package com.pop.popcoinsystem.network.protocol.messageHandler;

import com.pop.popcoinsystem.network.service.KademliaNodeServer;
import com.pop.popcoinsystem.network.common.NodeInfo;
import com.pop.popcoinsystem.network.protocol.RpcInvoker;
import com.pop.popcoinsystem.network.protocol.message.KademliaMessage;
import com.pop.popcoinsystem.network.protocol.message.RpcRequestMessage;
import com.pop.popcoinsystem.network.protocol.message.RpcResponseMessage;
import com.pop.popcoinsystem.network.rpc.RpcServiceRegistry;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.Serializable;
import java.util.Map;

@Slf4j
public class RpcRequestMessageHandler implements MessageHandler {

    @Autowired
    private RpcInvoker rpcInvoker;

    @Override
    public KademliaMessage<? extends Serializable> handleMesage(KademliaNodeServer kademliaNodeServer, KademliaMessage<?> message) throws InterruptedException {
        return doHandle(kademliaNodeServer, (RpcRequestMessage) message);
    }


    protected RpcResponseMessage doHandle(KademliaNodeServer kademliaNodeServer, @NotNull RpcRequestMessage rpcRequest) throws InterruptedException {
        NodeInfo me = kademliaNodeServer.getNodeInfo();
        NodeInfo sender = rpcRequest.getSender();
        long requestId = rpcRequest.getRequestId();//响应消息必须保持一致
        log.info("收到RPC,{}",rpcRequest.isResponse()? "响应" : "请求");
        RpcResponseMessage response = new RpcResponseMessage();
        response.setRequestId(requestId);
        response.setSender(me);
        response.setReceiver(sender);
        try {
            String serviceName = rpcRequest.getServiceName();
            String methodName = rpcRequest.getMethodName();
            Class<?>[] paramTypes = rpcRequest.getParamTypes();
            Object[] parameters = rpcRequest.getParameters();
            RpcServiceRegistry rpcServiceRegistry = kademliaNodeServer.getRpcServiceRegistry();
            RpcInvoker rpcInvoker = new RpcInvoker(rpcServiceRegistry);
            Object invoke = rpcInvoker.invoke(serviceName, methodName, paramTypes, parameters, rpcRequest.getRequestId());
            log.info("调用结果:{}", invoke);
            response.setResponse(true);//这是一个响应
            response.setResult(invoke);
        } catch (Exception e) {
            response.setException(e);
        }
        return response;
    }
}
