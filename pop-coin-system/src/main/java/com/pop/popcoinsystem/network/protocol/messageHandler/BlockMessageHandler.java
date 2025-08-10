package com.pop.popcoinsystem.network.protocol.messageHandler;

import com.pop.popcoinsystem.data.block.Block;
import com.pop.popcoinsystem.network.common.NodeInfo;
import com.pop.popcoinsystem.network.service.KademliaNodeServer;
import com.pop.popcoinsystem.network.protocol.message.BlockMessage;
import com.pop.popcoinsystem.network.protocol.message.KademliaMessage;
import com.pop.popcoinsystem.service.blockChain.BlockChainServiceImpl;
import com.pop.popcoinsystem.util.ByteUtils;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import java.io.Serializable;
import java.net.ConnectException;
import java.util.Objects;

@Slf4j
public class BlockMessageHandler implements MessageHandler {

    @Override
    public KademliaMessage<? extends Serializable> handleMesage(KademliaNodeServer kademliaNodeServer, KademliaMessage<?> message) throws InterruptedException, ConnectException {
        return doHandle(kademliaNodeServer, (BlockMessage) message);
    }

    protected BlockMessage doHandle(KademliaNodeServer kademliaNodeServer, @NotNull BlockMessage message) throws InterruptedException, ConnectException {
        NodeInfo originalAuthor = message.getSender();
        Block data = message.getData();
        byte[] bytes = data.getHash();
        long blockMessageId = ByteUtils.bytesToLong(bytes);
        if (kademliaNodeServer.getBroadcastMessages().getIfPresent(blockMessageId) != null) {
            log.info("接收已处理的区块消息 {}，丢弃", blockMessageId);
        }else {
            BlockChainServiceImpl localBlockChainService = kademliaNodeServer.getBlockChainService();
            // 记录：标记为已处理
            kademliaNodeServer.getBroadcastMessages().put(blockMessageId, Boolean.TRUE);
            kademliaNodeServer.getBlockChainService().verifyBlock(data,false);
            message.setSender(kademliaNodeServer.getNodeInfo());
            kademliaNodeServer.broadcastMessage(message,originalAuthor);

            Thread.startVirtualThread(() -> {
                if (!Objects.equals(originalAuthor.getId(), kademliaNodeServer.getNodeInfo().getId())) {
                    long remoteLatestBlockHeight = data.getHeight();
                    byte[] remoteLatestBlockHash = data.getHash();
                    byte[] remoteLatestChainWork = data.getChainWork();
                    Block mainLatestBlock = localBlockChainService.getMainLatestBlock();
                    long localLatestHeight = mainLatestBlock.getHeight();
                    byte[] localLatestHash = mainLatestBlock.getHash();
                    byte[] localLatestChainWork = mainLatestBlock.getChainWork();
                    //提交差异
                    if (localLatestHeight != remoteLatestBlockHeight) {
                        log.info("与节点{}的区块高度不一致，提交差异", originalAuthor.getId());
                        try {
                            localBlockChainService.compareAndSync(
                                    originalAuthor,
                                    localLatestHeight,
                                    localLatestHash,
                                    localLatestChainWork,
                                    remoteLatestBlockHeight,
                                    remoteLatestBlockHash,
                                    remoteLatestChainWork
                            );
                        } catch (ConnectException e) {
                            throw new RuntimeException(e);
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }
            });
        }
        return null;
    }

}
