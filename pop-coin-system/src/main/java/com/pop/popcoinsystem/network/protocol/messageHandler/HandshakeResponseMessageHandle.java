package com.pop.popcoinsystem.network.protocol.messageHandler;

import com.pop.popcoinsystem.network.KademliaNodeServer;
import com.pop.popcoinsystem.network.common.Bucket;
import com.pop.popcoinsystem.network.common.ExternalNodeInfo;
import com.pop.popcoinsystem.network.common.NodeInfo;
import com.pop.popcoinsystem.network.common.RoutingTable;
import com.pop.popcoinsystem.network.exception.FullBucketException;
import com.pop.popcoinsystem.network.protocol.message.*;
import com.pop.popcoinsystem.util.BeanCopyUtils;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import java.io.Serializable;
import java.math.BigInteger;
import java.util.Date;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
public class HandshakeResponseMessageHandle implements MessageHandler{
    @Override
    public KademliaMessage<? extends Serializable> handleMesage(KademliaNodeServer kademliaNodeServer, KademliaMessage<?> message) throws InterruptedException, FullBucketException {
        log.info("收到握手响应--握手成功");
        return doHandle(kademliaNodeServer, (HandshakeResponseMessage) message);
    }


    protected EmptyKademliaMessage doHandle(KademliaNodeServer kademliaNodeServer, @NotNull HandshakeResponseMessage message) throws InterruptedException {
        RoutingTable routingTable = kademliaNodeServer.getRoutingTable();
        NodeInfo sender = message.getSender();//消息来源
        ExternalNodeInfo data = message.getData();
        try {
            log.info("成功更新节点 {} 到路由表", sender.getId());
            //ping消息应该携带 节点基本消息外的额外消息如
            kademliaNodeServer.getRoutingTable().update(data);
        }catch (FullBucketException e){
            Bucket bucket = routingTable.findBucket(sender.getId());
            // 为每个节点创建超时任务：若超时未收到Pong，则标记为不活跃 清楚掉节点
            ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
            for (BigInteger nodeId : bucket.getNodeIds()) {
                ExternalNodeInfo oldNode = bucket.getNode(nodeId);
                PingKademliaMessage pingKademliaMessage = new PingKademliaMessage();
                pingKademliaMessage.setSender(kademliaNodeServer.getNodeInfo());
                pingKademliaMessage.setReceiver(message.getSender());
                kademliaNodeServer.getUdpClient().sendMessage(pingKademliaMessage);
                // 超时任务：5秒未收到Pong，则认为不活跃，从桶中移除
                scheduler.schedule(() -> {
                    Date now = new Date();
                    // 计算时间差（毫秒）
                    long timeDiff = now.getTime() - oldNode.getLastSeen().getTime();
                    // 若最后活跃时间超过阈值（如5秒），则判定为不活跃
                    if (timeDiff > 5000) {
                        log.info("节点 {} 不活跃，从路由表移除", oldNode.getId());
                        bucket.remove(oldNode.getId()); // 从桶中移除
                    }
                }, 5, TimeUnit.SECONDS); // 超时时间设为3秒（可调整）
            }
        }
        return new EmptyKademliaMessage();
    }
}
