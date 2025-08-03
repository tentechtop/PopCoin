package com.pop.popcoinsystem.service.blockChain.asyn;

import com.pop.popcoinsystem.data.block.Block;
import com.pop.popcoinsystem.network.common.NodeInfo;
import com.pop.popcoinsystem.network.rpc.RpcProxyFactory;
import com.pop.popcoinsystem.network.service.KademliaNodeServer;
import com.pop.popcoinsystem.service.blockChain.BlockChainService;
import com.pop.popcoinsystem.util.CryptoUtil;
import com.pop.popcoinsystem.util.DifficultyUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.net.ConnectException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.pop.popcoinsystem.constant.BlockChainConstants.GENESIS_BLOCK_HASH_HEX;

@Slf4j
@Component
public class AsyncBlockSynchronizerImpl implements AsyncBlockSynchronizer{
    // 基础批次大小（可动态调整）
    public static final int BATCH_SIZE = 100;
    // 基础批次大小（可动态调整）
    private static final int BASE_BATCH_SIZE = 100;
    // 最大批次大小（避免单次加载过多）
    private static final int MAX_BATCH_SIZE = 500;
    // 最小批次大小（内存紧张时）
    private static final int MIN_BATCH_SIZE = 10;
    // 网络请求超时时间（毫秒）
    public static final int RPC_TIMEOUT = 10000;

    // 最大并发同步任务数（避免资源耗尽）
    private static final int MAX_CONCURRENT_TASKS = 3;
    // 并发任务计数器
    private final AtomicInteger activeTaskCount = new AtomicInteger(0);




    // 新增：最大重试次数
    public static final int MAX_RETRY = 3;
    // 新增：记录同步进度（key：远程节点ID，value：最近同步到的区块哈希）
    public final ConcurrentHashMap<BigInteger, String> syncProgress = new ConcurrentHashMap<>();


    // 线程池用于执行异步任务
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

    // 同步进度跟踪（线程安全）
    private final ConcurrentHashMap<String, SyncProgress> globalSyncProgress = new ConcurrentHashMap<>();
    // 节点ID与任务ID映射（用于断点续传）
    private final ConcurrentHashMap<BigInteger, String> nodeTaskMap = new ConcurrentHashMap<>();


    private final KademliaNodeServer kademliaNodeServer;

    public AsyncBlockSynchronizerImpl(KademliaNodeServer kademliaNodeServer) {
        this.kademliaNodeServer = kademliaNodeServer;
    }





    /**
     * 提交同步任务（带并发控制）
     */
    public CompletableFuture<SyncProgress> submitSyncTask(
            KademliaNodeServer nodeServer, NodeInfo remoteNode,
            long localHeight, byte[] localHash, byte[] localWork,
            long remoteHeight, byte[] remoteHash, byte[] remoteWork) {

        // 检查并发任务数
        if (activeTaskCount.get() >= MAX_CONCURRENT_TASKS) {
            SyncProgress progress = new SyncProgress();
            progress.setStatus(SyncProgress.SyncStatus.FAILED);
            progress.setErrorMsg("超出最大并发同步任务数（" + MAX_CONCURRENT_TASKS + "）");
            return CompletableFuture.completedFuture(progress);
        }

        // 生成任务ID
        String taskId = UUID.randomUUID().toString();
        nodeTaskMap.put(remoteNode.getId(), taskId);

        // 初始化进度
        SyncProgress progress = initSyncProgress(taskId, remoteNode, localHash, remoteHash, localHeight, remoteHeight);
        globalSyncProgress.put(taskId, progress);

        // 提交任务
        CompletableFuture<SyncProgress> future = CompletableFuture.supplyAsync(() -> {
            activeTaskCount.incrementAndGet();
            try {
                progress.setStatus(SyncProgress.SyncStatus.RUNNING);
                performCompareAndSync(nodeServer, remoteNode, progress,
                        localHeight, localHash, localWork,
                        remoteHeight, remoteHash, remoteWork);
                progress.setStatus(SyncProgress.SyncStatus.COMPLETED);
                return progress;
            } catch (Exception e) {
                log.error("同步任务失败", e);
                progress.setStatus(SyncProgress.SyncStatus.FAILED);
                progress.setErrorMsg(e.getMessage());
                return progress;
            } finally {
                activeTaskCount.decrementAndGet();
                progress.setLastSyncTime(LocalDateTime.now());
            }
        }, syncExecutor);

        return future;
    }
    /**
     * 初始化同步进度
     */
    private SyncProgress initSyncProgress(String taskId, NodeInfo node,
                                          byte[] startHash, byte[] endHash,
                                          long startHeight, long targetHeight) {
        SyncProgress progress = new SyncProgress();
        progress.setTaskId(taskId);
        progress.setNodeId(node.getId());
        progress.setNodeInfo(node);
        progress.setStartHash(CryptoUtil.bytesToHex(startHash));
        progress.setEndHash(CryptoUtil.bytesToHex(endHash));
        progress.setStartHeight(startHeight);
        progress.setTargetHeight(targetHeight);
        progress.setTotalBlocks(targetHeight - startHeight);
        progress.setCurrentHeight(startHeight);
        progress.setSyncedBlocks(0);
        progress.setProgressPercent(0.0);
        progress.setStatus(SyncProgress.SyncStatus.INIT);
        progress.setLastSyncTime(LocalDateTime.now());
        return progress;
    }


    /**
     * 异步比较并同步区块，不阻塞主线程
     *
     * @return CompletableFuture 可用于追踪异步操作结果
     */
    public CompletableFuture<Void> compareAndSyncAsync(
            KademliaNodeServer nodeServer, NodeInfo remoteNode,
            long localHeight, byte[] localHash, byte[] localWork,
            long remoteHeight, byte[] remoteHash, byte[] remoteWork) {

        // 将同步操作提交到线程池异步执行
        return CompletableFuture.runAsync(() -> {
            try {
                // 实际的比较和同步逻辑
                performCompareAndSync(nodeServer, remoteNode,
                        localHeight, localHash, localWork,
                        remoteHeight, remoteHash, remoteWork);
            } catch (ConnectException e) {
                log.error("连接远程节点失败: {}", e.getMessage());
            } catch (InterruptedException e) {
                log.error("同步操作被中断: {}", e.getMessage());
                Thread.currentThread().interrupt(); // 恢复中断状态
            } catch (Exception e) {
                log.error("区块同步发生异常", e);
            }
        }, syncExecutor);
    }

    /**
     * 实际执行比较和同步的逻辑
     */
    private void performCompareAndSync(
            KademliaNodeServer nodeServer, NodeInfo remoteNode,
            long localHeight, byte[] localHash, byte[] localWork,
            long remoteHeight, byte[] remoteHash, byte[] remoteWork)
            throws ConnectException, InterruptedException {

        log.info("比较本地与远程节点的区块差异");

        // 情况1：远程链工作量更大
        if (DifficultyUtils.compare(localWork, remoteWork) == -1) {
            log.info("远程链链工作量更大（本地:{}，远程:{}），准备同步",
                    CryptoUtil.bytesToHex(localWork), CryptoUtil.bytesToHex(remoteWork));

            if (localHeight < remoteHeight) {
                // 远程链更长且工作量更大 - 从本地最新区块开始同步后续区块
                log.info("远程链更长且工作量更大，请求区块同步");
                sendHeadersRequest(nodeServer, remoteNode, localHash, remoteHash);
            } else if (localHeight == remoteHeight) {
                // 高度相同但工作量不同（分叉）- 从创世区块找分叉点
                log.warn("区块链分叉，远程链工作量更大，查找分叉点");
                sendForkPointRequest(nodeServer, remoteNode);
            } else {
                // 本地链更高但工作量更小（存在无效区块）
                log.warn("本地链高度更高但工作量更小，可能包含无效区块，请求完整链同步");
                sendHeadersRequest(nodeServer, remoteNode,
                        CryptoUtil.hexToBytes(GENESIS_BLOCK_HASH_HEX), remoteHash);
            }
        }
        // 情况2：本地链工作量更大
        else if (DifficultyUtils.compare(localWork, remoteWork) == 1) {
            log.info("本地链工作量更大（本地:{}，远程:{}），无需主动同步",
                    CryptoUtil.bytesToHex(localWork), CryptoUtil.bytesToHex(remoteWork));
            // 远程节点会在自己的握手处理中发现差异并请求同步
        }
        // 情况3：工作量相同
        else {
            if (localHeight < remoteHeight) {
                // 工作量相同但远程更长 - 同步新区块
                log.info("本地链落后（本地高度:{}，远程高度:{}），开始同步",
                        localHeight, remoteHeight);
                sendHeadersRequest(nodeServer, remoteNode, localHash, remoteHash);
            } else if (localHeight == remoteHeight && !Arrays.equals(localHash, remoteHash)) {
                // 工作量相同、高度相同但哈希不同（临时分叉）
                log.warn("区块链临时分叉，工作量相同，等待更多区块确认");
            } else {
                // 链状态完全一致
                log.info("链状态完全一致，无需同步");
            }
        }
    }


    /**
     * 核心同步逻辑（优化内存管理）
     */
    private void performCompareAndSync(
            KademliaNodeServer nodeServer, NodeInfo remoteNode,
            SyncProgress progress,
            long localHeight, byte[] localHash, byte[] localWork,
            long remoteHeight, byte[] remoteHash, byte[] remoteWork)
            throws ConnectException, InterruptedException {

        log.info("开始同步任务[{}]，远程节点: {}", progress.getTaskId(), remoteNode);

        // 远程链更优（工作量更大）
        if (DifficultyUtils.compare(localWork, remoteWork) <= 0) {
            BlockChainService localChainService = nodeServer.getBlockChainService();
            byte[] effectiveStartHash = localHash;
            long effectiveStartHeight = localHeight;

            // 全新节点（仅创世区块）特殊处理
            if (localHeight == 0 && Arrays.equals(localHash, CryptoUtil.hexToBytes(GENESIS_BLOCK_HASH_HEX))) {
                log.info("检测到全新节点，从创世区块开始全量同步");
                effectiveStartHash = CryptoUtil.hexToBytes(GENESIS_BLOCK_HASH_HEX);
                effectiveStartHeight = 0;
                progress.setStartHash(GENESIS_BLOCK_HASH_HEX);
                progress.setStartHeight(0);
                progress.setTotalBlocks(remoteHeight); // 预估总区块数
            }

            // 执行同步
            sendHeadersRequest(nodeServer, remoteNode, progress, effectiveStartHash, remoteHash);
        }
    }

    /**
     * 分批次同步区块（核心优化）
     */
    private void sendHeadersRequest(KademliaNodeServer nodeServer, NodeInfo remoteNode,
                                    SyncProgress progress, byte[] startHash, byte[] endHash)
            throws ConnectException, InterruptedException {

        BlockChainService localChainService = nodeServer.getBlockChainService();
        Block currentStartBlock = localChainService.getBlockByHash(startHash);
        if (currentStartBlock == null) {
            throw new IllegalArgumentException("起始区块不存在: " + CryptoUtil.bytesToHex(startHash));
        }

        long currentHeight = currentStartBlock.getHeight();
        long targetHeight = progress.getTargetHeight();
        log.info("同步任务[{}]：从高度{}到{}，共{}个区块",
                progress.getTaskId(), currentHeight, targetHeight, targetHeight - currentHeight);

        while (currentHeight < targetHeight) {
            // 动态调整批次大小（根据内存使用情况）
            int batchSize = calculateDynamicBatchSize();
            log.debug("同步任务[{}]：当前批次大小={}", progress.getTaskId(), batchSize);

            // 计算本次同步的结束高度（不超过目标）
            long batchEndHeight = Math.min(currentHeight + batchSize, targetHeight);
            byte[] batchEndHash = localChainService.getMainBlockHashByHeight(batchEndHeight);
            if (batchEndHash == null) batchEndHash = endHash;

            // 拉取批次区块
            List<Block> remoteBlocks = fetchBlockBatch(remoteNode, startHash, batchEndHash, batchSize);
            if (remoteBlocks == null || remoteBlocks.isEmpty()) {
                log.warn("同步任务[{}]：未获取到区块，终止同步", progress.getTaskId());
                break;
            }

            // 处理批次区块（逐个处理，避免内存累积）
            Block lastProcessed = null;
            for (Block block : remoteBlocks) {
                // 验证并添加区块（同步逻辑复用原有验证）
                if (!localChainService.verifyBlock(block,false)) {
                    throw new RuntimeException("区块验证失败: " + CryptoUtil.bytesToHex(block.getHash()));
                }
                lastProcessed = block;
                // 立即释放已处理区块引用（帮助GC）
                System.gc();
            }

            // 更新进度
            if (lastProcessed != null) {
                currentHeight = lastProcessed.getHeight();
                startHash = lastProcessed.getHash();
                progress.setCurrentHeight(currentHeight);
                progress.setSyncedBlocks(progress.getSyncedBlocks() + remoteBlocks.size());
                progress.setProgressPercent(calculateProgress(progress));
                progress.setLastSyncTime(LocalDateTime.now());
                log.info("同步任务[{}]：进度={}%（{} / {}）",
                        progress.getTaskId(), progress.getProgressPercent(),
                        currentHeight, targetHeight);
            }

            // 检查是否需要暂停（内存过高时）
            if (shouldPauseForMemory()) {
                log.warn("同步任务[{}]：内存使用率过高，暂停10秒", progress.getTaskId());
                Thread.sleep(10000);
            }
        }
    }



    /**
     * 动态计算批次大小（根据内存使用情况）
     */
    private int calculateDynamicBatchSize() {
        Runtime runtime = Runtime.getRuntime();
        long freeMemory = runtime.freeMemory();
        long totalMemory = runtime.totalMemory();
        double usedPercent = (1.0 - (double) freeMemory / totalMemory) * 100;

        // 内存使用率>70%：减小批次
        if (usedPercent > 70) {
            return MIN_BATCH_SIZE;
        }
        // 内存使用率<30%：增大批次
        else if (usedPercent < 30) {
            return MAX_BATCH_SIZE;
        }
        // 中间状态：默认批次
        else {
            return BASE_BATCH_SIZE;
        }
    }


    /**
     * 拉取单批区块（带重试和超时）
     */
    private List<Block> fetchBlockBatch(NodeInfo remoteNode, byte[] startHash, byte[] endHash, int batchSize) throws InterruptedException {
        log.info("拉取单批区块（带重试和超时）");
        for (int retry = 0; retry < MAX_RETRY; retry++) {
            try {
                RpcProxyFactory proxyFactory = new RpcProxyFactory(kademliaNodeServer, remoteNode);
                proxyFactory.setTimeout(RPC_TIMEOUT);
                BlockChainService remoteService = proxyFactory.createProxy(BlockChainService.class);
                return remoteService.getBlockByStartHashAndEndHashWithLimit(startHash, endHash, batchSize);
            } catch (Exception e) {
                log.warn("第{}次拉取区块失败（节点:{}），重试...", retry + 1, remoteNode, e);
                if (retry == MAX_RETRY - 1) {
                    log.error("达到最大重试次数，拉取失败", e);
                    return null;
                }
                // 指数退避重试
                Thread.sleep((long) (1000 * Math.pow(2, retry)));
            }
        }
        return null;
    }



    /**
     * 判断是否需要暂停同步（内存保护）
     */
    private boolean shouldPauseForMemory() {
        Runtime runtime = Runtime.getRuntime();
        long freeMemory = runtime.freeMemory();
        long totalMemory = runtime.totalMemory();
        double freePercent = (double) freeMemory / totalMemory * 100;
        // 可用内存<10%时暂停
        return freePercent < 10;
    }

    /**
     * 计算同步进度百分比
     */
    private double calculateProgress(SyncProgress progress) {
        if (progress.getTotalBlocks() <= 0) return 0.0;
        double percent = (double) progress.getSyncedBlocks() / progress.getTotalBlocks() * 100;
        return Math.round(percent * 100) / 100.0; // 保留2位小数
    }

    /**
     * 发送区块头请求（异步执行）
     */
    private void sendHeadersRequest(KademliaNodeServer nodeServer, NodeInfo remoteNode,
                                    byte[] startHash, byte[] endHash) throws ConnectException, InterruptedException {
        log.info("开始同步区块，从 {} 到 {}",
                CryptoUtil.bytesToHex(startHash), CryptoUtil.bytesToHex(endHash));
        // 检查进度：如果之前同步过，从上次进度开始
        BigInteger nodeId = remoteNode.getId();
        try {
            String lastSyncedHashHex = syncProgress.get(nodeId);
            byte[] currentStartHash = startHash;
            if (lastSyncedHashHex != null && !lastSyncedHashHex.isEmpty()) {
                currentStartHash = CryptoUtil.hexToBytes(lastSyncedHashHex);
                log.info("检测到历史同步进度，从 {} 继续", lastSyncedHashHex);
            }

            BlockChainService localChainService = nodeServer.getBlockChainService();
            Block prevBlock = localChainService.getBlockByHash(currentStartHash);
            if (prevBlock == null && !Arrays.equals(currentStartHash, CryptoUtil.hexToBytes(GENESIS_BLOCK_HASH_HEX))) {
                log.error("起始区块不存在，同步失败");
                return;
            }

            // 循环分批获取区块
            while (true) {
                // 1. 分批请求区块（本次最多BATCH_SIZE个）
                List<Block> remoteBlocks = fetchBlockBatch(remoteNode, currentStartHash, endHash,BASE_BATCH_SIZE);
                if (remoteBlocks == null || remoteBlocks.isEmpty()) {
                    log.info("所有区块同步完成");
                    syncProgress.remove(nodeId); // 清除进度
                    return;
                }

                // 2. 处理当前批次区块
                Block lastProcessedBlock = null;
                for (Block block : remoteBlocks) {
                    // 验证区块合法性
                    if (!localChainService.verifyBlock(block,false)) {
                        log.error("区块验证失败，哈希: {}", CryptoUtil.bytesToHex(block.getHash()));
                        throw new RuntimeException("无效区块，中断同步");
                    }

                    // 检查连续性
                    if (prevBlock != null && !Arrays.equals(block.getPreviousHash(), prevBlock.getHash())) {
                        log.warn("区块不连续，补充中间区块");
                        // 递归处理中间缺失的区块（范围小，递归安全）
                        // sendHeadersRequest(nodeServer, remoteNode, prevBlock.getHash(), block.getHash());

                        // 原递归部分改为循环
                        while (prevBlock != null && !Arrays.equals(block.getPreviousHash(), prevBlock.getHash())) {
                            log.warn("区块不连续，补充中间区块（从 {} 到 {}）",
                                    CryptoUtil.bytesToHex(prevBlock.getHash()), CryptoUtil.bytesToHex(block.getPreviousHash()));

                            // 拉取中间缺失的区块（最多BATCH_SIZE个）
                            List<Block> missingBlocks = fetchBlockBatch(remoteNode, prevBlock.getHash(), block.getPreviousHash(),BASE_BATCH_SIZE);
                            if (missingBlocks == null || missingBlocks.isEmpty()) {
                                throw new RuntimeException("补充中间区块失败，中断同步");
                            }

                            // 处理中间区块
                            for (Block missingBlock : missingBlocks) {
                                if (!localChainService.verifyBlock(missingBlock,false)) {
                                    throw new RuntimeException("中间区块验证失败，哈希: " + CryptoUtil.bytesToHex(missingBlock.getHash()));
                                }
                                localChainService.addBlockToMainChain(missingBlock);
                                log.info("补充中间区块[{}]，高度: {}", CryptoUtil.bytesToHex(missingBlock.getHash()), missingBlock.getHeight());
                                prevBlock = missingBlock;
                            }
                        }

                        prevBlock = localChainService.getBlockByHash(block.getPreviousHash());
                        if (prevBlock == null) {
                            throw new RuntimeException("补充区块失败，中断同步");
                        }
                    }

                    // 添加到本地链
                    localChainService.addBlockToMainChain(block);
                    log.info("同步区块[{}]，高度: {}", CryptoUtil.bytesToHex(block.getHash()), block.getHeight());
                    lastProcessedBlock = block;
                    prevBlock = block;
                }

                // 3. 记录进度，准备下一批
                if (lastProcessedBlock != null) {
                    String lastHashHex = CryptoUtil.bytesToHex(lastProcessedBlock.getHash());
                    syncProgress.put(nodeId, lastHashHex);
                    currentStartHash = lastProcessedBlock.getHash();

                    // 检查是否已达到目标区块
                    if (Arrays.equals(lastProcessedBlock.getHash(), endHash)) {
                        log.info("已同步到目标区块 {}", lastHashHex);
                        syncProgress.remove(nodeId);
                        return;
                    }
                }
            }

        } catch (Exception e) {
            log.error("同步失败，清理进度", e);
            syncProgress.remove(nodeId); // 异常时移除错误进度
            throw e; // 向上抛出，中断同步
        }
    }

    // 新增：分批获取区块（带重试和超时）


    /**
     * 发送分叉点查找请求（异步执行）
     */
    private void sendForkPointRequest(KademliaNodeServer nodeServer, NodeInfo remoteNode)
            throws ConnectException, InterruptedException {
        log.info("发送分叉点查找请求到节点: {}", remoteNode);
        try {

            RpcProxyFactory proxyFactory = new RpcProxyFactory(kademliaNodeServer, remoteNode);
            BlockChainService remoteChainService = proxyFactory.createProxy(BlockChainService.class);
            BlockChainService localChainService = nodeServer.getBlockChainService();

            // 1. 收集本地链的关键哈希（二分查找优化，如每1000块取一个哈希）
            List<byte[]> localHashes = collectKeyHashes(localChainService);

            // 2. 远程节点查找分叉点（最后一个共同哈希）
            //byte[] forkPointHash = remoteChainService.findForkPoint(localHashes);
            byte[] forkPointHash = findForkPointWithBinarySearch(remoteChainService, localChainService);

            if (forkPointHash == null) {
                forkPointHash = CryptoUtil.hexToBytes(GENESIS_BLOCK_HASH_HEX); // 无共同区块，从创世开始
                log.warn("未找到共同区块，从创世区块同步");
            }

            log.info("找到分叉点，哈希: {}", CryptoUtil.bytesToHex(forkPointHash));

            // 3. 从分叉点同步远程链的后续区块
            byte[] remoteLatestHash = remoteChainService.getMainLatestBlockHash();
            sendHeadersRequest(nodeServer, remoteNode, forkPointHash, remoteLatestHash);

        } catch (Exception e) {
            log.error("发送分叉点查找请求失败", e);
        }
    }


    // 优化分叉点查找逻辑（远程节点实现对应方法）
    private byte[] findForkPointWithBinarySearch(BlockChainService remoteService, BlockChainService localService) {
        long localHeight = localService.getMainLatestHeight();
        long remoteHeight = remoteService.getMainLatestHeight();
        long low = 0;
        long high = Math.min(localHeight, remoteHeight);
        byte[] forkHash = CryptoUtil.hexToBytes(GENESIS_BLOCK_HASH_HEX); // 默认创世区块

        while (low <= high) {
            long mid = (low + high) / 2;
            Block localBlock = localService.getMainBlockByHeight(mid);
            Block remoteBlock = remoteService.getMainBlockByHeight(mid);

            if (localBlock != null && remoteBlock != null && Arrays.equals(localBlock.getHash(), remoteBlock.getHash())) {
                // 中间高度区块相同，尝试更高高度
                forkHash = localBlock.getHash();
                low = mid + 1;
            } else {
                // 中间高度区块不同，尝试更低高度
                high = mid - 1;
            }
        }
        return forkHash;
    }

    // 辅助方法：收集本地链的关键哈希（用于分叉点查找）
    private List<byte[]> collectKeyHashes(BlockChainService localChainService) {
        List<byte[]> hashes = new ArrayList<>();
        long latestHeight = localChainService.getMainLatestHeight();
        // 从最新区块开始，每1000个高度取一个哈希（可调整步长）
        for (long h = latestHeight; h >= 0; h -= 1000) {
            Block block = localChainService.getMainBlockByHeight(h);
            if (block != null) {
                hashes.add(block.getHash());
            } else {
                break;
            }
        }
        hashes.add(CryptoUtil.hexToBytes(GENESIS_BLOCK_HASH_HEX)); // 包含创世区块
        return hashes;
    }



    // ------------------- 同步进度查询接口 -------------------
    @Override
    public List<SyncProgress> getAllSyncProgress() {
        return new ArrayList<>(globalSyncProgress.values());
    }

    /**
     * 获取指定任务ID的进度
     */
    @Override
    public SyncProgress getSyncProgressByTaskId(String taskId) {
        return globalSyncProgress.get(taskId);
    }

    /**
     * 获取指定节点的同步进度
     */
    @Override
    public SyncProgress getSyncProgressByNodeId(BigInteger nodeId) {
        String taskId = nodeTaskMap.get(nodeId);
        return taskId != null ? globalSyncProgress.get(taskId) : null;
    }

    /**
     * 取消同步任务
     */
    @Override
    public boolean cancelSyncTask(String taskId) {
        SyncProgress progress = globalSyncProgress.get(taskId);
        if (progress == null) return false;

        progress.setStatus(SyncProgress.SyncStatus.PAUSED);
        progress.setErrorMsg("任务已取消");
        // 从节点映射中移除
        nodeTaskMap.remove(progress.getNodeId());
        return true;
    }

    /**
     * 关闭资源
     */
    public void shutdown() {
        syncExecutor.shutdownNow();
        globalSyncProgress.clear();
        nodeTaskMap.clear();
    }


}
