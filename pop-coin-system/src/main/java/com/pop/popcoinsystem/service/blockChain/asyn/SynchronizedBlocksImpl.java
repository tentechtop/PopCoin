package com.pop.popcoinsystem.service.blockChain.asyn;

import com.pop.popcoinsystem.data.block.Block;
import com.pop.popcoinsystem.data.block.BlockHeader;
import com.pop.popcoinsystem.network.common.ExternalNodeInfo;
import com.pop.popcoinsystem.network.common.NodeInfo;
import com.pop.popcoinsystem.network.rpc.RpcProxyFactory;
import com.pop.popcoinsystem.network.service.KademliaNodeServer;
import com.pop.popcoinsystem.service.blockChain.BlockChainService;
import com.pop.popcoinsystem.service.blockChain.asyn.SyncProgress;
import com.pop.popcoinsystem.service.blockChain.asyn.SyncStatus;
import com.pop.popcoinsystem.service.blockChain.asyn.SyncTaskRecord;
import com.pop.popcoinsystem.storage.StorageService;
import com.pop.popcoinsystem.util.BeanCopyUtils;
import com.pop.popcoinsystem.util.CryptoUtil;
import com.pop.popcoinsystem.util.DifficultyUtils;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.net.ConnectException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static com.pop.popcoinsystem.constant.BlockChainConstants.RPC_TIMEOUT;
import static com.pop.popcoinsystem.data.block.Block.validateBlockHeaderPoW;



@Slf4j
@Component
public class SynchronizedBlocksImpl {
    @Autowired
    private StorageService popStorage;
    @Autowired
    private KademliaNodeServer kademliaNodeServer;
    @Lazy
    @Autowired
    private BlockChainService localBlockChainService;

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

    // 新增：高度到节点ID的映射（记录每个高度由哪个节点同步）
    // 键：区块高度，值：负责该高度的节点ID
    private final ConcurrentMap<Long, BigInteger> heightToNodeMap = new ConcurrentHashMap<>();

    // 节点评分管理器：维护节点可信度评分（1-100，默认60）
    private final ConcurrentMap<BigInteger, Integer> nodeScores = new ConcurrentHashMap<>();
    private static final int DEFAULT_NODE_SCORE = 60;
    private static final int MIN_NODE_SCORE = 10;
    private static final int SCORE_PENALTY = 15; // 每次降级扣分

    // 基础批次大小（可动态调整）
    private static final int BASE_BATCH_SIZE = 100;
    // 最大批次大小（避免单次加载过多）
    private static final int MAX_BATCH_SIZE = 500;
    // 最小批次大小（内存紧张时）
    private static final int MIN_BATCH_SIZE = 10;

    // 最大并发同步任务数（避免资源耗尽）
    private static final int MAX_CONCURRENT_TASKS = 6;
    // 并发任务计数器
    private final AtomicInteger activeTaskCount = new AtomicInteger(0);
    // 最大重试次数
    public static final int MAX_RETRY = 3;

    // 最大连续错误区块头数量
    private static final int MAX_CONTINUOUS_INVALID_HEADER = 10;
    // 错误区块头占比阈值（超过此比例则判定为大量错误）
    private static final double INVALID_HEADER_RATE_THRESHOLD = 0.3;

    private final Map<String, SyncTaskRecord> activeTasks = new ConcurrentHashMap<>();
    Map<String, CompletableFuture<SyncTaskRecord>> taskFutureMap = new ConcurrentHashMap<>();
    private ScheduledExecutorService scheduler; // 合并为一个调度器，减少线程资源占用
    private ExecutorService detectExecutor; // 用于并发探测节点的线程池

    // 同步中标记（避免并发冲突）
    private volatile boolean isSyncing = false;

    // 线程池优化：限制最大线程数，避免资源耗尽
    public final ExecutorService syncExecutor = new ThreadPoolExecutor(
            3, // 核心线程数
            10, // 最大线程数
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(100), // 任务队列限制
            runnable -> {
                Thread thread = new Thread(runnable);
                thread.setName("block-sync-worker");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.CallerRunsPolicy() // 队列满时让提交者执行，避免任务丢失 new ThreadPoolExecutor.AbortPolicy() // 任务满时拒绝，避免OOM
    );


    // 初始化：节点启动时恢复未完成的同步任务
    @PostConstruct
    public void init() {
        // 从存储恢复未完成的同步任务 正在进行 或者 暂停的任务
        List<SyncTaskRecord> unfinishedTasks = popStorage.getRunningSyncTasks();
        for (SyncTaskRecord task : unfinishedTasks) {
            if (task.getStatus() != SyncStatus.COMPLETED && task.getStatus() != SyncStatus.CANCELLED) {
                activeTasks.put(task.getTaskId(), task);
                log.info("恢复未完成任务(运行中或者暂停的任务): {}", task.getTaskId());
            }
        }

        //TODO
        // 加载节点评分（默认初始化）
        List<ExternalNodeInfo> nodes = kademliaNodeServer.getRoutingTable().getAllNodes();
        nodes.forEach(node -> nodeScores.putIfAbsent(node.getId(), DEFAULT_NODE_SCORE));


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
/*        scheduler.scheduleAtFixedRate(
                this::steadyStateSync, 0, steadySyncInterval, TimeUnit.SECONDS);*/
    }



    public void compareAndSync(NodeInfo remoteNode,
                                long localHeight, byte[] localHash, byte[] localTotalWork,
                                long remoteHeight, byte[] remoteHash, byte[] remoteTotalWork)
            throws ConnectException {
        // 情况0：节点正在同步中，拒绝新请求
        if (isSyncing) {
            log.info("节点正在同步中，暂不处理新请求（当前进度: {}%）", 1);
            return;
        }

        // 情况1：本地节点未初始化（无区块数据）仅仅一个创世区块
        if (localHeight == 0) {
            log.info("本地节点未初始化（高度: {}），请求远程完整链（远程高度: {}，远程总工作量: {}）",
                    localHeight, remoteHeight, remoteTotalWork);
            startSyncFromRemote(remoteNode, -1);  // 从初始状态同步
            return;
        }

        // 情况2：远程节点未初始化（无区块数据）
        if (remoteHeight == 0) {
            log.info("远程节点未初始化（高度: {}），等待远程节点拉取本地链（本地高度: {}，本地总工作量: {}）",
                    remoteHeight, localHeight, localTotalWork);
            return;
        }
        // 预检查：获取本地与远程的共同分叉点（即使高度不同也可能存在历史分叉）
        long forkHeight = findForkHeightWithBatchQuery(remoteNode, Math.min(localHeight, remoteHeight));

        // 情况3：本地链高度低于远程节点
        if (localHeight < remoteHeight) {
            // 远程高度高，且工作量更大（需要同步）
            if (DifficultyUtils.compare(remoteTotalWork,localTotalWork)==1) {
                // 存在历史分叉（共同高度后出现分歧）
                if (forkHeight < localHeight) {
                    log.info("本地链短、工作量小且存在历史分叉（本地高度:{}，远程高度:{}，分叉点:{}，本地总工作量:{}，远程总工作量:{}），从分叉点开始同步远程链",
                            localHeight, remoteHeight, forkHeight, localTotalWork, remoteTotalWork);
                    startSyncFromRemote(remoteNode, forkHeight);  // 从分叉点同步，覆盖本地分歧链
                } else {
                    // 无历史分叉，直接从本地当前高度同步
                    log.info("本地链落后且远程工作量更大（本地高度:{}，远程高度:{}，无历史分叉，本地总工作量:{}，远程总工作量:{}），开始同步远程链",
                            localHeight, remoteHeight, localTotalWork, remoteTotalWork);
                    startSyncFromRemote(remoteNode, localHeight);
                }
            } else {
                // 远程高度高但工作量更小（无效链），等待远程同步本地
                log.info("本地链高度低但工作量更大（本地高度:{}，远程高度:{}，本地总工作量:{}，远程总工作量:{}），等待远程节点同步本地链",
                        localHeight, remoteHeight, localTotalWork, remoteTotalWork);
            }
            return;
        }

        // 情况4：本地链高度高于远程节点
        if (localHeight > remoteHeight) {
            // 本地高度高，需判断工作量
            if (DifficultyUtils.compare(localTotalWork, remoteTotalWork) ==1) {
                // 本地高度高且工作量更大（有效链），无需同步
                log.info("本地链领先且工作量更大（本地高度:{}，远程高度:{}，本地总工作量:{}，远程总工作量:{}），状态正常",
                        localHeight, remoteHeight, localTotalWork, remoteTotalWork);
            } else {
                // 本地高度高但工作量更小（无效链），需同步远程
                // 检查是否存在历史分叉
                if (forkHeight < remoteHeight) {
                    log.info("本地链高但工作量小且存在历史分叉（本地高度:{}，远程高度:{}，分叉点:{}，本地总工作量:{}，远程总工作量:{}），从分叉点开始同步远程链",
                            localHeight, remoteHeight, forkHeight, localTotalWork, remoteTotalWork);
                    startSyncFromRemote(remoteNode, forkHeight);
                } else {
                    log.info("本地链高度高但工作量更小（本地高度:{}，远程高度:{}，无历史分叉，本地总工作量:{}，远程总工作量:{}），开始同步远程链",
                            localHeight, remoteHeight, localTotalWork, remoteTotalWork);
                    startSyncFromRemote(remoteNode, remoteHeight);
                }
            }
            return;
        }

        // 情况5：高度相同但哈希不同（存在分叉）
        if (!Arrays.equals(localHash, remoteHash)) {
            // 分叉时以工作量大的链为准
            if (DifficultyUtils.compare(localTotalWork, remoteTotalWork) == 1) {
                log.info("链分叉（高度:{}），本地工作量更大（本地:{}，远程:{}），等待远程同步本地链",
                        localHeight, localTotalWork, remoteTotalWork);
            } else if (DifficultyUtils.compare(remoteTotalWork,localTotalWork)==1) {
                log.info("链分叉（高度:{}），远程工作量更大（本地:{}，远程:{}），开始同步远程链",
                        localHeight, localTotalWork, remoteTotalWork);
                startSyncFromRemote(remoteNode, localHeight);
            } else {
                // 工作量相同的特殊情况（极罕见），可根据策略保留本地链
                log.info("链分叉（高度:{}），双方工作量相同（{}），保留本地链",
                        localHeight, localTotalWork);
            }
            return;
        }

        // 情况6：高度相同且哈希一致（状态正常）
        log.info("本地链与远程链状态一致（高度: {}，哈希: {}，总工作量: {}）",
                localHeight, CryptoUtil.bytesToHex(localHash), localTotalWork);
    }


    /**
     * 高效查找本地链与远程链的分叉点高度
     * 采用二分查找法，时间复杂度优化为O(log n)
     * @param remoteNode 远程节点信息
     * @param maxCommonHeight 理论上的最大共同高度（取本地和远程高度的最小值）
     * @return 分叉点高度（-1表示无共同历史）
     */
    private long findForkHeight(NodeInfo remoteNode, long maxCommonHeight) {
        RpcProxyFactory proxyFactory = new RpcProxyFactory(kademliaNodeServer, remoteNode);
        BlockChainService remoteService = proxyFactory.createProxy(BlockChainService.class);
        // 边界校验：若最大共同高度为负数，直接返回无共同历史
        if (maxCommonHeight < 0) {
            return -1;
        }

        long low = 0;
        long high = maxCommonHeight;
        long forkHeight = -1;  // 初始化分叉点为无共同历史

        // 缓存已查询的哈希，避免重复网络请求或本地查询
        Map<Long, byte[]> localHashCache = new HashMap<>();
        Map<Long, byte[]> remoteHashCache = new HashMap<>();

        try {
            // 二分查找主逻辑
            while (low <= high) {
                // 计算中间高度（避免溢出）
                long mid = low + (high - low) / 2;

                // 获取本地区块哈希（优先从缓存获取）
                byte[] localHash = localHashCache.get(mid);
                if (localHash == null) {
                    localHash = localBlockChainService.getBlockHash(mid);
                    localHashCache.put(mid, localHash);
                }

                // 获取远程区块哈希（优先从缓存获取）
                byte[] remoteHash = remoteHashCache.get(mid);
                if (remoteHash == null) {
                    remoteHash = remoteService.getBlockHash(mid);
                    remoteHashCache.put(mid, remoteHash);
                }

                // 哈希相同：说明此高度及以下可能存在共同历史，尝试查找更高的共同高度
                if (Arrays.equals(localHash, remoteHash)) {
                    forkHeight = mid;  // 临时记录当前共同高度
                    low = mid + 1;    // 向更高高度查找
                } else {
                    // 哈希不同：分叉点在更低高度，缩小上边界
                    high = mid - 1;
                }
            }
        } catch (Exception e) {
            log.error("分叉点检测失败，最大共同高度:{}", maxCommonHeight, e);
            // 异常情况下尝试返回已找到的临时分叉点（可能不准确，但避免完全失败）
            return forkHeight != -1 ? forkHeight : -1;
        }

        return forkHeight;
    }

    /**
     * 进一步优化：批量查询哈希（适用于支持批量接口的场景）
     * 减少网络交互次数，尤其在跨节点通信时提升性能
     */
    private long findForkHeightWithBatchQuery(NodeInfo remoteNode, long maxCommonHeight) {
        RpcProxyFactory proxyFactory = new RpcProxyFactory(kademliaNodeServer, remoteNode);
        BlockChainService remoteService = proxyFactory.createProxy(BlockChainService.class);

        if (maxCommonHeight < 0) {
            return -1;
        }

        long low = 0;
        long high = maxCommonHeight;
        long forkHeight = -1;

        while (low <= high) {
            long mid = low + (high - low) / 2;

            try {
                // 批量获取一段高度范围的哈希（如mid-1, mid, mid+1）
                // 减少单次网络请求的开销，尤其适用于远程节点查询
                List<Long> heightsToCheck = Arrays.asList(mid, low, high);
                Map<Long, byte[]> localHashes = localBlockChainService.getBlockHashes(heightsToCheck);
                Map<Long, byte[]> remoteHashes = remoteService.getBlockHashes(heightsToCheck);

                byte[] localHash = localHashes.get(mid);
                byte[] remoteHash = remoteHashes.get(mid);

                if (Arrays.equals(localHash, remoteHash)) {
                    forkHeight = mid;
                    low = mid + 1;
                } else {
                    high = mid - 1;
                }
            } catch (Exception e) {
                log.error("批量查询哈希失败，中间高度:{}", mid, e);
                high = mid - 1;  // 出错时缩小范围，避免死循环
            }
        }

        return forkHeight;
    }


    private void startSyncFromRemote(NodeInfo remoteNode, long startHeight) {
        // 从远程节点同步链的实现

    }



    /**
     * 提交差异
     * 节点握手确定差异
     * 定期检查差异并同步
     *    同步时合并差异
     *    根据合并后的差异建立同步任务  并行执行
     *    将下载的区块暂存到 下载数据库
     *    检查全部下载完就开始从开始位置慢慢补充到主链
     *
     *    要检查开始高度 和开始高度hash
     *    如果不重叠且间隙 hash一致 也就是 [100 hasha1 - 200 hash2] [101hash3-200hash4]
     *    hash3的父hash是hash2 就允许创建一个新的任务
     *
     */
    public CompletableFuture<SyncTaskRecord> SubmitDifference(NodeInfo remoteNode,long localHeight, long remoteHeight,byte[] localWork,byte[] remoteWork,byte[] localHash,byte[] remoteHash) {
        List<ExternalNodeInfo> candidateNodes = kademliaNodeServer.getRoutingTable().findClosest();










        long newTaskStart = localHeight + 1;
        // 2. 检查并处理与已有任务的重叠
        List<SyncTaskRecord> overlappingTasks = findOverlappingTasks(newTaskStart, remoteHeight);
        // 计算需要修剪的起始高度（取所有重叠任务的最大结束高度 + 1）
        long adjustedStart = newTaskStart;
        if (!overlappingTasks.isEmpty()) {
            long maxEndHeight = overlappingTasks.stream()
                    .mapToLong(SyncTaskRecord::getTargetHeight)
                    .max()
                    .orElse(newTaskStart - 1);

            adjustedStart = maxEndHeight + 1;
            log.info("检测到与{}个任务重叠，调整新任务起始高度从{}到{}",
                    overlappingTasks.size(), newTaskStart, adjustedStart);
        }

        // 如果调整后起始高度超过结束高度，说明无需创建新任务
        if (adjustedStart > remoteHeight) {
            log.info("新任务范围已被已有任务完全覆盖，无需创建新任务");
            return CompletableFuture.completedFuture(null);
        }

        // 3. 筛选高评分节点（评分>40）
        List<ExternalNodeInfo> validNodes = candidateNodes.stream()
                .filter(node -> getNodeScore(node.getId()) > 40)
                .sorted(Comparator.comparingInt(node -> -getNodeScore(node.getId()))) // 按评分降序
                .toList();
        if (validNodes.isEmpty()) {
            log.error("无可用高评分节点，同步任务创建失败");
            return CompletableFuture.failedFuture(new IllegalStateException("无可用同步节点"));
        }

        // 4. 创建修剪后的新任务
        SyncTaskRecord task = createSyncProgresses(adjustedStart, remoteHeight, validNodes);
        log.info("创建新同步任务：{}", task);
        activeTasks.put(task.getTaskId(), task);
        popStorage.saveSyncTaskRecord(task);
        activeTasks.put(task.getTaskId(), task);
        // 5. 检查并发限制，提交任务执行
        if (activeTaskCount.get() >= MAX_CONCURRENT_TASKS) {
            log.warn("达到最大并发任务数({})，任务[{}]进入等待队列", MAX_CONCURRENT_TASKS, task.getTaskId());
        }
        CompletableFuture<SyncTaskRecord> taskFuture = executeSyncTask(task).whenComplete((completedTask, ex) -> {
            activeTaskCount.decrementAndGet();
            if (ex != null) {
                log.error("任务[{}]执行失败", task.getTaskId(), ex);
                completedTask.setStatus(SyncStatus.FAILED);
                completedTask.setErrorMsg(ex.getMessage());
            } else {
                log.info("任务[{}]执行完成", task.getTaskId());
            }
            // 持久化任务状态
            popStorage.saveSyncTaskRecord(completedTask);
        });
        taskFutureMap.put(task.getTaskId(), taskFuture);
        activeTaskCount.incrementAndGet();
        return taskFuture;
    }



    public CompletableFuture<SyncTaskRecord> SubmitDifference(long localHeight, long remoteHeight) {
        List<ExternalNodeInfo> candidateNodes = kademliaNodeServer.getRoutingTable().findClosest();
        log.info("提交差异：本地高度={}, 远程高度={}", localHeight, remoteHeight);
        // 1. 验证差异有效性
        if (remoteHeight <= localHeight) {
            log.warn("远程高度({})不大于本地高度({})，无需同步", remoteHeight, localHeight);
            return CompletableFuture.completedFuture(null);
        }
        long newTaskStart = localHeight + 1;
        // 2. 检查并处理与已有任务的重叠
        List<SyncTaskRecord> overlappingTasks = findOverlappingTasks(newTaskStart, remoteHeight);
        // 计算需要修剪的起始高度（取所有重叠任务的最大结束高度 + 1）
        long adjustedStart = newTaskStart;
        if (!overlappingTasks.isEmpty()) {
            long maxEndHeight = overlappingTasks.stream()
                    .mapToLong(SyncTaskRecord::getTargetHeight)
                    .max()
                    .orElse(newTaskStart - 1);

            adjustedStart = maxEndHeight + 1;
            log.info("检测到与{}个任务重叠，调整新任务起始高度从{}到{}",
                    overlappingTasks.size(), newTaskStart, adjustedStart);
        }

        // 如果调整后起始高度超过结束高度，说明无需创建新任务
        if (adjustedStart > remoteHeight) {
            log.info("新任务范围已被已有任务完全覆盖，无需创建新任务");
            return CompletableFuture.completedFuture(null);
        }

        // 3. 筛选高评分节点（评分>40）
        List<ExternalNodeInfo> validNodes = candidateNodes.stream()
                .filter(node -> getNodeScore(node.getId()) > 40)
                .sorted(Comparator.comparingInt(node -> -getNodeScore(node.getId()))) // 按评分降序
                .toList();
        if (validNodes.isEmpty()) {
            log.error("无可用高评分节点，同步任务创建失败");
            return CompletableFuture.failedFuture(new IllegalStateException("无可用同步节点"));
        }

        // 4. 创建修剪后的新任务
        SyncTaskRecord task = createSyncProgresses(adjustedStart, remoteHeight, validNodes);
        log.info("创建新同步任务：{}", task);
        activeTasks.put(task.getTaskId(), task);
        popStorage.saveSyncTaskRecord(task);
        activeTasks.put(task.getTaskId(), task);
        // 5. 检查并发限制，提交任务执行
        if (activeTaskCount.get() >= MAX_CONCURRENT_TASKS) {
            log.warn("达到最大并发任务数({})，任务[{}]进入等待队列", MAX_CONCURRENT_TASKS, task.getTaskId());
        }
        CompletableFuture<SyncTaskRecord> taskFuture = executeSyncTask(task).whenComplete((completedTask, ex) -> {
                    activeTaskCount.decrementAndGet();
                    if (ex != null) {
                        log.error("任务[{}]执行失败", task.getTaskId(), ex);
                        completedTask.setStatus(SyncStatus.FAILED);
                        completedTask.setErrorMsg(ex.getMessage());
                    } else {
                        log.info("任务[{}]执行完成", task.getTaskId());
                    }
                    // 持久化任务状态
                    popStorage.saveSyncTaskRecord(completedTask);
                });
        taskFutureMap.put(task.getTaskId(), taskFuture);
        activeTaskCount.incrementAndGet();
        return taskFuture;
    }


    /**
     * 查找与指定范围重叠的所有活跃任务
     */
    private List<SyncTaskRecord> findOverlappingTasks(long start, long end) {
        return activeTasks.values().stream()
                .filter(task ->
                        // 任务状态为运行中或未完成
                        task.getStatus() != SyncStatus.COMPLETED &&
                                task.getStatus() != SyncStatus.CANCELLED &&
                                // 范围存在重叠
                                !(task.getTargetHeight() < start || task.getStartHeight() > end)
                )
                .collect(Collectors.toList());
    }

    private void detectAndSync() {
        printRunningSyncTasks();
        long localHeight = localBlockChainService.getMainLatestHeight();
        byte[] localWork = localBlockChainService.getMainLatestBlock().getChainWork();
        // 获取所有邻居节点，筛选健康节点（评分>60分）
        List<ExternalNodeInfo> allNodes = kademliaNodeServer.getRoutingTable().findClosest();

        log.info("离自己最近的节点{}", allNodes);
        // 对每个健康节点发起同步（并发控制由syncService保证）
        for (ExternalNodeInfo externalNodeInfo : allNodes) {
            NodeInfo node = BeanCopyUtils.copyObject(externalNodeInfo, NodeInfo.class);
            try {
                // 探测节点最新状态
                RpcProxyFactory proxyFactory = new RpcProxyFactory(kademliaNodeServer, node);
                BlockChainService remoteService = proxyFactory.createProxy(BlockChainService.class);
                long remoteHeight = remoteService.getMainLatestHeight();
                byte[] remoteWork = remoteService.getMainLatestBlock().getChainWork();
                long mainLatestHeight = localBlockChainService.getMainLatestHeight();
                log.info("探测 节点{}最新高度为: {}", node.getId(), remoteHeight);
                // 记录节点性能（探测延迟）
                long start = System.currentTimeMillis();
                boolean success = remoteHeight > 0; // 简单判断探测是否成功
                long latency = System.currentTimeMillis() - start;
                // 若远程链更优，触发同步
                if (remoteHeight > localHeight) {
                    log.info("探测 节点{}链更优，开始同步", node.getId());
                    SubmitDifference(remoteHeight,mainLatestHeight);
                }
            } catch (Exception e) {
                // 记录失败，降低节点评分
                log.warn("节点{}探测失败，降低评分", node.getId());
                //删除
                kademliaNodeServer.removeNode(node.getId());
            }
        }
    }


    /**
     * 打印所有正在同步的任务（状态为RUNNING的任务）
     */
    public void printRunningSyncTasks() {
        // 筛选出状态为运行中的任务
        List<SyncTaskRecord> runningTasks = activeTasks.values().stream()
                .filter(task -> task.getStatus() == SyncStatus.RUNNING)
                .toList();

        if (runningTasks.isEmpty()) {
            log.info("当前没有正在同步的任务");
            return;
        }

        log.info("===== 正在同步的任务列表（共{}个） =====", runningTasks.size());
        for (SyncTaskRecord task : runningTasks) {
            // 计算任务整体进度（基于所有分片的完成情况）
            double totalProgress = calculateTaskTotalProgress(task);

            // 打印任务基本信息
            log.info("任务ID: {}", task.getTaskId());
            log.info("  同步范围: 从高度{}到{}", task.getStartHeight(), task.getTargetHeight());
            log.info("  整体进度: {}%", totalProgress);
            log.info("  开始时间: {}", task.getCreateTime());
            log.info("  最后更新时间: {}", task.getUpdateTime());

            // 打印各分片进度详情
            log.info("  分片任务详情:");
            for (SyncProgress shard : task.getSyncProgressList()) {
                log.info("    分片ID: {} (节点: {})", shard.getProgressId(), shard.getNodeId().toString().substring(0, 8));
                log.info("      分片范围: {} - {}", shard.getStartHeight(), shard.getTargetHeight());
                log.info("      分片进度: {}% (已同步{}个区块)",
                        shard.getProgressPercent(), shard.getSyncedBlocks());
                log.info("      分片状态: {}", shard.getStatus());
            }
            log.info("----------------------------------------");
        }
    }

    // 稳态同步（接近最新高度时，仅同步最新区块）
    private void steadyStateSync() {
        long localHeight = localBlockChainService.getMainLatestHeight();
        // 若本地高度与网络最高高度差距<5，进入稳态同步
        if (getNetworkMaxHeight() - localHeight < 5) {
            syncLatestBlocks(); // 只同步最新几个区块，避免批量同步消耗资源
        }
    }


    // 同步最新区块（增量同步）
    private void syncLatestBlocks() {

    }


    // 获取网络最高高度（遍历健康节点，取最高高度）
    private long getNetworkMaxHeight() {
        try {
            // 默认使用本地高度作为基准
            long maxHeight = localBlockChainService.getMainLatestHeight();
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
            return localBlockChainService.getMainLatestHeight();
        }
    }












    /**
     * 执行完整同步任务（包含所有分片）
     */
// 修改executeSyncTask方法中的任务提交逻辑
    private CompletableFuture<SyncTaskRecord> executeSyncTask(SyncTaskRecord task) {
        task.setStatus(SyncStatus.RUNNING);
        task.setUpdateTime(LocalDateTime.now());

        // 提交所有分片任务并行执行
        List<CompletableFuture<SyncProgress>> shardFutures = task.getSyncProgressList().stream()
                .map(shard -> executeShardTaskBatch(shard, task))
                .toList();

        // 等待所有分片完成后合并区块
        return CompletableFuture.allOf(shardFutures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    // 检查所有分片状态
                    boolean allCompleted = shardFutures.stream()
                            .allMatch(future -> future.join().getStatus() == SyncStatus.COMPLETED);

                    if (allCompleted) {
                        // 合并暂存区块到主链
                        mergeDownloadedBlocksBatch(task.getStartHeight(), task.getTargetHeight());
                        task.setStatus(SyncStatus.COMPLETED);
                    } else {
                        task.setStatus(SyncStatus.PARTIALLY_COMPLETED);
                        task.setErrorMsg("部分分片任务执行失败");
                    }

                    task.setUpdateTime(LocalDateTime.now());
                    return task;
                })
                // 在任务最终完成时自动删除
                .whenComplete((completedTask, ex) -> {
                    activeTaskCount.decrementAndGet();
                    if (ex != null) {
                        log.error("任务[{}]执行失败", task.getTaskId(), ex);
                        completedTask.setStatus(SyncStatus.FAILED);
                        completedTask.setErrorMsg(ex.getMessage());
                    } else {
                        log.info("任务[{}]执行完成", task.getTaskId());
                    }
                    // 持久化任务状态（保留历史记录，仅删除内存中的活跃任务）
                    popStorage.saveSyncTaskRecord(completedTask);
                    // 自动删除内存中的任务记录
                    removeTask(task.getTaskId());
                });
    }


    private CompletableFuture<SyncProgress> executeShardTaskBatch(SyncProgress progress, SyncTaskRecord mainTask) {
        return CompletableFuture.supplyAsync(() -> {
            progress.setStatus(SyncStatus.RUNNING);
            progress.setLastSyncTime(LocalDateTime.now());
            long currentHeight = progress.getStartHeight(); // 当前批次起始高度
            int batchRetryCount = 0; // 批次级重试计数

            try {
                while (currentHeight <= progress.getTargetHeight()) {
                    // 检查任务是否已被终止
                    if (mainTask.getStatus() == SyncStatus.CANCELLED || mainTask.getStatus() == SyncStatus.FAILED) {
                        progress.setStatus(SyncStatus.CANCELLED);
                        progress.setErrorMsg("主任务已终止");
                        return progress;
                    }

                    // 计算当前批次的结束高度（不超过分片目标高度）
                    long batchEnd = Math.min(currentHeight + BASE_BATCH_SIZE - 1, progress.getTargetHeight());
                    int batchSize = (int) (batchEnd - currentHeight + 1);
                    log.debug("分片[{}]开始请求批次: {} - {} (共{}个区块)",
                            progress.getProgressId(), currentHeight, batchEnd, batchSize);

                    // 批量获取区块头（带重试）
                    List<BlockHeader> batchHeaders = null;
                    try {
                        batchHeaders = fetchBlockHeadersInBatch(progress.getNodeId(), currentHeight, batchSize);
                        batchRetryCount = 0; // 成功后重置重试计数

                        // 验证返回数量是否匹配请求数量
                        if (batchHeaders == null || batchHeaders.size() != batchSize) {
                            log.warn("批次[{} - {}]返回数量不匹配: 请求{}个, 实际{}个",
                                    currentHeight, batchEnd, batchSize,
                                    (batchHeaders == null ? 0 : batchHeaders.size()));

                            if (handleEmptyOrMismatchedBatch(progress, currentHeight, batchEnd, batchSize,
                                    (batchHeaders == null ? 0 : batchHeaders.size()))) {
                                terminateTask(mainTask, progress, progress.getErrorMsg());
                                return progress;
                            }
                            currentHeight = batchEnd + 1;
                            continue;
                        }
                    } catch (Exception e) {
                        batchRetryCount++;
                        log.warn("批次[{} - {}]请求失败，重试次数({}/{})",
                                currentHeight, batchEnd, batchRetryCount, MAX_RETRY, e);
                        if (batchRetryCount >= MAX_RETRY) {
                            log.error("批次[{} - {}]达到最大重试次数，标记为失败", currentHeight, batchEnd);
                            progress.setErrorMsg("批次请求失败: " + e.getMessage());
                            terminateTask(mainTask, progress, progress.getErrorMsg());
                            return progress;
                        }
                        // 指数退避重试（1s, 2s, 4s...）
                        Thread.sleep(1000L * (1 << batchRetryCount));
                        continue;
                    }

                    // 建立高度与区块头的映射关系（核心优化点）
                    Map<Long, BlockHeader> heightToHeaderMap = new HashMap<>(batchSize);
                    for (int i = 0; i < batchHeaders.size(); i++) {
                        // 计算当前索引对应的高度
                        long height = currentHeight + i;
                        heightToHeaderMap.put(height, batchHeaders.get(i));
                    }

                    // 验证并处理批次中的每个区块头，传入高度映射
                    boolean batchValid = processBlockHeaderBatch(progress, currentHeight, batchEnd, heightToHeaderMap);
                    if (!batchValid) {
                        // 批次处理失败（达到错误阈值）
                        terminateTask(mainTask, progress, progress.getErrorMsg());
                        return progress;
                    }

                    // 批次处理成功，更新当前高度
                    currentHeight = batchEnd + 1;
                    // 更新进度信息
                    progress.setCurrentHeight(batchEnd);
                    progress.setProgressPercent(calculateProgress(progress));
                    progress.setLastSyncTime(LocalDateTime.now());
                    log.info("分片[{}]完成批次: {} - {}，累计同步{}个区块，进度: {}%",
                            progress.getProgressId(), currentHeight - batchSize, batchEnd,
                            progress.getSyncedBlocks(), progress.getProgressPercent());

                    //休眠
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        progress.setStatus(SyncStatus.CANCELLED);
                        progress.setErrorMsg("任务被中断");
                        return progress;
                    }
                }

                // 分片完成
                progress.setStatus(SyncStatus.COMPLETED);
                progress.setProgressPercent(100.0);
                return progress;

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                progress.setStatus(SyncStatus.CANCELLED);
                progress.setErrorMsg("任务被中断");
                return progress;
            } catch (Exception e) {
                progress.setStatus(SyncStatus.FAILED);
                progress.setErrorMsg("分片执行异常: " + e.getMessage());
                terminateTask(mainTask, progress, progress.getErrorMsg());
                return progress;
            }
        }, syncExecutor);
    }


    /**
     * 处理批量获取的区块头（使用高度索引映射）
     */
    private boolean processBlockHeaderBatch(SyncProgress progress, long batchStart, long batchEnd,
                                            Map<Long, BlockHeader> heightToHeaderMap) {
        int validCount = 0;
        int invalidCount = 0;
        int missingCount = 0; // 新增：记录缺失的区块头数量
        boolean hasContinuousInvalid = false;

        // 遍历批次内所有高度，通过映射获取对应的区块头
        for (long height = batchStart; height <= batchEnd; height++) {
            BlockHeader header = heightToHeaderMap.get(height);

            if (header == null) {
                // 区块头缺失
                log.warn("高度[{}]的区块头缺失", height);
                missingCount++;
                invalidCount++; // 缺失也视为无效
                progress.setContinuousInvalidHeaderCount(progress.getContinuousInvalidHeaderCount() + 1);
                if (progress.getContinuousInvalidHeaderCount() >= MAX_CONTINUOUS_INVALID_HEADER) {
                    hasContinuousInvalid = true;
                }
                continue;
            }

            // 验证区块头PoW
            boolean isValid = validateBlockHeaderPoW(header);
            if (isValid) {
                // 有效区块头：保存并重置连续无效计数
                popStorage.saveDownloadedHeader(header, height);
                validCount++;
                progress.setContinuousInvalidHeaderCount(0);
            } else {
                // 无效区块头：累积计数
                invalidCount++;
                progress.setContinuousInvalidHeaderCount(progress.getContinuousInvalidHeaderCount() + 1);
                if (progress.getContinuousInvalidHeaderCount() >= MAX_CONTINUOUS_INVALID_HEADER) {
                    hasContinuousInvalid = true;
                }
            }
        }

        // 更新进度计数
        progress.setSyncedBlocks(progress.getSyncedBlocks() + validCount);
        progress.setTotalValidHeaderCount(progress.getTotalValidHeaderCount() + validCount);
        progress.setTotalInvalidHeaderCount(progress.getTotalInvalidHeaderCount() + invalidCount);

        // 检查是否达到错误阈值
        int totalProcessed = (int) (batchEnd - batchStart + 1); // 总应处理数量
        double invalidRate = totalProcessed == 0 ? 0 : (double) invalidCount / totalProcessed;

        if (hasContinuousInvalid || invalidRate >= INVALID_HEADER_RATE_THRESHOLD) {
            progress.setErrorMsg(String.format(
                    "批次验证失败: 有效=%d, 无效=%d(含缺失%d), 连续无效=%d, 错误率=%.2f",
                    validCount, invalidCount, missingCount,
                    progress.getContinuousInvalidHeaderCount(), invalidRate));
            return false;
        }

        return true;
    }

    /**
     * 处理空批次或数量不匹配的批次响应
     */
    private boolean handleEmptyOrMismatchedBatch(SyncProgress progress, long batchStart, long batchEnd,
                                                 int expectedCount, int actualCount) {
        int missingCount = expectedCount - actualCount;
        progress.setTotalInvalidHeaderCount(progress.getTotalInvalidHeaderCount() + missingCount);
        progress.setContinuousInvalidHeaderCount(progress.getContinuousInvalidHeaderCount() + missingCount);

        int totalProcessed = progress.getTotalValidHeaderCount() + progress.getTotalInvalidHeaderCount();
        double invalidRate = totalProcessed == 0 ? 0 :
                (double) progress.getTotalInvalidHeaderCount() / totalProcessed;

        boolean shouldTerminate =
                progress.getContinuousInvalidHeaderCount() >= MAX_CONTINUOUS_INVALID_HEADER ||
                        invalidRate >= INVALID_HEADER_RATE_THRESHOLD;

        if (shouldTerminate) {
            progress.setErrorMsg(String.format(
                    "批次数量不匹配: 期望=%d, 实际=%d, 缺失=%d, 连续无效=%d, 错误率=%.2f",
                    expectedCount, actualCount, missingCount,
                    progress.getContinuousInvalidHeaderCount(), invalidRate));
        }
        return shouldTerminate;
    }





    // 节点评分管理工具方法
    private int getNodeScore(BigInteger nodeId) {
        return nodeScores.getOrDefault(nodeId, DEFAULT_NODE_SCORE);
    }
    private void degradeNodeScore(BigInteger nodeId, String reason) {
        nodeScores.compute(nodeId, (id, score) -> {
            int newScore = (score == null ? DEFAULT_NODE_SCORE : score) - SCORE_PENALTY;
            int finalScore = Math.max(newScore, MIN_NODE_SCORE);
            log.warn("节点[{}]评分从{}降至{}，原因: {}", id, score, finalScore, reason);
            return finalScore;
        });
    }
    private double calculateProgress(SyncProgress progress) {
        long total = progress.getTargetHeight() - progress.getStartHeight() + 1;
        long completed = progress.getCurrentHeight() - progress.getStartHeight() + 1;
        return total == 0 ? 0 : (double) completed / total * 100;
    }

    /**
     * 终止同步任务
     */
    public void stopTask(String taskId) {
        SyncTaskRecord task = activeTasks.get(taskId);
        if (task == null) {
            log.warn("任务[{}]不存在", taskId);
            return;
        }

        // 更新任务状态
        task.setStatus(SyncStatus.CANCELLED);
        task.setUpdateTime(LocalDateTime.now());
        task.setErrorMsg("任务被手动终止");
        activeTasks.put(taskId, task);
        popStorage.saveSyncTaskRecord(task);

        // 取消Future
        CompletableFuture<SyncTaskRecord> future = taskFutureMap.get(taskId);
        if (future != null && !future.isDone()) {
            future.cancel(true);
            log.info("任务[{}]已取消", taskId);
        } else {
            log.info("任务[{}]已完成或不存在，无需取消", taskId);
        }

        // 手动终止后自动删除任务
        removeTask(taskId);
    }


    /**
     * 重启失败的同步任务
     */
    public CompletableFuture<SyncTaskRecord> restartTask(String taskId) {
        SyncTaskRecord task = activeTasks.get(taskId);
        if (task == null) {
            log.warn("任务[{}]不存在", taskId);
            return CompletableFuture.failedFuture(new IllegalArgumentException("任务不存在"));
        }

        if (task.getStatus() == SyncStatus.RUNNING) {
            log.warn("任务[{}]正在执行，无需重启", taskId);
            return taskFutureMap.getOrDefault(taskId, CompletableFuture.completedFuture(task));
        }

        // 重置任务状态
        task.setStatus(SyncStatus.INIT);
        task.getSyncProgressList().forEach(shard -> {
            shard.setStatus(SyncStatus.INIT);
            shard.setCurrentHeight(shard.getStartHeight() - 1);
            shard.setSyncedBlocks(0);
            shard.setProgressPercent(0.0);
            shard.setContinuousInvalidHeaderCount(0);
            shard.setTotalValidHeaderCount(0);
            shard.setTotalInvalidHeaderCount(0);
            shard.setErrorMsg(null);
        });
        task.setUpdateTime(LocalDateTime.now());
        activeTasks.put(taskId, task);
        popStorage.saveSyncTaskRecord(task);

        // 重新提交任务
        CompletableFuture<SyncTaskRecord> newFuture = executeSyncTask(task)
                .whenComplete((t, ex) -> activeTaskCount.decrementAndGet());
        taskFutureMap.put(taskId, newFuture);
        activeTaskCount.incrementAndGet();
        log.info("任务[{}]已重启", taskId);
        return newFuture;
    }



    /**
     * 执行分片同步任务
     */
    private CompletableFuture<SyncProgress> executeShardTask(SyncProgress progress, SyncTaskRecord mainTask) {
        return CompletableFuture.supplyAsync(() -> {
            progress.setStatus(SyncStatus.RUNNING);
            progress.setLastSyncTime(LocalDateTime.now());
            long currentHeight = progress.getStartHeight();
            int retryCount = 0;

            try {
                while (currentHeight <= progress.getTargetHeight()) {
                    // 检查任务是否已被终止
                    if (mainTask.getStatus() == SyncStatus.CANCELLED || mainTask.getStatus() == SyncStatus.FAILED) {
                        progress.setStatus(SyncStatus.CANCELLED);
                        progress.setErrorMsg("主任务已终止");
                        return progress;
                    }
                    // 获取区块头（带重试）
                    BlockHeader header = null;
                    try {
                        header = fetchBlockHeaderFromNode(progress.getNodeId(), currentHeight);
                        retryCount = 0; // 成功后重置重试计数
                    } catch (Exception e) {
                        retryCount++;
                        log.warn("获取区块[{}]失败，重试次数({}/{})", currentHeight, retryCount, MAX_RETRY, e);
                        if (retryCount >= MAX_RETRY) {
                            log.error("获取区块[{}]达到最大重试次数，标记为无效", currentHeight);
                            if (handleHeaderValidationResult(progress, false)) {
                                terminateTask(mainTask, progress, progress.getErrorMsg());
                                return progress;
                            }
                            currentHeight++;
                            continue;
                        }
                        Thread.sleep(1000L * retryCount); // 指数退避重试
                        continue;
                    }
                    // 验证区块头
                    boolean isValid = validateBlockHeaderPoW(header);
                    if (handleHeaderValidationResult(progress, isValid)) {
                        terminateTask(mainTask, progress, progress.getErrorMsg());
                        return progress;
                    }
                    // 保存有效区块头
                    if (isValid) {
                        popStorage.saveDownloadedHeader(header,currentHeight);
                        progress.setCurrentHeight(currentHeight);
                        progress.setSyncedBlocks(progress.getSyncedBlocks() + 1);
                        progress.setProgressPercent(calculateProgress(progress));
                        progress.setLastSyncTime(LocalDateTime.now());
                        log.info("分片[{}]同步到高度[{}]，进度: {}%",
                                progress.getProgressId(), currentHeight, progress.getProgressPercent());
                    }

                    currentHeight++;
                }
                // 分片完成
                progress.setStatus(SyncStatus.COMPLETED);
                progress.setProgressPercent(100.0);
                return progress;

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                progress.setStatus(SyncStatus.CANCELLED);
                progress.setErrorMsg("任务被中断");
                return progress;
            } catch (Exception e) {
                progress.setStatus(SyncStatus.FAILED);
                progress.setErrorMsg("分片执行异常: " + e.getMessage());
                terminateTask(mainTask, progress, progress.getErrorMsg());
                return progress;
            }
        }, syncExecutor);
    }

    private BlockHeader fetchBlockHeaderFromNode(BigInteger nodeId, long currentHeight) {
        NodeInfo remoteNode = kademliaNodeServer.getNodeInfo(nodeId);
        if (remoteNode == null) {
            throw new RuntimeException("节点不可用: " + nodeId);
        }
        RpcProxyFactory proxyFactory = new RpcProxyFactory(kademliaNodeServer, remoteNode);
        proxyFactory.setTimeout(RPC_TIMEOUT);
        BlockChainService remoteService = proxyFactory.createProxy(BlockChainService.class);
        BlockHeader blockHeader = remoteService.getBlockHeader(currentHeight);
        log.debug("获取区块[{}]成功", currentHeight);
        return blockHeader;
    }

    /**
     * 批量获取区块头
     */
    private List<BlockHeader> fetchBlockHeadersInBatch(BigInteger nodeId, long startHeight, int count) {
        NodeInfo remoteNode = kademliaNodeServer.getNodeInfo(nodeId);
        if (remoteNode == null) {
            throw new RuntimeException("节点不可用: " + nodeId);
        }
        RpcProxyFactory proxyFactory = new RpcProxyFactory(kademliaNodeServer, remoteNode);
        proxyFactory.setTimeout(rpcTimeoutMs); // 使用配置的超时时间
        BlockChainService remoteService = proxyFactory.createProxy(BlockChainService.class);
        // 假设远程服务支持批量获取区块头的方法
        return remoteService.getBlockHeaders(startHeight, count);
    }


    /**
     * 终止同步任务及所有分片
     * @param task 主任务
     * @param progress 触发终止的分片任务
     * @param reason 终止原因
     */
    private void terminateTask(SyncTaskRecord task, SyncProgress progress, String reason) {
        // 1. 更新主任务状态
        task.setStatus(SyncStatus.FAILED);
        task.setErrorMsg(reason);
        task.setUpdateTime(LocalDateTime.now());

        // 2. 更新所有分片状态
        if (task.getSyncProgressList() != null) {
            task.getSyncProgressList().forEach(p -> {
                p.setStatus(SyncStatus.FAILED);
                p.setErrorMsg(reason);
            });
        }

         //3. 中断相关线程（通过Future追踪任务时使用）
         //此处假设使用Map保存任务Future，实际需根据任务管理方式调整
         if (taskFutureMap.containsKey(task.getTaskId())) {
             taskFutureMap.get(task.getTaskId()).cancel(true);
         }

        // 4. 降级节点评分
        if (progress != null && progress.getNodeId() != null) {
            //TODO 对节点降级
            log.warn("节点[{}]因大量无效区块头被降级，任务[{}]已终止",
                    progress.getNodeId(), task.getTaskId());
        }
    }


    /**
     * 处理区块头验证结果，累计错误计数并判断是否需要终止任务
     */
    private boolean handleHeaderValidationResult(SyncProgress progress, boolean isValid) {
        // 更新有效/无效计数
        if (isValid) {
            progress.setContinuousInvalidHeaderCount(0); // 重置连续错误计数
            progress.setTotalValidHeaderCount(progress.getTotalValidHeaderCount() + 1);
        } else {
            progress.setContinuousInvalidHeaderCount(progress.getContinuousInvalidHeaderCount() + 1);
            progress.setTotalInvalidHeaderCount(progress.getTotalInvalidHeaderCount() + 1);
        }

        // 计算总处理数量和错误率
        int totalProcessed = progress.getTotalValidHeaderCount() + progress.getTotalInvalidHeaderCount();
        double invalidRate = totalProcessed == 0 ? 0 :
                (double) progress.getTotalInvalidHeaderCount() / totalProcessed;

        // 检查是否触发终止条件
        boolean shouldTerminate =
                progress.getContinuousInvalidHeaderCount() >= MAX_CONTINUOUS_INVALID_HEADER ||
                        invalidRate >= INVALID_HEADER_RATE_THRESHOLD;

        if (shouldTerminate) {
            // 记录错误信息
            String errorMsg = String.format(
                    "大量无效区块头: 连续错误=%d, 错误率=%.2f",
                    progress.getContinuousInvalidHeaderCount(),
                    invalidRate
            );
            progress.setErrorMsg(errorMsg);
        }
        return shouldTerminate;
    }


    /**
     * 通过区块高度获取负责同步该区块的节点ID
     * （基于分片任务时记录的映射关系）
     */
    private BigInteger getNodeIdByHeight(long height) {
        return heightToNodeMap.get(height);
    }


    /**
     * 创建分片任务
     */
    public SyncTaskRecord createSyncProgresses(long startHeight, long targetHeight, List<ExternalNodeInfo> nodeInfoList) {
        if (nodeInfoList == null || nodeInfoList.isEmpty()) {
            throw new IllegalArgumentException("无外部节点可供同步");
        }
        if (startHeight < 0 || targetHeight < 0 || startHeight > targetHeight) {
            throw new IllegalArgumentException("无效的高度范围: " + startHeight + "-" + targetHeight);
        }

        String taskId = String.format("Task[%d-%d]", startHeight, targetHeight);
        SyncTaskRecord task = new SyncTaskRecord();
        task.setTaskId(taskId);
        task.setStartHeight(startHeight);
        task.setTargetHeight(targetHeight);
        task.setStatus(SyncStatus.INIT);
        task.setCreateTime(LocalDateTime.now());
        task.setUpdateTime(LocalDateTime.now());

        List<SyncProgress> progressList = new ArrayList<>();
        long totalBlocks = targetHeight - startHeight + 1;
        int totalShards = (int) Math.ceil((double) totalBlocks / BASE_BATCH_SIZE);

        for (int i = 0; i < totalShards; i++) {
            ExternalNodeInfo assignedNode = nodeInfoList.get(i % nodeInfoList.size());
            NodeInfo nodeInfo = BeanCopyUtils.copyObject(assignedNode, NodeInfo.class);

            long shardStart = startHeight + (long) i * BASE_BATCH_SIZE;
            long shardTarget = Math.min(shardStart + BASE_BATCH_SIZE - 1, targetHeight);


            SyncProgress progress = new SyncProgress();
            progress.setProgressId(String.format("%s_PROGRESS_[%d-%d]", taskId, shardStart, shardTarget));
            progress.setTaskId(taskId);
            progress.setStartHeight(shardStart);
            progress.setTargetHeight(shardTarget);
            progress.setNodeId(nodeInfo.getId());
            progress.setStatus(SyncStatus.INIT);
            progress.setCurrentHeight(shardStart - 1);
            progress.setSyncedBlocks(0);
            progress.setProgressPercent(0.0);
            progress.setContinuousInvalidHeaderCount(0);
            progress.setTotalValidHeaderCount(0);
            progress.setTotalInvalidHeaderCount(0);
            progressList.add(progress);
        }

        task.setSyncProgressList(progressList);
        return task;
    }



    /**
     * 将下载的区块合并到主链
     * TODO 批量下载
     */
    private void mergeDownloadedBlocks(long startHeight, long targetHeight) {
        log.info("开始合并区块: {} - {}", startHeight, targetHeight);
        try {
            for (long height = startHeight; height <= targetHeight; height++) {
                BlockHeader header = popStorage.getDownloadedHeader(height);
                if (header == null) {
                    log.warn("区块[{}]暂存记录不存在，跳过合并", height);
                    continue;
                }
                // 再次验证后 下载完整区块 合并到主链
                if (validateBlockHeaderPoW(header)) {
                    byte[] hash = header.computeHash();
                    //RPC下载完整区块
                    RpcProxyFactory proxyFactory = new RpcProxyFactory(kademliaNodeServer);
                    BlockChainService remoteService = proxyFactory.createProxy(BlockChainService.class);
                    Block blockByHash = remoteService.getBlockByHash(hash);
                    localBlockChainService.verifyBlock(blockByHash,false);
                    //localBlockChainService.addBlockHeader(header,height);
                    popStorage.deleteDownloadedHeader(height); // 合并后删除暂存
                    log.info("区块[{}]已合并到主链", height);
                    //休眠
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                } else {
                    log.error("区块[{}]验证失败，拒绝合并", height);
                }
            }
            log.info("区块合并完成: {} - {}", startHeight, targetHeight);
        } catch (Exception e) {
            log.error("区块合并失败", e);
            throw new RuntimeException("区块合并异常", e);
        }
    }



    /**
     * 批量下载并合并区块到主链（优化版）
     * 1. 批量读取暂存的区块头
     * 2. 批量验证区块头有效性
     * 3. 并发批量下载完整区块
     * 4. 批量验证完整区块并合并到主链
     * 5. 批量清理暂存的区块头
     */
    private void mergeDownloadedBlocksBatch(long startHeight, long targetHeight) {
        log.info("开始批量合并区块: 范围[{} - {}]", startHeight, targetHeight);
        if (startHeight > targetHeight) {
            log.warn("无效的区块范围: 起始高度[{}] > 目标高度[{}]", startHeight, targetHeight);
            return;
        }

        // 批量处理大小（可配置，平衡内存与效率）
        int BATCH_SIZE = 500;
        long totalBlocks = targetHeight - startHeight + 1;
        int totalBatches = (int) Math.ceil((double) totalBlocks / BATCH_SIZE);
        log.info("区块合并总数量: {}, 分{}批处理，每批最多{}个", totalBlocks, totalBatches, BATCH_SIZE);

        try {
            for (int batchNum = 0; batchNum < totalBatches; batchNum++) {
                // 1. 计算当前批次的高度范围（严格连续）
                long batchStart = startHeight + (long) batchNum * BATCH_SIZE;
                long batchEnd = Math.min(batchStart + BATCH_SIZE - 1, targetHeight);
                log.info("处理第{}批区块: 范围[{} - {}]", batchNum + 1, batchStart, batchEnd);

                // 2. 批量读取暂存的区块头（按高度范围）
                Map<Long, BlockHeader> heightToHeaderMap = popStorage.getDownloadedHeadersInBatch(batchStart, batchEnd);
                if (heightToHeaderMap.isEmpty()) {
                    log.info("第{}批无暂存区块头，跳过处理", batchNum + 1);
                    continue;
                }

                // 3. 检查缺失的区块头（范围内未找到的高度）
                List<Long> missingHeights = new ArrayList<>();
                for (long h = batchStart; h <= batchEnd; h++) {
                    if (!heightToHeaderMap.containsKey(h)) {
                        missingHeights.add(h);
                    }
                }
                if (!missingHeights.isEmpty()) {
                    log.warn("第{}批缺失{}个区块头: {}", batchNum + 1, missingHeights.size(), missingHeights);
                    // 缺失前置区块头时，直接跳过当前批次（确保顺序性）
                    continue;
                }

                // 4. 批量验证区块头PoW（按高度顺序验证）
                Map<Long, BlockHeader> validHeaders = new TreeMap<>(); // 使用TreeMap保证按高度升序
                for (long h = batchStart; h <= batchEnd; h++) {
                    BlockHeader header = heightToHeaderMap.get(h);
                    if (validateBlockHeaderPoW(header)) {
                        validHeaders.put(h, header);
                    } else {
                        log.error("第{}批区块[{}]的区块头PoW验证失败，整批中断（确保顺序）", batchNum + 1, h);
                        validHeaders.clear(); // 清除已验证的，中断后续处理
                        break;
                    }
                }
                log.info("第{}批有效区块头: {}个（总{}个）", batchNum + 1, validHeaders.size(), heightToHeaderMap.size());
                if (validHeaders.isEmpty()) {
                    continue; // 无有效区块头，跳过后续处理
                }

                // 5. 并发批量下载完整区块（通过哈希）
                List<CompletableFuture<Block>> blockFutures = validHeaders.entrySet().stream()
                        .map(entry -> {
                            long height = entry.getKey();
                            BlockHeader header = entry.getValue();
                            byte[] blockHash = header.computeHash();
                            return CompletableFuture.supplyAsync(() -> {
                                try {
                                    // 从提供区块头的节点下载完整区块
                                    RpcProxyFactory proxyFactory = null;
                                    NodeInfo node = kademliaNodeServer.getNodeInfo(getNodeIdByHeight(height));
                                    if (node == null) {
                                        log.warn("区块[" + height + "]对应的节点已离线");
                                        proxyFactory = new RpcProxyFactory(kademliaNodeServer);
                                    }else {
                                        proxyFactory = new RpcProxyFactory(kademliaNodeServer,node);
                                    }
                                    proxyFactory.setTimeout(rpcTimeoutMs);
                                    BlockChainService remoteService = proxyFactory.createProxy(BlockChainService.class);
                                    Block block = remoteService.getBlockByHash(blockHash);
                                    if (block == null) {
                                        throw new RuntimeException("区块[" + height + "]不存在于节点");
                                    }
                                    log.debug("区块[{}]下载完成", height);
                                    return block;
                                } catch (Exception e) {
                                    log.error("区块[{}]下载失败", height, e);
                                    return null; // 下载失败的区块标记为null
                                }
                            }, syncExecutor);
                        })
                        .toList();

                // 等待所有下载完成，收集结果
                CompletableFuture.allOf(blockFutures.toArray(new CompletableFuture[0])).join();
                List<Block> downloadedBlocks = blockFutures.stream()
                        .map(CompletableFuture::join)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
                log.info("第{}批成功下载完整区块: {}个（请求{}个）", batchNum + 1, downloadedBlocks.size(), validHeaders.size());

                // 6. 按高度升序排序下载的区块（关键：确保处理顺序）
                List<Block> sortedBlocks = downloadedBlocks.stream()
                        .sorted(Comparator.comparingLong(Block::getHeight))
                        .collect(Collectors.toList());

                // 7. 严格按顺序验证并合并完整区块到主链
                List<Long> successHeights = new ArrayList<>();
                long lastMergedHeight = batchStart - 1; // 上一个已合并的高度（初始为批次起始前一个）
                for (Block block : sortedBlocks) {
                    long currentHeight = block.getHeight();

                    // 校验当前区块是否为下一个应合并的高度（核心顺序校验）
                    if (currentHeight != lastMergedHeight + 1) {
                        log.error("区块[{}]与上一个合并区块[{}]不连续，中断当前批次合并", currentHeight, lastMergedHeight);
                        // 记录缺失的中间高度（用于后续重新下载）
                        for (long h = lastMergedHeight + 1; h < currentHeight; h++) {
                            missingHeights.add(h);
                        }
                        break; // 顺序断裂，停止后续合并
                    }

                    // 验证区块完整性并合并
                    try {
                        if (localBlockChainService.verifyBlock(block, false)) {
                            successHeights.add(currentHeight);
                            lastMergedHeight = currentHeight; // 更新上一个合并高度
                            log.debug("区块[{}]成功合并到主链", currentHeight);
                        } else {
                            log.error("区块[{}]验证失败（完整区块校验），中断后续合并", currentHeight);
                            break; // 验证失败，停止后续处理
                        }
                    } catch (Exception e) {
                        log.error("区块[{}]合并失败，中断后续合并", currentHeight, e);
                        break; // 合并异常，停止后续处理
                    }
                }

                // 8. 批量清理已成功合并的区块头
                if (!successHeights.isEmpty()) {
                    popStorage.deleteDownloadedHeadersInBatch(successHeights);
                    log.info("第{}批清理{}个已合并的区块头", batchNum + 1, successHeights.size());
                }

                // 9. 记录批次处理结果
                log.info("第{}批处理完成: 成功合并{}个，失败{}个",
                        batchNum + 1, successHeights.size(), downloadedBlocks.size() - successHeights.size());
                Thread.sleep(5000);
            }
            log.info("所有区块合并完成: 总范围[{} - {}]", startHeight, targetHeight);
        } catch (Exception e) {
            log.error("批量合并区块失败", e);
            throw new RuntimeException("区块批量合并异常", e);
        }
    }



    /**
     * 计算任务的整体进度（基于所有分片的平均进度）
     */
    private double calculateTaskTotalProgress(SyncTaskRecord task) {
        if (task.getSyncProgressList() == null || task.getSyncProgressList().isEmpty()) {
            return 0.0;
        }
        // 计算所有分片的平均进度
        double totalProgress = task.getSyncProgressList().stream()
                .mapToDouble(SyncProgress::getProgressPercent)
                .average()
                .orElse(0.0);
        return Math.min(totalProgress, 100.0); // 进度不超过100%
    }


    /**
     * 从内存中移除任务（包括活跃任务映射和Future映射）
     */
    private void removeTask(String taskId) {
        // 从活跃任务映射中移除
        SyncTaskRecord removedTask = activeTasks.remove(taskId);
        if (removedTask != null) {
            log.info("任务[{}]已从活跃任务列表中移除（状态：{}）", taskId, removedTask.getStatus());
        }
        // 从Future映射中移除
        CompletableFuture<SyncTaskRecord> removedFuture = taskFutureMap.remove(taskId);
        if (removedFuture != null) {
            log.debug("任务[{}]的Future已清理", taskId);
        }
        popStorage.deleteSyncTaskRecord(taskId);
    }


    @PreDestroy
    public void destroy() {
        scheduler.shutdown();
        detectExecutor.shutdown();
        syncExecutor.shutdown();
    }
}
