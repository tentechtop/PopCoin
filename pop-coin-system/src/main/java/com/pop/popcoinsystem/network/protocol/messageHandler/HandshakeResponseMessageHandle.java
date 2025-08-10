package com.pop.popcoinsystem.network.protocol.messageHandler;

import com.pop.popcoinsystem.data.block.Block;
import com.pop.popcoinsystem.exception.UnsupportedChainException;
import com.pop.popcoinsystem.network.service.KademliaNodeServer;
import com.pop.popcoinsystem.network.common.ExternalNodeInfo;
import com.pop.popcoinsystem.network.common.NodeInfo;
import com.pop.popcoinsystem.exception.FullBucketException;
import com.pop.popcoinsystem.network.protocol.message.*;
import com.pop.popcoinsystem.network.protocol.messageData.Handshake;
import com.pop.popcoinsystem.service.blockChain.BlockChainServiceImpl;
import com.pop.popcoinsystem.util.BeanCopyUtils;
import com.pop.popcoinsystem.util.CryptoUtil;
import com.pop.popcoinsystem.util.DifficultyUtils;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import java.io.Serializable;
import java.net.ConnectException;
import java.util.Arrays;

import static com.pop.popcoinsystem.constant.BlockChainConstants.GENESIS_PREV_BLOCK_HASH;


@Slf4j
public class HandshakeResponseMessageHandle implements MessageHandler{
    @Override
    public KademliaMessage<? extends Serializable> handleMesage(KademliaNodeServer kademliaNodeServer, KademliaMessage<?> message) throws InterruptedException, FullBucketException, ConnectException, UnsupportedChainException {
        return doHandle(kademliaNodeServer, (HandshakeResponseMessage) message);
    }

    protected EmptyKademliaMessage doHandle(KademliaNodeServer kademliaNodeServer, @NotNull HandshakeResponseMessage message) throws InterruptedException, ConnectException, UnsupportedChainException, FullBucketException {
        log.info("收到握手响应");
        NodeInfo sender = message.getSender();//消息来源
        Handshake handshake = message.getData();
        Boolean handshakeSuccess = handshake.getHandshakeSuccess();
        if (!handshakeSuccess){
            String errorMessage = handshake.getErrorMessage();
            log.error("握手失败:{}", errorMessage);
            //删除节点
            kademliaNodeServer.getRoutingTable().delete(sender.extractExternalNodeInfo());
            throw new UnsupportedChainException(errorMessage);
        }
        ExternalNodeInfo data = handshake.getExternalNodeInfo();
        log.info("对方节点类型:{}", data.getNodeType());
        kademliaNodeServer.getRoutingTable().update(data);
        //再发送查找节点的请求
        log.debug("收到响应后再发送查找节点的请求");
        FindNodeRequestMessage findNodeRequestMessage = new FindNodeRequestMessage();
        findNodeRequestMessage.setSender(kademliaNodeServer.getNodeInfo());
        findNodeRequestMessage.setReceiver(sender);
        findNodeRequestMessage.setData(kademliaNodeServer.getNodeInfo().getId());//根据本节点查找
        kademliaNodeServer.getTcpClient().sendMessage(findNodeRequestMessage);
        return null;
    }



}
