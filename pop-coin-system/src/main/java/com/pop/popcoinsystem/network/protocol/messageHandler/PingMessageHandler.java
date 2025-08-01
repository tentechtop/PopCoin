package com.pop.popcoinsystem.network.protocol.messageHandler;

import com.pop.popcoinsystem.network.KademliaNodeServer;
import com.pop.popcoinsystem.network.common.ExternalNodeInfo;
import com.pop.popcoinsystem.network.common.NodeInfo;
import com.pop.popcoinsystem.exception.FullBucketException;
import com.pop.popcoinsystem.network.protocol.message.KademliaMessage;
import com.pop.popcoinsystem.network.protocol.message.PingKademliaMessage;
import com.pop.popcoinsystem.network.protocol.message.PongKademliaMessage;
import com.pop.popcoinsystem.util.BeanCopyUtils;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import java.io.Serializable;
import java.util.UUID;

@Slf4j
public class PingMessageHandler implements MessageHandler {

    @Override
    public KademliaMessage<? extends Serializable> handleMesage(KademliaNodeServer kademliaNodeServer, KademliaMessage<?> message) throws InterruptedException {
        return doHandle(kademliaNodeServer, (PingKademliaMessage) message);
    }


    protected PongKademliaMessage doHandle(KademliaNodeServer kademliaNodeServer, @NotNull PingKademliaMessage message) throws InterruptedException {
        log.info("收到ping");
        if (kademliaNodeServer.isRunning()){
            //验证版本
            try {
                //主动ping我的一定是活跃节点记录下
                NodeInfo sender = message.getSender();
                ExternalNodeInfo externalNodeInfo = BeanCopyUtils.copyObject(sender, ExternalNodeInfo.class);
                kademliaNodeServer.getRoutingTable().update(externalNodeInfo);
            }catch (FullBucketException e) {
                log.info("PingMessageHandler ");
                //只有收到Pong才主动更新
            }
        }
        PongKademliaMessage pongKademliaMessage = new PongKademliaMessage();
        pongKademliaMessage.setMessageId(UUID.randomUUID().toString());
        pongKademliaMessage.setSender(kademliaNodeServer.getNodeInfo());
        pongKademliaMessage.setReceiver(message.getSender());
        kademliaNodeServer.getUdpClient().sendMessage(pongKademliaMessage);
        return new PongKademliaMessage();
    }

}
