package com.pop.popcoinsystem.service.blockChain.asyn;

import com.pop.popcoinsystem.data.block.Block;
import com.pop.popcoinsystem.data.block.BlockHeader;
import com.pop.popcoinsystem.network.common.ExternalNodeInfo;
import com.pop.popcoinsystem.network.common.NodeInfo;
import com.pop.popcoinsystem.network.protocol.message.HandshakeRequestMessage;
import com.pop.popcoinsystem.network.protocol.message.KademliaMessage;
import com.pop.popcoinsystem.network.protocol.message.PingKademliaMessage;
import com.pop.popcoinsystem.network.protocol.message.PongKademliaMessage;
import com.pop.popcoinsystem.network.protocol.messageData.Handshake;
import com.pop.popcoinsystem.network.rpc.RpcProxyFactory;
import com.pop.popcoinsystem.network.service.KademliaNodeServer;
import com.pop.popcoinsystem.service.blockChain.BlockChainService;
import com.pop.popcoinsystem.service.blockChain.BlockChainServiceImpl;
import com.pop.popcoinsystem.service.blockChain.asyn.SyncProgress;
import com.pop.popcoinsystem.service.blockChain.asyn.SyncStatus;
import com.pop.popcoinsystem.service.blockChain.asyn.SyncTaskRecord;
import com.pop.popcoinsystem.storage.StorageService;
import com.pop.popcoinsystem.util.BeanCopyUtils;
import com.pop.popcoinsystem.util.CryptoUtil;
import com.pop.popcoinsystem.util.DifficultyUtils;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.math.BigInteger;
import java.net.ConnectException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;


import static com.pop.popcoinsystem.constant.BlockChainConstants.RPC_TIMEOUT;
import static com.pop.popcoinsystem.data.block.Block.validateBlockHeaderPoW;


@Data
@Slf4j
@Component
public class SynchronizedBlocksImpl implements ApplicationRunner {
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

    // 节点评分管理器：维护节点可信度评分（1-100，默认60）
    private final ConcurrentMap<BigInteger, Integer> nodeScores = new ConcurrentHashMap<>();
    private static final int DEFAULT_NODE_SCORE = 60;
    private static final int MIN_NODE_SCORE = 10;
    private static final int SCORE_PENALTY = 15; // 每次降级扣分

    // 基础批次大小（可动态调整）
    private static final int BASE_BATCH_SIZE = 500;

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






    // 1. 下载线程池（处理区块头/RPC下载，IO密集）
    private  ThreadPoolExecutor downloadExecutor;

    // 2. 处理线程池（验证+存储区块头，IO密集）
    private  ThreadPoolExecutor processExecutor;

    // 3. 合并线程池（合并区块到主链，混合密集）
    private  ThreadPoolExecutor mergeExecutor;

    // 4. 探测线程池（节点健康检测，轻量IO密集）
    private  ThreadPoolExecutor detectExecutor;

    // 5. 调度器（单线程，负责定时任务）
    private ScheduledExecutorService scheduler;



    // 同步中标记（避免并发冲突）
    private volatile boolean isSyncing = false;


    @Override
    public void run(ApplicationArguments args) throws Exception {
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

        initThreadPool();

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "block-sync-scheduler");
            thread.setDaemon(true); // 守护线程，随应用退出
            return thread;
        });


        // 启动线程池监控（每30秒输出状态）
        scheduler.scheduleAtFixedRate(this::logThreadPoolStatus, 0, 30, TimeUnit.SECONDS);


        // 启动线程池动态调整（每60秒检查一次）
        scheduler.scheduleAtFixedRate(this::adjustThreadPools, 60, 60, TimeUnit.SECONDS);


        // 启动快速同步任务
        scheduler.scheduleAtFixedRate(
                this::detectAndSync, 0, fastSyncInterval, TimeUnit.SECONDS);

        // 添加任务队列监控，当队列积压过多时发出警告
        scheduler.scheduleAtFixedRate(this::monitorTaskQueues, 10, 10, TimeUnit.SECONDS);
    }

    // 监控任务队列，当队列积压过多时发出警告
    private void monitorTaskQueues() {
        checkQueueThreshold("下载线程池", downloadExecutor, 0.8);
        checkQueueThreshold("处理线程池", processExecutor, 0.8);
        checkQueueThreshold("合并线程池", mergeExecutor, 0.7);
        checkQueueThreshold("探测线程池", detectExecutor, 0.8);
    }

    // 检查队列是否达到阈值
    private void checkQueueThreshold(String name, ThreadPoolExecutor executor, double threshold) {
        BlockingQueue<?> queue = executor.getQueue();
        int size = queue.size();
        int capacity = queue.remainingCapacity() + size;
        double usage = (double) size / capacity;

        if (usage >= threshold) {
            log.warn("{}队列使用率过高: {}/{} ({:.0f}%)，可能导致阻塞",
                    name, size, capacity, usage * 100);
        }
    }

    // 动态调整线程池参数
    private void adjustThreadPools() {
        try {
            double cpuUsage = getSystemCpuUsage(); // 获取系统CPU使用率

            // 根据CPU使用率调整下载线程池
            adjustThreadPool("下载线程池", downloadExecutor, cpuUsage, 0.5, 0.2);
            // 根据CPU使用率调整处理线程池
            adjustThreadPool("处理线程池", processExecutor, cpuUsage,0.4, 0.2);
            // 合并线程池对CPU敏感，调整更保守
            adjustThreadPool("合并线程池", mergeExecutor, cpuUsage, 0.6, 0.3);
        } catch (Exception e) {
            log.error("动态调整线程池失败", e);
        }
    }


    // 调整单个线程池（带自定义阈值）
    private void adjustThreadPool(String name, ThreadPoolExecutor executor, double cpuUsage,
                                  double highThreshold, double lowThreshold) {
        int currentCore = executor.getCorePoolSize();
        int max = executor.getMaximumPoolSize();
        int minCore = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);

        if (cpuUsage < lowThreshold && currentCore < max) {
            // CPU使用率低，增加核心线程数
            int newCore = Math.min(currentCore + 1, max);
            executor.setCorePoolSize(newCore);
            log.info("{}核心线程数从{}调整为{} (CPU使用率: {}%)",
                    name, currentCore, newCore, cpuUsage * 100);
        } else if (cpuUsage > highThreshold && currentCore > minCore) {
            // CPU使用率高，减少核心线程数
            int newCore = Math.max(currentCore - 1, minCore);
            executor.setCorePoolSize(newCore);
            log.info("{}核心线程数从{}调整为{} (CPU使用率: {}%)",
                    name, currentCore, newCore, cpuUsage * 100);
        }
    }

    // 获取系统CPU使用率（可使用OSHI等库）
    private double getSystemCpuUsage() {
        // 可以使用更准确的方法
        return ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
    }

    //线程池状态监控日志
    private void logThreadPoolStatus() {
        log.info("=== 线程池状态 ===");
        logThreadPoolInfo("下载线程池", downloadExecutor);
        logThreadPoolInfo("处理线程池", processExecutor);
        logThreadPoolInfo("合并线程池", mergeExecutor);
        logThreadPoolInfo("探测线程池", detectExecutor);
    }

    // 打印单个线程池信息
    private void logThreadPoolInfo(String name, ThreadPoolExecutor executor) {
        log.info("{}: 活跃={}, 核心={}, 最大={}, 队列={}/{}, 完成={}, 拒绝={}",
                name,
                executor.getActiveCount(),
                executor.getCorePoolSize(),
                executor.getMaximumPoolSize(),
                executor.getQueue().size(),
                executor.getQueue().remainingCapacity(),
                executor.getCompletedTaskCount(),
                executor.getRejectedExecutionHandler());
    }


    // 初始化线程池并优化配置
    private void initThreadPool() {
        int cpuCount = Runtime.getRuntime().availableProcessors();
        // 下载线程池（处理区块头/RPC下载，IO密集） 下载后按高度排序再提交给处理线程池
        downloadExecutor = new ThreadPoolExecutor(
                Math.max(4, cpuCount * 2),  // 核心线程数（CPU*2，至少4个）
                Math.max(8, cpuCount * 4),  // 最大线程数（CPU*4，至少8个）
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(500),  // 有界队列，避免OOM
                r -> new Thread(r, "block-downloader"),
                new ThreadPoolExecutor.DiscardOldestPolicy()  // 下载任务优先处理最新的
        );

        // 处理线程池（验证+存储区块头，IO密集） 单线程 + FIFO 队列，严格按高度顺序处理
        processExecutor = new ThreadPoolExecutor(
                1,  // 核心线程数=1（保证顺序）
                1,  // 最大线程数=1（禁止并行）
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(1000),  // 队列缓冲待处理任务
                r -> new Thread(r, "block-processor"),
                new ThreadPoolExecutor.CallerRunsPolicy()  // 队列满时阻塞提交者，避免乱序
        );

        // 合并线程池（合并区块到主链，混合密集） 单线程 + FIFO 队列，按高度升序合并
        mergeExecutor = new ThreadPoolExecutor(
                1,  // 单线程
                1,  // 禁止扩容
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(100),  // 缓冲待合并批次
                r -> new Thread(r, "block-merger"),
                new ThreadPoolExecutor.AbortPolicy()  // 队列满时拒绝，避免溢出
        );

        // 探测线程池（节点健康检测，轻量IO密集）
        detectExecutor = new ThreadPoolExecutor(
                detectConcurrency,
                detectConcurrency,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(100),
                r -> new Thread(r, "node-detector"),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );

        // 设置线程池饱和时的钩子函数，用于告警
        ((ThreadPoolExecutor) downloadExecutor).setRejectedExecutionHandler((r, executor) -> {
            log.warn("下载线程池饱和，已拒绝任务，当前队列大小: {}", executor.getQueue().size());
            new ThreadPoolExecutor.DiscardOldestPolicy().rejectedExecution(r, executor);
        });

    }




















    public void compareAndSync(NodeInfo remoteNode,
                                long localHeight, byte[] localHash, byte[] localTotalWork,
                                long remoteHeight, byte[] remoteHash, byte[] remoteTotalWork)
            throws ConnectException {
        // 情况0：节点正在同步中，拒绝新请求
        if (isSyncing) {
            List<SyncTaskRecord> runningTasks = activeTasks.values().stream()
                    .filter(task -> task.getStatus() == SyncStatus.RUNNING)
                    .toList();

            log.info("节点正在同步中，不处理新请求");
        }

        // 情况1：本地节点未初始化（无区块数据）仅仅一个创世区块
        if (localHeight == 0) {
            log.info("本地节点未初始化（高度: {}），请求远程完整链（远程最新高度: {}，远程总工作量: {}）",
                    localHeight, remoteHeight, remoteTotalWork);
            startSyncFromRemote(remoteNode, 0);  // 从初始状态同步
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
        log.info("获取本地与远程的分叉点（本地高度: {}，远程高度: {}，分叉点: {}）",
                localHeight, remoteHeight, forkHeight);
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
     * 进一步优化：批量查询哈希（适用于支持批量接口的场景）
     * 减少网络交互次数，尤其在跨节点通信时提升性能
     */
    private long findForkHeightWithBatchQuery(NodeInfo remoteNode, long maxCommonHeight) {
        RpcProxyFactory proxyFactory = new RpcProxyFactory(kademliaNodeServer, remoteNode);
        BlockChainService remoteService = proxyFactory.createProxy(BlockChainService.class);
        log.info("开始批量查询哈希，远程节点:{}，最大共同高度:{}", remoteNode, maxCommonHeight);

        if (maxCommonHeight < 0) {
            return -1;
        }

        long low = 0;
        long high = maxCommonHeight;
        long forkHeight = -1;

        while (low <= high) {
            long mid = low + (high - low) / 2;

            try {
                List<Long> heightsToCheck = Arrays.asList(mid, low, high);
                Map<Long, byte[]> localHashes = localBlockChainService.getBlockHashes(heightsToCheck);
                log.info("本地址批量查询哈希，高度范围:{}", heightsToCheck);
                Map<Long, byte[]> remoteHashes = remoteService.getBlockHashes(heightsToCheck);
                log.info("远程地址批量查询哈希，高度范围:{}", heightsToCheck);

                // 获取本地和远程节点的哈希，并判断是否存在
                byte[] localHash = localHashes.get(mid);
                byte[] remoteHash = remoteHashes.get(mid);

                // 处理本地或远程没有该高度的情况
                if (localHash == null || remoteHash == null) {
                    // 本地或远程不存在该高度，视为不匹配，缩小上界
                    high = mid - 1;
                } else if (Arrays.equals(localHash, remoteHash)) {
                    // 两者都存在且哈希匹配，尝试更高高度
                    forkHeight = mid;
                    low = mid + 1;
                } else {
                    // 哈希不匹配，缩小上界
                    high = mid - 1;
                }
            } catch (Exception e) {
                log.error("批量查询哈希失败，中间高度:{}", mid, e);
                high = mid - 1;  // 出错时缩小范围，避免死循环
            }
        }

        // 确保创世区块（高度0）作为最小分叉点
        return forkHeight == -1 && maxCommonHeight >= 0 ? 0 : forkHeight;
    }


    /**
     * 从远程节点同步区块（核心同步入口）
     * @param remoteNode 远程节点信息
     * @param startHeight 同步起始高度（分叉点或本地当前高度）
     */
    private void startSyncFromRemote(NodeInfo remoteNode, long startHeight) {
        if (remoteNode == null) {
            log.error("远程节点信息为空，无法启动同步");
            return;
        }

        // 标记同步开始
        if (isSyncing) {
            log.warn("已有同步任务在执行中，当前任务将排队等待");
            return;
        }
        isSyncing = true;
        log.info("开始从远程节点[{}]同步区块，起始高度: {}", remoteNode.getId().toString().substring(0, 8), startHeight);

        // 异步执行同步流程（避免阻塞调用线程）
        CompletableFuture.runAsync(() -> {
            RpcProxyFactory proxyFactory = null;
            BlockChainService remoteService = null;
            try {
                // 1. 验证远程节点有效性（评分、在线状态）
                BigInteger nodeId = remoteNode.getId();


                // 2. 获取远程节点最新高度（确定同步目标）
                proxyFactory = new RpcProxyFactory(kademliaNodeServer, remoteNode);
                proxyFactory.setTimeout(rpcTimeoutMs);
                remoteService = proxyFactory.createProxy(BlockChainService.class);
                long remoteLatestHeight = remoteService.getMainLatestHeight();
                log.info("远程节点[{}]最新高度: {}，同步范围: [{} - {}]",
                        nodeId.toString().substring(0, 8), remoteLatestHeight, startHeight, remoteLatestHeight);

                // 3. 检查同步范围有效性（起始高度必须小于目标高度）
                if (startHeight >= remoteLatestHeight) {
                    log.info("无需同步：起始高度[{}] >= 远程最新高度[{}]", startHeight, remoteLatestHeight);
                    return;
                }

                // 4. 提交同步任务（自动处理任务重叠、分片及并发控制）
                CompletableFuture<SyncTaskRecord> syncFuture = SubmitSyncTask(startHeight, remoteLatestHeight);
                syncFuture.whenComplete((task, ex) -> {
                    if (ex != null) {
                        log.error("同步任务执行异常", ex);
                        degradeNodeScore(nodeId, "同步任务执行失败: " + ex.getMessage());
                    } else if (task != null) {
                        log.info("同步任务[{}]完成，状态: {}", task.getTaskId(), task.getStatus());
                        // 任务成功完成后，更新本地链的最新高度缓存
                        localBlockChainService.refreshLatestHeight();
                    }
                }).join(); // 等待任务完成

            } catch (Exception e) {
                log.error("从远程节点[{}]同步失败", remoteNode.getId().toString().substring(0, 8), e);
                // 同步失败时降级节点评分
                if (remoteNode.getId() != null) {
                    degradeNodeScore(remoteNode.getId(), "同步流程异常: " + e.getMessage());
                }
            } finally {
                // 无论成功失败，标记同步结束
                isSyncing = false;
                log.info("从远程节点[{}]的同步流程结束", remoteNode.getId().toString().substring(0, 8));
            }
        }, detectExecutor);
    }



    /**
     * 提交差异
     * 节点握手确定差异
     * 定期检查差异并同步
     *    同步时合并差异
     *    根据合并后的差异建立同步任务  并行执行
     *    将下载的区块暂存到 下载数据库
     *    检查全部下载完就开始从开始位置慢慢补充到主链
     *    要检查开始高度 和开始高度hash
     *    如果不重叠且间隙 hash一致 也就是 [100 hasha1 - 200 hash2] [101hash3-200hash4]
     *    hash3的父hash是hash2 就允许创建一个新的任务  也就是两个任务 必须高度连续 区块连续
     *    如果一个任务A是[100 hasha1 - 200 hash2] 任务B是[301 hash3 - 400 hash4] 此时如果A正在同步 则要拒绝B任务 因为不连续 无法合到主链
     */
    public CompletableFuture<SyncTaskRecord> SubmitSyncTask(long startHeight, long endHeight) {
        List<ExternalNodeInfo> candidateNodes = kademliaNodeServer.getRoutingTable().findClosest();
        log.info("提交差异：开始高度={}, 结束高度={}", startHeight, endHeight);
        // 检查并处理与已有任务的重叠
        List<SyncTaskRecord> overlappingTasks = findOverlappingTasks(startHeight, endHeight);
        // 计算需要修剪的起始高度（取所有重叠任务的最大结束高度 + 1）
        long adjustedStart = startHeight;
        if (!overlappingTasks.isEmpty()) {
            long maxEndHeight = overlappingTasks.stream()
                    .mapToLong(SyncTaskRecord::getTargetHeight)
                    .max()
                    .orElse(startHeight - 1);

            adjustedStart = maxEndHeight + 1;
            log.info("检测到与{}个任务重叠，调整新任务起始高度从{}到{}",
                    overlappingTasks.size(), startHeight, adjustedStart);
        }
        // 如果调整后起始高度超过结束高度，说明无需创建新任务
        if (adjustedStart > endHeight) {
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
        SyncTaskRecord task = createSyncProgresses(adjustedStart, endHeight, validNodes);
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
        log.info("检测并同步数据....");
        CompletableFuture.runAsync(this::getNetworkMaxHeight, detectExecutor)
                .exceptionally(ex -> {
                    log.error("节点探测异常", ex);
                    return null;
                });
    }

    // 获取网络最高高度（遍历健康节点，取最高高度）
    private long getNetworkMaxHeight() {
        try {
            NodeInfo local = kademliaNodeServer.getNodeInfo();
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
                        log.info("更新网络最高高度为: {} (来自节点 {})", maxHeight, node.getId());
                        PingKademliaMessage pingKademliaMessage = new PingKademliaMessage();
                        pingKademliaMessage.setSender(local);//本节点信息
                        pingKademliaMessage.setReceiver(node);
                        pingKademliaMessage.setReqResId();
                        pingKademliaMessage.setResponse(false);
                        try {
                            KademliaMessage kademliaMessage = kademliaNodeServer.getUdpClient().sendMessageWithResponse(pingKademliaMessage);
                            if (kademliaMessage == null){
                                log.error("未收到节点{}的Pong消息,无法同步", node);
                            }
                            if (kademliaMessage != null){
                                log.info("收到节点{}的Pong消息", node);
                                //向节点发送握手请求 收到握手回复后检查 自己的区块链信息
                                Block mainLatestBlock = localBlockChainService.getMainLatestBlock();
                                Handshake handshake = new Handshake();
                                handshake.setExternalNodeInfo(kademliaNodeServer.getExternalNodeInfo());//携带我的节点信息
                                handshake.setGenesisBlockHash(kademliaNodeServer.getBlockChainService().GENESIS_BLOCK_HASH());
                                handshake.setLatestBlockHash(mainLatestBlock.getHash());
                                handshake.setLatestBlockHeight(mainLatestBlock.getHeight());
                                handshake.setChainWork(mainLatestBlock.getChainWork());
                                HandshakeRequestMessage handshakeRequestMessage = new HandshakeRequestMessage(handshake);
                                handshakeRequestMessage.setSender(local);//本节点信息
                                handshakeRequestMessage.setReceiver(node);
                                kademliaNodeServer.getTcpClient().sendMessage(handshakeRequestMessage);
                            }
                        }catch (Exception e){
                            log.error("与节点{}通信失败: {}", node.getId(), e.getMessage());
                        }
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
     * 打印所有正在同步的任务（状态为RUNNING的任务）
     */
    public void printRunningSyncTasks() {
        // 筛选出状态为运行中的任务
        List<SyncTaskRecord> runningTasks = activeTasks.values().stream()
                .filter(task -> task.getStatus() == SyncStatus.RUNNING)
                .toList();
        //如果任务进度是100%


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

    // 批量获取区块头并建立高度映射（核心修改）
    private Map<Long, BlockHeader> fetchBlockHeadersWithHeightMap(BigInteger nodeId, long startHeight, int count) {
        NodeInfo remoteNode = kademliaNodeServer.getNodeInfo(nodeId);
        if (remoteNode == null) {
            throw new RuntimeException("节点不可用: " + nodeId);
        }

        RpcProxyFactory proxyFactory = new RpcProxyFactory(kademliaNodeServer, remoteNode);
        proxyFactory.setTimeout(rpcTimeoutMs);
        BlockChainService remoteService = proxyFactory.createProxy(BlockChainService.class);

        // 假设远程返回的区块头按高度升序排列（从startHeight开始）
        List<BlockHeader> headers = remoteService.getBlockHeaders(startHeight, count);
        if (headers == null || headers.size() != count) {
            throw new RuntimeException("区块头数量不匹配: 请求" + count + "个，实际" + (headers == null ? 0 : headers.size()));
        }

        // 建立高度→区块头的映射（关键：通过请求参数推算高度）
        Map<Long, BlockHeader> heightToHeaderMap = new TreeMap<>(); // TreeMap保证按高度升序
        for (int i = 0; i < headers.size(); i++) {
            long height = startHeight + i; // 第i个区块头对应高度=起始高度+i
            heightToHeaderMap.put(height, headers.get(i));
        }
        return heightToHeaderMap;
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
        }, downloadExecutor);
    }



    // 处理批量区块头（依赖高度映射）
    private boolean processBlockHeaderBatch(SyncProgress progress, Map<Long, BlockHeader> heightToHeaderMap) {
        // TreeMap已按高度升序排序，直接遍历即可保证顺序
        for (Map.Entry<Long, BlockHeader> entry : heightToHeaderMap.entrySet()) {
            long height = entry.getKey();
            BlockHeader header = entry.getValue();

            // 1. 验证区块头PoW（无需高度字段，仅验证自身哈希）
            if (!validateBlockHeaderPoW(header)) {
                log.error("高度[{}]区块头PoW验证失败，中断处理", height);
                return false;
            }

            // 2. 额外验证：检查当前区块头的前序哈希是否与上一高度区块的哈希匹配
            if (height > 0) { // 创世区块（高度0）无前置
                BlockHeader prevHeader = popStorage.getDownloadedHeader(height - 1);
                if (prevHeader == null) {
                    log.error("高度[{}]的前置区块（高度{}）未找到，中断处理", height, height - 1);
                    return false;
                }
                if (!Arrays.equals(header.getPreviousHash(), prevHeader.computeHash())) {
                    log.error("高度[{}]的前置哈希不匹配，中断处理", height);
                    return false;
                }
            }

            // 3. 与高度绑定存储（关键：后续可通过高度直接查询）
            popStorage.saveDownloadedHeader(header, height);
            progress.setSyncedBlocks(progress.getSyncedBlocks() + 1);
            log.debug("已处理高度[{}]区块头", height);
        }
        return true;
    }



    private CompletableFuture<SyncProgress> executeShardTaskBatchNew(SyncProgress progress, SyncTaskRecord mainTask) {
        return CompletableFuture.supplyAsync(() -> {
            progress.setStatus(SyncStatus.RUNNING);
            long currentHeight = progress.getStartHeight();

            try {
                while (currentHeight <= progress.getTargetHeight()) {
                    // 1. 计算当前批次范围（每次下载BASE_BATCH_SIZE个）
                    int batchSize = Math.min(BASE_BATCH_SIZE, (int)(progress.getTargetHeight() - currentHeight + 1));

                    // 2. 下载并建立高度映射（核心：获取height→header关系）
                    Map<Long, BlockHeader> heightToHeaderMap;
                    try {
                        heightToHeaderMap = fetchBlockHeadersWithHeightMap(
                                progress.getNodeId(), currentHeight, batchSize);
                    } catch (Exception e) {
                        log.error("下载区块头失败（高度范围：{}~{}）", currentHeight, currentHeight + batchSize - 1, e);
                        throw new RuntimeException("下载区块头失败", e);
                        //需要重新处理 TODO
                    }

                    // 3. 提交到单线程处理池，确保顺序执行（依赖TreeMap的有序性）
                    CompletableFuture<Boolean> processFuture = CompletableFuture.supplyAsync(
                            () -> processBlockHeaderBatch(progress, heightToHeaderMap),
                            processExecutor // 单线程池保证顺序
                    ).exceptionally(ex -> {
                        log.error("处理区块头失败", ex);
                        return false;
                    });

                    // 4. 等待当前批次处理完成，再继续下一批（保证顺序）
                    if (!processFuture.get()) {
                        progress.setStatus(SyncStatus.FAILED);
                        return progress;
                    }

                    // 5. 推进到下一批次
                    currentHeight += batchSize;
                    progress.setCurrentHeight(currentHeight - 1); // 更新已处理到的高度
                    progress.setProgressPercent(calculateProgress(progress));
                    log.info("分片[{}]进度：{}%（已处理到高度{}）",
                            progress.getProgressId(), progress.getProgressPercent(), currentHeight - 1);
                }

                progress.setStatus(SyncStatus.COMPLETED);
                return progress;
            } catch (Exception e) {
                progress.setStatus(SyncStatus.FAILED);
                progress.setErrorMsg("分片执行异常: " + e.getMessage());
                return progress;
            }
        }, downloadExecutor);
    }



    private void mergeDownloadedBlocksBatchNew(long startHeight, long targetHeight) {
        mergeExecutor.submit(() -> { // 单线程合并，保证顺序
            for (long height = startHeight; height <= targetHeight; height++) {
                // 1. 通过高度直接查询区块头（依赖存储时的高度映射）
                BlockHeader header = popStorage.getDownloadedHeader(height);
                if (header == null) {
                    log.warn("高度[{}]区块头未找到，等待下载后重试", height);
                    try {
                        Thread.sleep(2000); // 等待下载补全
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    height--; // 重试当前高度
                    continue;
                }

                // 2. 下载完整区块（通过区块头哈希）
                Block block = downloadFullBlockByHeader(header, height);
                if (block == null) {
                    log.error("高度[{}]完整区块下载失败，中断合并", height);
                    return;
                }

                // 3. 验证并合并到主链
                if (!localBlockChainService.verifyBlock(block, false)) {
                    log.error("高度[{}]区块验证失败，中断合并", height);
                    return;
                }

                // 4. 清理暂存的区块头
                popStorage.deleteDownloadedHeader(height);
                log.info("已合并高度[{}]区块到主链", height);
            }
            log.info("区块合并完成：{}~{}", startHeight, targetHeight);
        });
    }

    // 辅助方法：通过区块头下载完整区块（并验证高度一致性）
    private Block downloadFullBlockByHeader(BlockHeader header, long expectedHeight) {
        try {
            byte[] blockHash = header.computeHash();
            RpcProxyFactory proxyFactory = new RpcProxyFactory(kademliaNodeServer);
            BlockChainService remoteService = proxyFactory.createProxy(BlockChainService.class);
            Block block = remoteService.getBlockByHash(blockHash);

            // 额外验证：确保下载的区块高度与预期一致（防止节点返回错误区块）
            if (block.getHeight() != expectedHeight) {
                log.error("区块哈希对应的高度不符：预期{}，实际{}", expectedHeight, block.getHeight());
                return null;
            }
            return block;
        } catch (Exception e) {
            log.error("下载完整区块失败（预期高度{}）", expectedHeight, e);
            return null;
        }
    }




    /**
     * 处理批量获取的区块头（使用高度索引映射）
     */
    private boolean processBlockHeaderBatch(SyncProgress progress, long batchStart, long batchEnd,
                                            Map<Long, BlockHeader> heightToHeaderMap) {

        // 1. 按高度升序排序当前批次的区块头（关键：保证输入顺序）
        List<Map.Entry<Long, BlockHeader>> sortedEntries = new ArrayList<>(heightToHeaderMap.entrySet());
        sortedEntries.sort(Comparator.comparingLong(Map.Entry::getKey));

        // 2. 创建一个顺序执行的任务（整个批次作为一个任务提交）
/*        CompletableFuture<Boolean> batchFuture = CompletableFuture.supplyAsync(() -> {
            int validCount = 0;
            int invalidCount = 0;
            // 3. 批次内严格按高度顺序验证
            for (Map.Entry<Long, BlockHeader> entry : sortedEntries) {
                long height = entry.getKey();
                BlockHeader header = entry.getValue();

                boolean isValid = validateBlockHeaderPoW(header);
                if (isValid) {
                    popStorage.saveDownloadedHeader(header, height); // 顺序存储
                    validCount++;
                } else {
                    invalidCount++;
                    // 发现无效区块，中断整个批次处理（保证后续区块依赖正确）
                    if (invalidCount >= MAX_CONTINUOUS_INVALID_HEADER) {
                        return false;
                    }
                }
            }
            return true;
        }, processExecutor); // 提交到单线程处理池，保证顺序

        // 4. 等待批次处理完成（同步等待，确保当前批次处理完再提交下一批）
        try {
            return batchFuture.get(); // 阻塞等待当前批次完成
        } catch (Exception e) {
            log.error("批次验证失败", e);
            return false;
        }*/


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
        }, detectExecutor);
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
                    boolean b = validateBlockHeaderPoW(header);
                    log.info("第{}批区块[{}]的区块头PoW验证结果: {}", batchNum + 1, h, b);
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
                                    RpcProxyFactory proxyFactory = new RpcProxyFactory(kademliaNodeServer);
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
                            }, downloadExecutor);
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


}
