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
    public KademliaMessage<? extends Serializable> handleMesage(KademliaNodeServer kademliaNodeServer, KademliaMessage<?> message) throws FullBucketException, InterruptedException, ConnectException {
        return doHandle(kademliaNodeServer, (FindNodeResponseMessage) message);
    }

    protected FindNodeResponseMessage doHandle(KademliaNodeServer kademliaNodeServer, @NotNull FindNodeResponseMessage message) throws InterruptedException, ConnectException, FullBucketException {
        FindNodeResult data = message.getData();
        List<ExternalNodeInfo> nodes = data.getNodes();
        log.debug("收到查询结果{}",nodes);
        for(ExternalNodeInfo node:nodes){
            kademliaNodeServer.getRoutingTable().update(node);
        }
        return null;
    }
}
