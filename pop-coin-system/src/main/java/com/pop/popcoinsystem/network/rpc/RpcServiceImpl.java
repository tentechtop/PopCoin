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
    public PongKademliaMessage ping() throws InterruptedException, FullBucketException {
        log.info("收到ping--rpc");
        PongKademliaMessage pongKademliaMessage = new PongKademliaMessage();
        pongKademliaMessage.setSender(kademliaNodeServer.getNodeInfo());
        return pongKademliaMessage;
    }
}
