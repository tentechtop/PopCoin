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

import java.net.ConnectException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.pop.popcoinsystem.constant.BlockChainConstants.GENESIS_BLOCK_HASH_HEX;

@Slf4j
public class AsyncBlockSynchronizer {

    // 线程池用于执行异步任务
    private final ExecutorService syncExecutor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable);
        thread.setName("block-sync-worker");
        thread.setDaemon(true); // 守护线程，程序退出时自动关闭
        return thread;
    });


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
                                    byte[] startHash, byte[] endHash)
            throws ConnectException, InterruptedException {
        log.info("发送区块头请求，从 {} 到 {}",
                CryptoUtil.bytesToHex(startHash), CryptoUtil.bytesToHex(endHash));
        try {
            RpcProxyFactory proxyFactory = new RpcProxyFactory(kademliaNodeServer,remoteNode);
            BlockChainService blockChainService = proxyFactory.createProxy(BlockChainService.class);
            List<Block> remoteBlocks  = blockChainService.getBlockByStartHashAndEndHash(startHash, endHash);
            if (remoteBlocks == null || remoteBlocks.isEmpty()) {
                log.warn("未获取到远程区块，可能已同步完成");
                return;
            }
            // 本地区块链服务（用于验证和应用区块）
            BlockChainService localChainService = nodeServer.getBlockChainService(); // 假设节点持有本地服务实例
            Block prevBlock = localChainService.getBlockByHash(startHash); // 起始区块（本地已存在）


            // 遍历远程区块，验证并应用
            for (Block block : remoteBlocks) {
                // 1. 验证区块合法性（PoW、交易、前哈希连续性）
                if (!localChainService.verifyBlock(block)) {
                    log.error("区块验证失败，哈希: {}", CryptoUtil.bytesToHex(block.getHash()));
                    break; // 无效区块中断同步
                }
                // 2. 检查区块连续性（当前区块的前哈希需等于上一区块的哈希）
                if (prevBlock != null && !Arrays.equals(block.getPreviousHash(), prevBlock.getHash())) {
                    log.error("区块不连续，预期前哈希: {}，实际: {}",
                            CryptoUtil.bytesToHex(prevBlock.getHash()),
                            CryptoUtil.bytesToHex(block.getPreviousHash()));
                    // 尝试补充中间缺失的区块（递归请求）
                    sendHeadersRequest(nodeServer, remoteNode, prevBlock.getHash(), block.getHash());
                    return;
                }
                // 3. 应用区块到本地链（主链或备选链）
                localChainService.addBlockToMainChain(block); // 若为分叉链，需先处理本地分叉
                log.info("成功同步区块，高度: {}，哈希: {}", block.getHeight(), CryptoUtil.bytesToHex(block.getHash()));
                prevBlock = block; // 更新上一区块，继续处理下一个
            }
        } catch (Exception e) {
            log.error("发送区块头请求失败", e);
        }
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
