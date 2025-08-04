package com.pop.popcoinsystem.service.blockChain.asyn;

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
import com.pop.popcoinsystem.util.DifficultyUtils;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
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
    private static final int MAX_CONCURRENT_TASKS = 3;
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
            new ThreadPoolExecutor.AbortPolicy() // 任务满时拒绝，避免OOM
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
        scheduler.scheduleAtFixedRate(
                this::steadyStateSync, 0, steadySyncInterval, TimeUnit.SECONDS);
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
     */
    public CompletableFuture<SyncTaskRecord> SubmitDifference(KademliaNodeServer nodeServer,
            long localHeight, byte[] localHash, byte[] localWork,
            long remoteHeight, byte[] remoteHash, byte[] remoteWork) {

        List<ExternalNodeInfo> candidateNodes = kademliaNodeServer.getRoutingTable().getAllNodes();
        // 1. 验证差异有效性
        if (remoteHeight <= localHeight) {
            log.warn("远程高度({})不大于本地高度({})，无需同步", remoteHeight, localHeight);
            return CompletableFuture.completedFuture(null);
        }
        // 2. 检查是否已有覆盖该范围的任务
        String newTaskId = String.format("Task[%d-%d]", localHeight + 1, remoteHeight);
        boolean isDuplicate = activeTasks.values().stream()
                .anyMatch(task -> task.getStartHeight() <= localHeight + 1
                        && task.getTargetHeight() >= remoteHeight);
        if (isDuplicate) {
            log.info("已有覆盖范围的同步任务，无需创建新任务");
            return CompletableFuture.completedFuture(
                    activeTasks.values().stream()
                            .filter(task -> task.getStartHeight() <= localHeight + 1
                                    && task.getTargetHeight() >= remoteHeight)
                            .findFirst()
                            .orElse(null)
            );
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
        // 4. 创建同步任务
        SyncTaskRecord task = createSyncProgresses(localHeight + 1, remoteHeight, validNodes);
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




    private void detectAndSync() {
        log.info("高频自治");
        long localHeight = localBlockChainService.getMainLatestHeight();
        byte[] localHash = localBlockChainService.getMainLatestBlockHash();
        byte[] localWork = localBlockChainService.getMainLatestBlock().getChainWork();

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



                }
            } catch (Exception e) {
                // 记录失败，降低节点评分
                log.warn("节点{}探测失败，降低评分", node.getId());
            }
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
        log.info("低频自治");
        try {
            long localHeight = localBlockChainService.getMainLatestHeight();
            long networkMaxHeight = getNetworkMaxHeight();

            // 计算需要同步的区块范围
            long startHeight = localHeight + 1;
            long endHeight = networkMaxHeight;

            // 限制单次同步数量，防止过载
            if (endHeight - startHeight + 1 > BASE_BATCH_SIZE) {
                endHeight = startHeight + BASE_BATCH_SIZE - 1;
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
/*            syncService.submitRangeSyncTask(
                    kademliaNodeServer,
                    node,
                    startHeight,
                    endHeight
            );*/
            log.info("已提交区块同步任务，节点: {}, 范围: {} - {}",
                    node.getId(), startHeight, endHeight);

        } catch (Exception e) {
            log.error("同步最新区块失败", e);
        }
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
    private CompletableFuture<SyncTaskRecord> executeSyncTask(SyncTaskRecord task) {
        task.setStatus(SyncStatus.RUNNING);
        task.setUpdateTime(LocalDateTime.now());
        // 提交所有分片任务并行执行
        List<CompletableFuture<SyncProgress>> shardFutures = task.getSyncProgressList().stream()
                .map(shard -> executeShardTask(shard, task))
                .toList();
        // 等待所有分片完成后合并区块
        return CompletableFuture.allOf(shardFutures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    // 检查所有分片状态
                    boolean allCompleted = shardFutures.stream()
                            .allMatch(future -> future.join().getStatus() == SyncStatus.COMPLETED);

                    if (allCompleted) {
                        // 合并暂存区块到主链
                        mergeDownloadedBlocks(task.getStartHeight(), task.getTargetHeight());
                        task.setStatus(SyncStatus.COMPLETED);
                    } else {
                        task.setStatus(SyncStatus.PARTIALLY_COMPLETED);
                        task.setErrorMsg("部分分片任务执行失败");
                    }

                    task.setUpdateTime(LocalDateTime.now());
                    return task;
                });
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
                        Thread.sleep(1000 * retryCount); // 指数退避重试
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
                        popStorage.saveDownloadedHeader(header);
                        progress.setCurrentHeight(currentHeight);
                        progress.setSyncedBlocks(progress.getSyncedBlocks() + 1);
                        progress.setProgressPercent(calculateProgress(progress));
                        progress.setLastSyncTime(LocalDateTime.now());
                        log.debug("分片[{}]同步到高度[{}]，进度: {:.2f}%",
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
        return remoteService.getBlockHeader(currentHeight);
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
        int totalShards = (int) Math.ceil((double) totalBlocks / MAX_BATCH_SIZE);

        for (int i = 0; i < totalShards; i++) {
            ExternalNodeInfo assignedNode = nodeInfoList.get(i % nodeInfoList.size());
            NodeInfo nodeInfo = BeanCopyUtils.copyObject(assignedNode, NodeInfo.class);

            long shardStart = startHeight + (long) i * MAX_BATCH_SIZE;
            long shardTarget = Math.min(shardStart + MAX_BATCH_SIZE - 1, targetHeight);

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
                // 再次验证后合并到主链
                if (validateBlockHeaderPoW(header)) {
                    localBlockChainService.addBlockHeader(header);
                    popStorage.deleteDownloadedHeader(height); // 合并后删除暂存
                    log.debug("区块[{}]已合并到主链", height);
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




}
