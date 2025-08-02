package com.pop.popcoinsystem.network.protocol.messageHandler;

import com.pop.popcoinsystem.data.block.Block;
import com.pop.popcoinsystem.network.KademliaNodeServer;
import com.pop.popcoinsystem.network.common.ExternalNodeInfo;
import com.pop.popcoinsystem.exception.FullBucketException;
import com.pop.popcoinsystem.network.common.NodeInfo;
import com.pop.popcoinsystem.network.protocol.message.*;
import com.pop.popcoinsystem.network.protocol.messageData.Handshake;
import com.pop.popcoinsystem.service.BlockChainService;
import com.pop.popcoinsystem.util.BeanCopyUtils;
import com.pop.popcoinsystem.util.CryptoUtil;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import java.io.Serializable;
import java.net.ConnectException;

import static com.pop.popcoinsystem.constant.BlockChainConstants.GENESIS_BLOCK_HASH_HEX;

@Slf4j
public class HandshakeRequestMessageHandle implements MessageHandler{
    @Override
    public KademliaMessage<? extends Serializable> handleMesage(KademliaNodeServer kademliaNodeServer, KademliaMessage<?> message) throws InterruptedException, FullBucketException, ConnectException {
        return doHandle(kademliaNodeServer, (HandshakeRequestMessage) message);
    }


    protected HandshakeResponseMessage doHandle(KademliaNodeServer kademliaNodeServer, @NotNull HandshakeRequestMessage message) throws InterruptedException, ConnectException {
        log.info("收到握手请求");
        //将该节点添加到路由表中  一定是活跃节点记录下
        Handshake handshake = message.getData();
        ExternalNodeInfo data = handshake.getExternalNodeInfo();
                NodeInfo nodeInfo = kademliaNodeServer.getNodeInfo();
        ExternalNodeInfo externalNodeInfo = BeanCopyUtils.copyObject(nodeInfo, ExternalNodeInfo.class);
        try{
            kademliaNodeServer.getRoutingTable().update(data);
            log.info("已经更新路由表");
            //返回握手响应
            BlockChainService blockChainService = kademliaNodeServer.getBlockChainService();
            Block block = blockChainService.getMainLatestBlock();

            ExternalNodeInfo me = kademliaNodeServer.getExternalNodeInfo();
            Handshake handshakeResponse = new Handshake();
            handshakeResponse.setExternalNodeInfo(me);
            handshakeResponse.setGenesisBlockHash(CryptoUtil.hexToBytes(GENESIS_BLOCK_HASH_HEX));
            handshakeResponse.setLatestBlockHash(block.getHash());
            handshakeResponse.setLatestBlockHeight(block.getHeight());
            handshakeResponse.setChainWork(block.getChainWork());

            HandshakeResponseMessage handshakeResponseMessage = new HandshakeResponseMessage(handshakeResponse);
            handshakeResponseMessage.setSender(kademliaNodeServer.getNodeInfo());
            handshakeResponseMessage.setReceiver(message.getSender());
            handshakeResponseMessage.setData(handshakeResponse);
            kademliaNodeServer.getTcpClient().sendMessage(handshakeResponseMessage);
        }catch (FullBucketException e) {
            log.info("PingMessageHandler ");
        }
        return null;
    }
}
