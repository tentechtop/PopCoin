package com.pop.popcoinsystem.network.rpc;

import com.pop.popcoinsystem.network.common.ExternalNodeInfo;
import com.pop.popcoinsystem.network.common.RoutingTable;
import com.pop.popcoinsystem.network.enums.NodeType;
import com.pop.popcoinsystem.network.service.KademliaNodeServer;
import com.pop.popcoinsystem.network.common.NodeInfo;
import com.pop.popcoinsystem.network.protocol.message.KademliaMessage;
import com.pop.popcoinsystem.network.protocol.message.RpcRequestMessage;
import com.pop.popcoinsystem.network.protocol.messageData.RpcRequestData;
import com.pop.popcoinsystem.network.protocol.messageData.RpcResponseData;
import com.pop.popcoinsystem.util.BeanCopyUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
public class RpcProxyFactory {

    private  KademliaNodeServer kademliaNodeServer;
    private  NodeInfo targetNode; // 目标服务节点信息
    private  RoutingTable routingTable;
    private  NodeInfo localNodeInfo;
    private int timeoutSeconds = 5; // 默认超时时间5秒

    public RpcProxyFactory(KademliaNodeServer server, NodeInfo targetNode) {
        this.kademliaNodeServer = server;
        this.targetNode = targetNode;
        this.routingTable = server.getRoutingTable();
        this.localNodeInfo = server.getNodeInfo();

        log.info("创建RPC代理，目标节点为：{}", targetNode);

        // 检查目标节点是否在线
        if (!routingTable.isNodeAvailable(targetNode)) {
            log.warn("指定的目标节点{}可能不可用，尝试从路由表获取可用节点", targetNode);
            this.targetNode = routingTable.findAvailableNode();
            if (this.targetNode == null) {
                throw new RuntimeException("无任何可用节点");
            }
        }
    }
    public RpcProxyFactory(KademliaNodeServer server) {
        this.kademliaNodeServer = server;
        NodeInfo nodeInfo = server.getNodeInfo();
        RoutingTable routingTable = server.getRoutingTable();
        List<ExternalNodeInfo> closest = routingTable.findNodesByType(NodeType.FULL);
        //去除自己
        closest.removeIf(node -> node.getId().equals(nodeInfo.getId()));
        closest.removeIf(node -> ( node.getIpv4().equals(nodeInfo.getIpv4()) && node.getTcpPort() == nodeInfo.getTcpPort()));
        if (closest.isEmpty()){
            throw new RuntimeException("没有可用的节点");
        }
        ExternalNodeInfo externalNodeInfo = closest.getFirst();
        this.targetNode = externalNodeInfo.extractNodeInfo();
    }

    // 添加超时时间设置方法
    public void setTimeout(int timeoutSeconds) {
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("超时时间必须大于0");
        }
        this.timeoutSeconds = timeoutSeconds;
    }

    @SuppressWarnings("unchecked")
    public <T> T createProxy(Class<T> serviceInterface) {
        return (T) Proxy.newProxyInstance(
                serviceInterface.getClassLoader(),
                new Class[]{serviceInterface},
                new RpcInvocationHandler(serviceInterface, targetNode,timeoutSeconds)
        );
    }


    private class RpcInvocationHandler implements InvocationHandler {
        private final Class<?> serviceInterface;
        private final NodeInfo targetNode;
        private final int timeoutSeconds; // 新增：超时时间

        public RpcInvocationHandler(Class<?> serviceInterface, NodeInfo targetNode,int timeoutSeconds) {
            this.serviceInterface = serviceInterface;
            this.targetNode = targetNode;
            this.timeoutSeconds = timeoutSeconds;
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
                    .sendMessageWithResponse(requestMessage,timeoutSeconds, TimeUnit.SECONDS);

            // 4. 解析响应结果（根据实际响应格式处理）
            return parseResponse(response);
        }

        private Object parseResponse(KademliaMessage response) {
            // 处理空响应情况
            if (response == null) {
                throw new IllegalStateException("未收到任何响应，可能是网络超时或目标节点无响应");
            }
            try {
                // 1. 从响应中获取RpcResponseData
                if (response.getData() instanceof RpcResponseData responseData) {
                    // 2. 检查是否有错误
                    if (responseData.getException() != null) {
                        throw new RuntimeException("远程调用错误: " + responseData.getException().getMessage());
                    }
                    // 3. 获取实际返回值
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