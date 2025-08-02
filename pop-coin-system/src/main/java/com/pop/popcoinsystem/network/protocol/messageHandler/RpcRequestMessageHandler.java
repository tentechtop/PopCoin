package com.pop.popcoinsystem.network.protocol.messageHandler;

import com.pop.popcoinsystem.network.KademliaNodeServer;
import com.pop.popcoinsystem.network.common.NodeInfo;
import com.pop.popcoinsystem.network.protocol.message.KademliaMessage;
import com.pop.popcoinsystem.network.protocol.message.RpcRequestMessage;
import com.pop.popcoinsystem.network.protocol.message.RpcResponseMessage;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import java.io.Serializable;

@Slf4j
public class RpcRequestMessageHandler implements MessageHandler {

    @Override
    public KademliaMessage<? extends Serializable> handleMesage(KademliaNodeServer kademliaNodeServer, KademliaMessage<?> message) throws InterruptedException {
        return doHandle(kademliaNodeServer, (RpcRequestMessage) message);
    }


    protected RpcResponseMessage doHandle(KademliaNodeServer kademliaNodeServer, @NotNull RpcRequestMessage rpcRequest) throws InterruptedException {
        log.info("收到RpcRequest -> 调用请求");
        if (!(rpcRequest instanceof RpcRequestMessage)) {
            return null;
        }
        NodeInfo me = kademliaNodeServer.getNodeInfo();
        NodeInfo sender = rpcRequest.getSender();


        RpcResponseMessage response = new RpcResponseMessage();
        response.setRequestId(rpcRequest.getRequestId());
        response.setSender(me);
        response.setReceiver(sender);



        try {
            // 从 RpcRequestData 中获取调用信息
            String serviceName = rpcRequest.getServiceName();
            String methodName = rpcRequest.getMethodName();
            Class<?>[] paramTypes = rpcRequest.getParamTypes();
            Object[] parameters = rpcRequest.getParameters();

            // 反射调用本地服务（逻辑不变）
            /*Object service = serviceRegistry.getService(serviceName);*/
            // ... 调用逻辑 ...

            response.setResult(null);
        } catch (Exception e) {
            response.setException(e);
        }





        return response;
    }

}
