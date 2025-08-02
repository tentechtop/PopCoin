package com.pop.popcoinsystem.service.blockChain;

import com.pop.popcoinsystem.data.block.Block;
import com.pop.popcoinsystem.data.vo.result.Result;
import com.pop.popcoinsystem.network.common.NodeInfo;
import com.pop.popcoinsystem.network.protocol.message.FindForkPointRequestMessage;
import com.pop.popcoinsystem.network.rpc.RpcProxyFactory;
import com.pop.popcoinsystem.network.service.KademliaNodeServer;
import com.pop.popcoinsystem.util.CryptoUtil;
import com.pop.popcoinsystem.util.DifficultyUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.net.ConnectException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;

import static com.pop.popcoinsystem.constant.BlockChainConstants.GENESIS_BLOCK_HASH_HEX;

@Slf4j
@Component
public class AsyncBlockSynchronizer {
    // 新增：分批获取区块的大小（可配置）
    public static final int BATCH_SIZE = 100;
    // 新增：网络请求超时时间（毫秒）
    public static final int RPC_TIMEOUT = 5000;
    // 新增：最大重试次数
    public static final int MAX_RETRY = 3;
    // 新增：记录同步进度（key：远程节点ID，value：最近同步到的区块哈希）
    public final ConcurrentHashMap<BigInteger, String> syncProgress = new ConcurrentHashMap<>();


    // 线程池用于执行异步任务
    // 线程池优化：限制最大线程数，避免资源耗尽
    public final ExecutorService syncExecutor = new ThreadPoolExecutor(
            5, // 核心线程数
            20, // 最大线程数
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),
            runnable -> {
                Thread thread = new Thread(runnable);
                thread.setName("block-sync-worker");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.CallerRunsPolicy() // 任务满时让提交者线程执行，避免任务丢失
    );


    private final KademliaNodeServer kademliaNodeServer;

    public AsyncBlockSynchronizer(KademliaNodeServer kademliaNodeServer) {
        this.kademliaNodeServer = kademliaNodeServer;
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
     * 发送区块头请求（异步执行）
     */
    private void sendHeadersRequest(KademliaNodeServer nodeServer, NodeInfo remoteNode,
                                    byte[] startHash, byte[] endHash) throws ConnectException, InterruptedException {
        log.info("开始同步区块，从 {} 到 {}",
                CryptoUtil.bytesToHex(startHash), CryptoUtil.bytesToHex(endHash));

        // 检查进度：如果之前同步过，从上次进度开始
        BigInteger nodeId = remoteNode.getId();
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
            List<Block> remoteBlocks = fetchBlockBatch(remoteNode, currentStartHash, endHash);
            if (remoteBlocks == null || remoteBlocks.isEmpty()) {
                log.info("所有区块同步完成");
                syncProgress.remove(nodeId); // 清除进度
                return;
            }

            // 2. 处理当前批次区块
            Block lastProcessedBlock = null;
            for (Block block : remoteBlocks) {
                // 验证区块合法性
                if (!localChainService.verifyBlock(block)) {
                    log.error("区块验证失败，哈希: {}", CryptoUtil.bytesToHex(block.getHash()));
                    throw new RuntimeException("无效区块，中断同步");
                }

                // 检查连续性
                if (prevBlock != null && !Arrays.equals(block.getPreviousHash(), prevBlock.getHash())) {
                    log.warn("区块不连续，补充中间区块");
                    // 递归处理中间缺失的区块（范围小，递归安全）
                    sendHeadersRequest(nodeServer, remoteNode, prevBlock.getHash(), block.getHash());
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
    }

    // 新增：分批获取区块（带重试和超时）
    private List<Block> fetchBlockBatch(NodeInfo remoteNode, byte[] startHash, byte[] endHash) throws InterruptedException {
        for (int retry = 0; retry < MAX_RETRY; retry++) {
            try {
                RpcProxyFactory proxyFactory = new RpcProxyFactory(kademliaNodeServer, remoteNode);
                // 设置RPC超时
                proxyFactory.setTimeout(RPC_TIMEOUT);
                BlockChainService remoteService = proxyFactory.createProxy(BlockChainService.class);

                // 调用支持分批的方法（需在BlockChainService中新增）
                return remoteService.getBlockByStartHashAndEndHashWithLimit(startHash, endHash, BATCH_SIZE);
            } catch (Exception e) {
                log.warn("第{}次获取区块失败（节点:{}），重试...", retry + 1, remoteNode, e);
                if (retry == MAX_RETRY - 1) {
                    log.error("达到最大重试次数，获取区块失败", e);
                    return null;
                }
                // 指数退避重试（1s, 2s, 4s...）
                Thread.sleep((long) (1000 * Math.pow(2, retry)));
            }
        }
        return null;
    }

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
            byte[] forkPointHash = remoteChainService.findForkPoint(localHashes);
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

    /**
     * 关闭线程池，释放资源
     */
    public void shutdown() {
        syncExecutor.shutdown();
    }


}
