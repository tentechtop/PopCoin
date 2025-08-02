package com.pop.popcoinsystem.network;

import com.pop.popcoinsystem.network.common.NodeInfo;
import com.pop.popcoinsystem.network.protocol.message.KademliaMessage;
import com.pop.popcoinsystem.network.protocol.message.RpcRequestMessage;
import com.pop.popcoinsystem.network.protocol.messageData.RpcRequestData;
import com.pop.popcoinsystem.network.protocol.messageData.RpcResponseData;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

public class RpcProxyFactory {
    private final KademliaNodeServer kademliaNodeServer;
    private final NodeInfo targetNode; // 目标服务节点信息

    public RpcProxyFactory(KademliaNodeServer server, NodeInfo targetNode) {
        this.kademliaNodeServer = server;
        this.targetNode = targetNode;
    }

    @SuppressWarnings("unchecked")
    public <T> T createProxy(Class<T> serviceInterface) {
        return (T) Proxy.newProxyInstance(
                serviceInterface.getClassLoader(),
                new Class[]{serviceInterface},
                new RpcInvocationHandler(serviceInterface, targetNode)
        );
    }

    private class RpcInvocationHandler implements InvocationHandler {
        private final Class<?> serviceInterface;
        private final NodeInfo targetNode;

        public RpcInvocationHandler(Class<?> serviceInterface, NodeInfo targetNode) {
            this.serviceInterface = serviceInterface;
            this.targetNode = targetNode;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            // 1. 构建Rpc请求数据
            RpcRequestData requestData = new RpcRequestData();
            requestData.setServiceName(serviceInterface.getSimpleName()); // 用接口名作为服务名
            requestData.setMethodName(method.getName());
            requestData.setParameters(args);
            requestData.setParamTypes(method.getParameterTypes());

            // 2. 构建请求消息
            RpcRequestMessage requestMessage = new RpcRequestMessage();
            requestMessage.setData(requestData);
            requestMessage.setSender(kademliaNodeServer.getNodeInfo());
            requestMessage.setReqResId();
            requestMessage.setResponse(false);
            requestMessage.setReceiver(targetNode);

            // 3. 发送请求并获取响应
            KademliaMessage response = kademliaNodeServer.getTcpClient()
                    .sendMessageWithResponse(requestMessage);

            // 4. 解析响应结果（根据实际响应格式处理）
            return parseResponse(response);
        }

        private Object parseResponse(KademliaMessage response) {
            try {
                // 1. 从响应中获取RpcResponseData
                if (response.getData() instanceof RpcResponseData) {
                    RpcResponseData responseData = (RpcResponseData) response.getData();
                    // 2. 检查是否有错误
                    if (responseData.getException() != null) {
                        throw new RuntimeException("远程调用错误: " + responseData.getException().getMessage());
                    }
                    // 3. 获取实际返回值（假设RpcResponseData有getResult()方法）
                    return responseData.getResult();
                } else {
                    throw new RuntimeException("无效的响应数据类型: " + response.getData().getClass().getName());
                }
            } catch (Exception e) {
                throw new RuntimeException("解析响应失败", e);
            }
        }
    }
}