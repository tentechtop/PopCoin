package com.pop.popcoinsystem.network.protocol.messageHandler;

import com.pop.popcoinsystem.network.KademliaNodeServer;
import com.pop.popcoinsystem.network.common.ExternalNodeInfo;
import com.pop.popcoinsystem.network.common.NodeInfo;
import com.pop.popcoinsystem.network.exception.FullBucketException;
import com.pop.popcoinsystem.network.protocol.message.*;
import com.pop.popcoinsystem.util.BeanCopyUtils;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import java.io.Serializable;
import java.util.UUID;

@Slf4j
public class HandshakeRequestMessageHandle implements MessageHandler{
    @Override
    public KademliaMessage<? extends Serializable> handleMesage(KademliaNodeServer kademliaNodeServer, KademliaMessage<?> message) throws InterruptedException, FullBucketException {
        log.info("收到握手请求-消息处理器"+message.getSender());
        return doHandle(kademliaNodeServer, (HandshakeRequestMessage) message);
    }


    protected HandshakeResponseMessage doHandle(KademliaNodeServer kademliaNodeServer, @NotNull HandshakeRequestMessage message) throws InterruptedException {
        if (kademliaNodeServer.isRunning()){
            //将该节点添加到路由表中  一定是活跃节点记录下
            ExternalNodeInfo data = message.getData();
            try{
                kademliaNodeServer.getRoutingTable().update(data);
                log.info("已经更新路由表");
                //返回握手响应
                HandshakeResponseMessage handshakeResponseMessage = new HandshakeResponseMessage(kademliaNodeServer.getExternalNodeInfo());
                handshakeResponseMessage.setSender(kademliaNodeServer.getNodeInfo());
                handshakeResponseMessage.setReceiver(message.getSender());
                kademliaNodeServer.getUdpClient().sendMessage(handshakeResponseMessage);
            }catch (FullBucketException e) {
                log.info("PingMessageHandler ");
            }
        }
        return new HandshakeResponseMessage(kademliaNodeServer.getExternalNodeInfo());
    }
}
