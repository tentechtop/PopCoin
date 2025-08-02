package com.pop.popcoinsystem.network.service;

import com.pop.popcoinsystem.network.KademliaNodeServer;
import com.pop.popcoinsystem.network.TCPClient;
import com.pop.popcoinsystem.network.common.ExternalNodeInfo;
import com.pop.popcoinsystem.network.common.NodeInfo;
import com.pop.popcoinsystem.network.protocol.message.RpcRequestMessage;
import com.pop.popcoinsystem.network.rpc.RpcRequest;
import com.pop.popcoinsystem.util.BeanCopyUtils;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.Promise;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import static com.pop.popcoinsystem.constant.BlockChainConstants.messageIdGenerator;

/**
 * RPC客户端代理工厂：
 * 1. 为服务接口创建动态代理对象
 * 2. 将本地方法调用转换为RPC网络请求
 * 3. 管理请求-响应的匹配与异步等待
 */
@Slf4j
@Component
public class RpcClientProxyFactory {
    // 注入Kademlia节点服务（用于获取路由表）
    @Lazy
    @Autowired
    private KademliaNodeServer nodeServer;

    // 注入TCP客户端（用于发送RPC请求）
    @Lazy
    @Autowired
    private TCPClient tcpClient;


    // 存储请求ID与响应Promise的映射（线程安全）
    private final Map<Long, Promise<Object>> requestPromises = new ConcurrentHashMap<>();


    /**
     * 创建服务接口的代理对象
     * @param serviceInterface 服务接口类（如TransactionService.class）
     * @param targetNodeId 目标服务节点的Kademlia ID
     * @param <T> 服务接口类型
     * @return 代理对象，可像调用本地方法一样调用远程服务
     */
    @SuppressWarnings("unchecked")
    public <T> T createProxy(Class<T> serviceInterface, BigInteger targetNodeId) {
        // 校验接口合法性
        if (!serviceInterface.isInterface()) {
            throw new IllegalArgumentException("服务类型必须是接口: " + serviceInterface.getName());
        }

        // 创建动态代理对象
        return (T) Proxy.newProxyInstance(
                serviceInterface.getClassLoader(),
                new Class[]{serviceInterface},
                new RpcInvocationHandler(serviceInterface, targetNodeId)
        );
    }

    /**
     * RPC调用处理器：拦截接口方法调用，转为网络请求
     */
    private class RpcInvocationHandler implements InvocationHandler {
        private final Class<?> serviceInterface;
        private final BigInteger targetNodeId;

        public RpcInvocationHandler(Class<?> serviceInterface, BigInteger targetNodeId) {
            this.serviceInterface = serviceInterface;
            this.targetNodeId = targetNodeId;
        }

        /**
         * 拦截方法调用，执行RPC逻辑
         */
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            // 1. 跳过Object类的方法（如toString、hashCode等）
            if (method.getDeclaringClass() == Object.class) {
                return method.invoke(this, args);
            }

            // 2. 生成唯一请求ID（使用UUID确保全局唯一）
            long requestId = messageIdGenerator.incrementAndGet();
            log.debug("创建RPC请求 | ID: {} | 方法: {}.{}",
                    requestId, serviceInterface.getName(), method.getName());

            // 3. 构建RPC请求对象
            RpcRequestMessage request = buildRpcRequest(requestId, method, args);

            // 4. 查找目标节点（通过Kademlia路由表）
            ExternalNodeInfo targetNode = nodeServer.getRoutingTable().findNode(targetNodeId);
            if (targetNode == null) {
                throw new RuntimeException("未找到目标节点: " + targetNodeId);
            }
            request.setReceiver(BeanCopyUtils.copyObject(targetNode, NodeInfo.class));
            request.setSender(nodeServer.getNodeInfo());

            // 5. 创建Promise等待响应
            Promise<Object> promise = createPromise(requestId);

            // 6. 发送RPC请求（通过TCP确保可靠传输）
            sendRpcRequest(request);

            // 7. 阻塞等待响应（超时时间可配置）
            try {
                return promise.get(30, TimeUnit.SECONDS); // 30秒超时
            } catch (TimeoutException e) {
                // 超时后移除Promise，避免内存泄漏
                requestPromises.remove(requestId);
                throw new RuntimeException("RPC调用超时 | ID: " + requestId, e);
            }
        }

        /**
         * 构建RPC请求对象
         */
        private RpcRequestMessage buildRpcRequest(Long requestId, Method method, Object[] args) {
            RpcRequestMessage request = new RpcRequestMessage();
            request.setRequestId(requestId);
            request.setServiceName(serviceInterface.getName());
            request.setMethodName(method.getName());
            request.setParamTypes(method.getParameterTypes());
            request.setParameters(args);
            return request;
        }

        /**
         * 创建响应Promise
         */
        private Promise<Object> createPromise(Long requestId) {
            // 使用Netty的EventLoop线程池创建Promise，确保线程安全
            Promise<Object> promise = new DefaultPromise<>(tcpClient.getEventLoopGroup().next());
            requestPromises.put(requestId, promise);
            return promise;
        }

        /**
         * 发送RPC请求
         */
        private void sendRpcRequest(RpcRequestMessage request) throws Exception {
            try {
                tcpClient.sendMessage(request);
                log.debug("RPC请求已发送 | ID: {} | 目标节点: {}",
                        request.getRequestId(), request.getReceiver().getId().toString().substring(0, 8));
            } catch (Exception e) {
                // 发送失败时移除Promise
                requestPromises.remove(request.getRequestId());
                log.error("RPC请求发送失败 | ID: {}", request.getRequestId(), e);
                throw e;
            }
        }
    }

    /**
     * 处理收到的RPC响应（由RpcResponseMessageHandler调用）
     * @param response RPC响应消息
     */
    public void handleRpcResponse(RpcRequestMessage response) {
        if (response == null || response.getRequestId() == -1) {
            log.warn("收到无效的RPC响应: 缺少请求ID");
            return;
        }

        long requestId = response.getRequestId();
        Promise<Object> promise = requestPromises.remove(requestId);

        if (promise == null) {
            log.warn("未找到匹配的RPC请求 | ID: {}", requestId);
            return;
        }

        // 根据响应结果设置Promise状态
        if (response.getException() != null) {
            log.error("RPC调用远程异常 | ID: {}", requestId, response.getException());
            promise.setFailure(response.getException());
        } else {
            log.debug("RPC响应处理完成 | ID: {}", requestId);
            promise.setSuccess(response);
        }
    }

    /**
     * 清理超时未响应的请求（防止内存泄漏）
     * 可由定时任务调用
     */
    public void cleanTimeoutRequests() {
        // 实际实现中可根据超时时间清理过期请求
        // 简化实现：保留最近1000个请求，超出则清理最早的
        if (requestPromises.size() > 1000) {
            int removeCount = requestPromises.size() - 1000;
            requestPromises.keySet().stream()
                    .limit(removeCount)
                    .forEach(key -> {
                        Promise<Object> promise = requestPromises.remove(key);
                        if (!promise.isDone()) {
                            promise.setFailure(new TimeoutException("RPC请求已过期 | ID: " + key));
                        }
                    });
            log.debug("清理过期RPC请求 | 数量: {}", removeCount);
        }
    }




}
