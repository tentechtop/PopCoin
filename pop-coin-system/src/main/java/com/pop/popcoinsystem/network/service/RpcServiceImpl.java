package com.pop.popcoinsystem.network.service;

import com.pop.popcoinsystem.network.common.NodeInfo;
import com.pop.popcoinsystem.network.protocol.message.KademliaMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.Serializable;

@Slf4j
@Service
public class RpcServiceImpl implements RpcService {
    @Override
    public KademliaMessage<? extends Serializable> ping(NodeInfo nodeInfo) throws InterruptedException {




        return null;
    }

    @Override
    public KademliaMessage<? extends Serializable> pong(NodeInfo nodeInfo) throws InterruptedException {
        return null;
    }
}
