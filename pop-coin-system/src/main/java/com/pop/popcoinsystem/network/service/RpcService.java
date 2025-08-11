package com.pop.popcoinsystem.network.service;

import com.pop.popcoinsystem.aop.annotation.RpcServiceAlias;
import com.pop.popcoinsystem.network.common.NodeInfo;
import com.pop.popcoinsystem.network.protocol.message.KademliaMessage;

import java.io.Serializable;

@RpcServiceAlias("RpcService")// 标记为需要注册的RPC服务
public interface RpcService {

    //ping
    KademliaMessage<? extends Serializable> ping(NodeInfo nodeInfo) throws InterruptedException;

    //pong
    KademliaMessage<? extends Serializable> pong(NodeInfo nodeInfo) throws InterruptedException;


}
