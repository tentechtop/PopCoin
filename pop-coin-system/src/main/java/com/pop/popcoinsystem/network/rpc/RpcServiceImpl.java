package com.pop.popcoinsystem.network.rpc;

import com.pop.popcoinsystem.exception.FullBucketException;
import com.pop.popcoinsystem.network.common.NodeInfo;
import com.pop.popcoinsystem.network.protocol.message.KademliaMessage;
import com.pop.popcoinsystem.network.protocol.message.PingKademliaMessage;
import com.pop.popcoinsystem.network.protocol.message.PongKademliaMessage;
import com.pop.popcoinsystem.network.service.KademliaNodeServer;
import lombok.extern.slf4j.Slf4j;
import org.checkerframework.checker.units.qual.A;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.io.Serializable;

@Slf4j
@Service
public class RpcServiceImpl implements RpcService {

    @Lazy
    @Autowired
    private KademliaNodeServer kademliaNodeServer;

    @Override
    public PongKademliaMessage ping(PingKademliaMessage message) throws InterruptedException, FullBucketException {
        log.info("收到ping--rpc");
        NodeInfo sender = message.getSender();
        kademliaNodeServer.getRoutingTable().update(sender);
        PongKademliaMessage pongKademliaMessage = new PongKademliaMessage();
        long requestId = message.getRequestId();
        pongKademliaMessage.setSender(kademliaNodeServer.getNodeInfo());
        pongKademliaMessage.setReceiver(message.getSender());
        pongKademliaMessage.setRequestId(requestId);
        pongKademliaMessage.setResponse(true);
        return pongKademliaMessage;
    }
}
