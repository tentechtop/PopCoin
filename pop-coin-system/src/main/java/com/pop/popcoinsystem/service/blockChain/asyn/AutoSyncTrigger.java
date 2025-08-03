package com.pop.popcoinsystem.service.blockChain.asyn;

import com.pop.popcoinsystem.network.common.ExternalNodeInfo;
import com.pop.popcoinsystem.network.common.NodeInfo;
import com.pop.popcoinsystem.network.rpc.RpcProxyFactory;
import com.pop.popcoinsystem.network.service.KademliaNodeServer;
import com.pop.popcoinsystem.service.blockChain.BlockChainService;
import com.pop.popcoinsystem.util.BeanCopyUtils;
import com.pop.popcoinsystem.util.DifficultyUtils;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;


/**
 * 主动探测与触发机制  节点定期主动探测网络状态，自动触发同步（无需外部干预）
 */
@Slf4j
@Component
public class AutoSyncTrigger {
    @Autowired
    private KademliaNodeServer kademliaNodeServer;
    @Autowired
    private BlockChainService blockChainService;
    @Autowired
    private AsyncBlockSynchronizerImpl syncService;


    // 配置参数：通过外部配置注入，方便调整
    @Value("${system.blockchain.sync.healthy-node-score-threshold:60}")
    private int healthyNodeScoreThreshold;
    @Value("${system.blockchain.sync.max-blocks-per-round:100}")
    private int maxSyncBlocksPerRound;
    @Value("${system.blockchain.sync.fast-interval-seconds:30}")
    private int fastSyncInterval;
    @Value("${system.blockchain.sync.steady-interval-seconds:60}")
    private int steadySyncInterval;
    @Value("${system.blockchain.sync.detect-concurrency:5}")
    private int detectConcurrency; // 节点探测的最大并发数
    @Value("${system.blockchain.sync.rpc-timeout-ms:3000}")
    private int rpcTimeoutMs; // 远程调用超时时间


    // 健康节点评分阈值
    private static final int HEALTHY_NODE_SCORE_THRESHOLD = 60;
    // 单次最大同步区块数量
    private static final int MAX_SYNC_BLOCKS_PER_ROUND = 100;

    private ScheduledExecutorService scheduler; // 合并为一个调度器，减少线程资源占用
    private ExecutorService detectExecutor; // 用于并发探测节点的线程池
    private final AtomicBoolean isSyncing = new AtomicBoolean(false); // 同步任务状态标记，防止并发执行

    // 初始化定时任务
    @PostConstruct
    public void init() {
        //根据节点类型自治
        log.info("数据自治触发器初始化");
        // 初始化调度线程池（核心线程1个，足够处理定时任务）
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "block-sync-scheduler");
            thread.setDaemon(true); // 守护线程，随应用退出
            return thread;
        });

        // 初始化节点探测线程池
        detectExecutor = new ThreadPoolExecutor(
                detectConcurrency,
                detectConcurrency,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(100), // 限制任务队列大小
                r -> new Thread(r, "node-detect-worker"),
                new ThreadPoolExecutor.CallerRunsPolicy() // 队列满时让提交者执行，避免任务丢失
        );

        // 快速同步任务
        scheduler.scheduleAtFixedRate(
                this::detectAndSync, 0, fastSyncInterval, TimeUnit.SECONDS);

        // 稳态同步任务
        scheduler.scheduleAtFixedRate(
                this::steadyStateSync, 0, steadySyncInterval, TimeUnit.SECONDS);
    }

    // 探测网络并触发同步
    private void detectAndSync() {
        log.info("高频自治");
        long localHeight = blockChainService.getMainLatestHeight();
        byte[] localHash = blockChainService.getMainLatestBlockHash();
        byte[] localWork = blockChainService.getMainLatestBlock().getChainWork();

        // 获取所有邻居节点，筛选健康节点（评分>60分）
        List<ExternalNodeInfo> allNodes = kademliaNodeServer.getRoutingTable().findClosest();
        // 对每个健康节点发起同步（并发控制由syncService保证）
        for (ExternalNodeInfo externalNodeInfo : allNodes) {
            NodeInfo node = BeanCopyUtils.copyObject(externalNodeInfo, NodeInfo.class);
            try {
                // 探测节点最新状态
                RpcProxyFactory proxyFactory = new RpcProxyFactory(kademliaNodeServer, node);
                BlockChainService remoteService = proxyFactory.createProxy(BlockChainService.class);
                long remoteHeight = remoteService.getMainLatestHeight();
                byte[] remoteHash = remoteService.getMainLatestBlockHash();
                byte[] remoteWork = remoteService.getMainLatestBlock().getChainWork();
                log.info("自治 节点{}最新高度为: {}", node.getId(), remoteHeight);

                // 记录节点性能（探测延迟）
                long start = System.currentTimeMillis();
                boolean success = remoteHeight > 0; // 简单判断探测是否成功
                long latency = System.currentTimeMillis() - start;
                // 若远程链更优，触发同步
                if (DifficultyUtils.compare(localWork, remoteWork) < 0 || remoteHeight > localHeight) {
                    syncService.submitSyncTask(kademliaNodeServer, node, localHeight, localHash, localWork,
                            remoteHeight, remoteHash, remoteWork);
                }
            } catch (Exception e) {
                // 记录失败，降低节点评分
                log.warn("节点{}探测失败，降低评分", node.getId());
            }
        }
    }

    // 稳态同步（接近最新高度时，仅同步最新区块）
    private void steadyStateSync() {
        long localHeight = blockChainService.getMainLatestHeight();
        // 若本地高度与网络最高高度差距<5，进入稳态同步
        if (getNetworkMaxHeight() - localHeight < 5) {
            syncLatestBlocks(); // 只同步最新几个区块，避免批量同步消耗资源
        }
    }

    // 获取网络最高高度（遍历健康节点，取最高高度）
    private long getNetworkMaxHeight() {
        try {
            // 默认使用本地高度作为基准
            long maxHeight = blockChainService.getMainLatestHeight();
            List<ExternalNodeInfo> allNodes = kademliaNodeServer.getRoutingTable().findClosest();
            // 遍历健康节点获取最高高度
            for (ExternalNodeInfo nodeInfo : allNodes) {
                NodeInfo node = BeanCopyUtils.copyObject(nodeInfo, NodeInfo.class);
                try {
                    RpcProxyFactory proxyFactory = new RpcProxyFactory(kademliaNodeServer, node);
                    BlockChainService remoteService = proxyFactory.createProxy(BlockChainService.class);
                    long remoteHeight = remoteService.getMainLatestHeight();
                    if (remoteHeight > maxHeight) {
                        maxHeight = remoteHeight;
                        log.debug("更新网络最高高度为: {} (来自节点 {})", maxHeight, node.getId());
                    }
                } catch (Exception e) {
                    log.warn("获取节点{}的高度失败: {}", node.getId(), e.getMessage());
                }
            }
            return maxHeight;
        } catch (Exception e) {
            log.error("获取网络最高高度失败", e);
            return blockChainService.getMainLatestHeight();
        }
    }

    // 同步最新区块（增量同步）
    private void syncLatestBlocks() {
        log.info("低频自治");
        try {
            long localHeight = blockChainService.getMainLatestHeight();
            long networkMaxHeight = getNetworkMaxHeight();

            // 计算需要同步的区块范围
            long startHeight = localHeight + 1;
            long endHeight = networkMaxHeight;

            // 限制单次同步数量，防止过载
            if (endHeight - startHeight + 1 > MAX_SYNC_BLOCKS_PER_ROUND) {
                endHeight = startHeight + MAX_SYNC_BLOCKS_PER_ROUND - 1;
                log.warn("同步区块数量超过上限，本次同步: {} - {}", startHeight, endHeight);
            }

            if (startHeight > endHeight) {
                log.debug("无需同步最新区块，本地高度已同步: {}", localHeight);
                return;
            }

            log.info("开始增量同步最新区块，范围: {} - {}", startHeight, endHeight);

            // 获取健康节点并选择最优节点
            List<ExternalNodeInfo> allNodes = kademliaNodeServer.getRoutingTable().findClosest();
            // 选择最合适的同步节点
            // TODO: 根据节点评分、延迟、网络状态等因素选择最合适的节点
            ExternalNodeInfo bestNode = allNodes.get(0);

            if (bestNode == null) {
                log.warn("未找到合适的同步节点");
                return;
            }
            NodeInfo node = BeanCopyUtils.copyObject(bestNode, NodeInfo.class);
            // 提交增量同步任务
            syncService.submitRangeSyncTask(
                    kademliaNodeServer,
                    node,
                    startHeight,
                    endHeight
            );
            log.info("已提交区块同步任务，节点: {}, 范围: {} - {}",
                    node.getId(), startHeight, endHeight);

        } catch (Exception e) {
            log.error("同步最新区块失败", e);
        }
    }
}