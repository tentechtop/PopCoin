package com.pop.popcoinsystem.network.rpc;

import com.pop.popcoinsystem.aop.annotation.RpcServiceAlias;
import com.pop.popcoinsystem.exception.FullBucketException;
import com.pop.popcoinsystem.network.common.NodeInfo;
import com.pop.popcoinsystem.network.protocol.message.KademliaMessage;
import com.pop.popcoinsystem.network.protocol.message.PingKademliaMessage;
import com.pop.popcoinsystem.network.protocol.message.PongKademliaMessage;

import java.io.Serializable;

@RpcServiceAlias("RpcService")// 标记为需要注册的RPC服务
public interface RpcService {

    //ping
    PongKademliaMessage ping() throws InterruptedException, FullBucketException;


}
