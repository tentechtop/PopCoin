package com.pop.popcoinsystem.network.protocol.messageHandler;

import com.pop.popcoinsystem.exception.FullBucketException;
import com.pop.popcoinsystem.network.KademliaNodeServer;
import com.pop.popcoinsystem.network.common.Bucket;
import com.pop.popcoinsystem.network.common.ExternalNodeInfo;
import com.pop.popcoinsystem.network.common.FindNodeResult;
import com.pop.popcoinsystem.network.common.RoutingTable;
import com.pop.popcoinsystem.network.protocol.message.FindNodeRequestMessage;
import com.pop.popcoinsystem.network.protocol.message.FindNodeResponseMessage;
import com.pop.popcoinsystem.network.protocol.message.KademliaMessage;
import com.pop.popcoinsystem.network.protocol.message.PingKademliaMessage;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import java.io.Serializable;
import java.math.BigInteger;
import java.net.ConnectException;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
public class FindNodeResponseMessageHandler implements MessageHandler{

    @Override
    public KademliaMessage<? extends Serializable> handleMesage(KademliaNodeServer kademliaNodeServer, KademliaMessage<?> message) {

        return null;
    }

    protected FindNodeResponseMessage doHandle(KademliaNodeServer kademliaNodeServer, @NotNull FindNodeResponseMessage message) throws InterruptedException, ConnectException, FullBucketException {
        log.info("收到节点查询响应");
        FindNodeResult data = message.getData();
        List<ExternalNodeInfo> nodes = data.getNodes();
        BigInteger id = kademliaNodeServer.getNodeInfo().getId();
        RoutingTable routingTable = kademliaNodeServer.getRoutingTable();
        for(ExternalNodeInfo node:nodes){
            try {
                //ping消息应该携带 节点基本消息外的额外消息如
                kademliaNodeServer.getRoutingTable().update(node);
            }catch (FullBucketException e){
                Bucket bucket = routingTable.findBucket(id);
                // 为每个节点创建超时任务：若超时未收到Pong，则标记为不活跃 清楚掉节点
                ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
                for (BigInteger nodeId : bucket.getNodeIds()) {
                    ExternalNodeInfo oldNode = bucket.getNode(nodeId);
                    PingKademliaMessage pingKademliaMessage = new PingKademliaMessage();
                    pingKademliaMessage.setSender(kademliaNodeServer.getNodeInfo());
                    pingKademliaMessage.setReceiver(message.getSender());
                    kademliaNodeServer.getTcpClient().sendMessage(pingKademliaMessage);
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
        }




        return null;
    }
}
