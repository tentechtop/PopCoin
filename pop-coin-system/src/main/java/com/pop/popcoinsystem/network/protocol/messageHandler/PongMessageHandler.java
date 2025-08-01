package com.pop.popcoinsystem.network.protocol.messageHandler;

import com.pop.popcoinsystem.data.block.Block;
import com.pop.popcoinsystem.network.KademliaNodeServer;
import com.pop.popcoinsystem.exception.FullBucketException;
import com.pop.popcoinsystem.network.common.ExternalNodeInfo;
import com.pop.popcoinsystem.network.common.NodeInfo;
import com.pop.popcoinsystem.network.protocol.message.*;
import com.pop.popcoinsystem.network.protocol.message.content.Handshake;
import com.pop.popcoinsystem.service.BlockChainService;
import com.pop.popcoinsystem.util.BeanCopyUtils;
import com.pop.popcoinsystem.util.CryptoUtil;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import java.io.Serializable;
import java.net.ConnectException;

import static com.pop.popcoinsystem.constant.BlockChainConstants.GENESIS_BLOCK_HASH_HEX;

@Slf4j
public class PongMessageHandler implements MessageHandler{

    @Override
    public KademliaMessage<? extends Serializable> handleMesage(KademliaNodeServer kademliaNodeServer, KademliaMessage<?> message) throws InterruptedException, FullBucketException, ConnectException {
      return doHandle(kademliaNodeServer, (PongKademliaMessage) message);
    }

    protected EmptyKademliaMessage doHandle(KademliaNodeServer kademliaNodeServer, @NotNull PongKademliaMessage message) throws InterruptedException, FullBucketException, ConnectException {
        log.info("收到pong");//pong信息中应该携带网络信息 消息头应该有网络消息
        //主动和节点握手
        NodeInfo sender = message.getSender();
        kademliaNodeServer.getRoutingTable().update(BeanCopyUtils.copyObject(sender, ExternalNodeInfo.class));
        BlockChainService blockChainService = kademliaNodeServer.getBlockChainService();
        Block mainLatestBlock = blockChainService.getMainLatestBlock();
        ExternalNodeInfo me = kademliaNodeServer.getExternalNodeInfo();
        Handshake handshake = new Handshake();
        handshake.setExternalNodeInfo(me);
        handshake.setGenesisBlockHash(CryptoUtil.hexToBytes(GENESIS_BLOCK_HASH_HEX));
        handshake.setLatestBlockHash(mainLatestBlock.getHash());
        handshake.setLatestBlockHeight(mainLatestBlock.getHeight());
        handshake.setChainWork(mainLatestBlock.getChainWork());
        HandshakeRequestMessage handshakeRequestMessage = new HandshakeRequestMessage(handshake);
        handshakeRequestMessage.setSender(kademliaNodeServer.getNodeInfo());
        handshakeRequestMessage.setReceiver(message.getSender());
        kademliaNodeServer.getTcpClient().sendMessage(handshakeRequestMessage);
        return null;
    }

}
