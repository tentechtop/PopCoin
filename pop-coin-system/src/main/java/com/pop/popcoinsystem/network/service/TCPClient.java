package com.pop.popcoinsystem.network.service;

import com.pop.popcoinsystem.network.common.NodeInfo;
import com.pop.popcoinsystem.network.protocol.message.KademliaMessage;
import com.pop.popcoinsystem.network.rpc.RequestResponseManager;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.Promise;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

import java.math.BigInteger;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.*;

@Slf4j
public class TCPClient {


    private final ExecutorService executorService;
    private Bootstrap bootstrap;
    private NioEventLoopGroup eventLoopGroup;

    /** 节点ID到Channel的映射 */
    private final Map<BigInteger, Channel> nodeTCPChannel = new ConcurrentHashMap<>();

    // 请求响应管理器，全局唯一实例
    private final RequestResponseManager responseManager;



    // 用于在Channel中存储节点ID的属性键
    private static final AttributeKey<BigInteger> NODE_ID_KEY = AttributeKey.valueOf("NODE_ID");
    private static final int DEFAULT_CONNECT_TIMEOUT = 30000; // 30秒，与Netty默认保持一致

    public TCPClient() {
        executorService = Executors.newFixedThreadPool(10);
        // 全局复用一个EventLoopGroup，避免资源浪费
        eventLoopGroup = new NioEventLoopGroup();
        // 创建全局唯一的请求响应管理器
        responseManager = new RequestResponseManager();
        // 初始化Bootstrap并复用配置
        bootstrap = new Bootstrap();
        bootstrap.group(eventLoopGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000) // 设置连接超时
                .handler(new ChannelInitializer<NioSocketChannel>() {
                    @Override
                    protected void initChannel(NioSocketChannel ch) throws Exception {
                        ChannelPipeline pipeline = ch.pipeline();
                        // 使用独立的编解码器，解除与服务器实现的耦合
                        pipeline.addLast(new KademliaNodeServer.TCPKademliaMessageDecoder());
                        pipeline.addLast(new KademliaNodeServer.TCPKademliaMessageEncoder());
                    }
                });
    }



    public KademliaMessage sendMessageWithResponse(KademliaMessage message)
            throws ConnectException, TimeoutException, InterruptedException, Exception {
        // 默认超时时间5秒，也可以提供重载方法让用户指定超时
        return sendMessageWithResponse(message, 5, TimeUnit.SECONDS);
    }

    /**
     * 重载方法：允许用户指定超时时间
     */
    public KademliaMessage sendMessageWithResponse(KademliaMessage message, long timeout, TimeUnit unit)
            throws ConnectException, TimeoutException, InterruptedException, Exception {
        if (message == null || message.getReceiver() == null) {
            throw new IllegalArgumentException("消息或接收者不能为空");
        }
        //请求ID已经在消息创建阶段设置
        long requestId = message.getRequestId();

        BigInteger nodeId = message.getReceiver().getId();
        Channel channel = getOrCreateChannel(message.getReceiver());
        if (channel == null || !channel.isActive()) {
            throw new ConnectException("节点 " + nodeId + " 无可用连接");
        }

        // 标记为请求消息（非响应）
        message.setResponse(false);
        // 发送请求并获取Promise（内部异步处理）
        Promise<KademliaMessage> promise = responseManager.sendRequest(channel, message, timeout, unit);
        try {
            // 阻塞等待结果
            if (!promise.await(timeout, unit)) {
                // 超时：主动取消并抛出超时异常
                promise.cancel(false);
                throw new TimeoutException("等待节点 " + nodeId + " 响应超时（" + timeout +" "+ unit + "）");
            }
            // 检查结果状态
            if (promise.isSuccess()) {
                return promise.getNow();
            } else {
                // 失败：抛出具体异常
                Throwable cause = promise.cause();
                if (cause instanceof Exception) {
                    throw (Exception) cause;
                } else {
                    throw new Exception("发送消息失败：" + cause.getMessage(), cause);
                }
            }
        } finally {
            // 清理：如果消息处理完成，从管理器中移除
            if (promise.isDone()) {
                responseManager.clearRequest(message.getRequestId());
            }
        }
    }


    public  void sendMessage(KademliaMessage message) throws InterruptedException, ConnectException {
        try {
            if (message == null || message.getReceiver() == null) {
                throw new IllegalArgumentException("Message or receiver cannot be null");
            }
            BigInteger nodeId = message.getReceiver().getId();
            Channel channel = getOrCreateChannel(message.getReceiver());
            if (channel != null && channel.isActive()) {
                // 发送消息并添加监听处理发送结果
                channel.writeAndFlush(message).addListener((ChannelFutureListener) future -> {
                    if (!future.isSuccess()) {
                        log.error("Failed to send message to node {}: {}", nodeId, future.cause().getMessage());
                        handleSendFailure(nodeId, message, future.cause());
                    }
                });
            } else {
                throw new ConnectException("No active channel available for node: " + nodeId);
            }
        }catch (Exception e){
            log.error("Failed to send message: {}", e.getMessage());
        }
    }



    /**
     * 处理消息发送失败的情况
     */
    private void handleSendFailure(BigInteger nodeId, KademliaMessage message, Throwable cause) {
        try {
            // 移除失效通道并尝试重新连接
            nodeTCPChannel.remove(nodeId);
            Channel newChannel = connectTarget(
                    message.getReceiver().getIpv4(),
                    message.getReceiver().getTcpPort(),
                    nodeId
            );
            nodeTCPChannel.put(nodeId, newChannel);
            newChannel.writeAndFlush(message);
            log.info("Reconnected and resent message to node {}", nodeId);
        } catch (Exception e) {
            log.error("Failed to reconnect and resend message to node {}", nodeId, e);
        }
    }


    /**
     * 获取已存在的通道或创建新通道（原子操作避免竞态条件）
     */
    private Channel getOrCreateChannel(NodeInfo receiver) throws InterruptedException, ConnectException {
        BigInteger nodeId = receiver.getId();
        // 检查现有通道是否活跃
        Channel existingChannel = nodeTCPChannel.get(nodeId);
        if (existingChannel != null && existingChannel.isActive()) {
            return existingChannel;
        }
        // 使用computeIfAbsent确保原子操作，避免重复连接
        return nodeTCPChannel.computeIfAbsent(nodeId, key -> {
            try {
                return connectTarget(receiver.getIpv4(), receiver.getTcpPort(), nodeId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // 恢复中断状态
                throw new RuntimeException("Interrupted while connecting to node: " + nodeId, e);
            } catch (ConnectException e) {
                throw new RuntimeException("Failed to connect to node: " + nodeId, e);
            }
        });
    }

    /**
     * 连接目标节点并返回通道
     */
    private Channel connectTarget(String ipv4, int tcpPort, BigInteger nodeId) throws InterruptedException, ConnectException {
        InetSocketAddress address = new InetSocketAddress(ipv4, tcpPort);
        log.info("Trying to connect to {}:{} (node {})", ipv4, tcpPort, nodeId);
        ChannelFuture connectFuture = bootstrap.connect(address);

        Integer connectTimeout = (Integer) bootstrap.config().options().get(ChannelOption.CONNECT_TIMEOUT_MILLIS);
        int timeoutMillis = (connectTimeout != null) ? connectTimeout : DEFAULT_CONNECT_TIMEOUT;
        // 等待连接完成，受CONNECT_TIMEOUT_MILLIS限制
        if (!connectFuture.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
            throw new ConnectException("Connection to " + ipv4 + ":" + tcpPort + " timed out after " + timeoutMillis + "ms");
        }

        if (!connectFuture.isSuccess()) {
            String errorMsg = "Failed to connect to " + ipv4 + ":" + tcpPort;
            log.error(errorMsg, connectFuture.cause());
            throw new ConnectException(errorMsg + ": " + connectFuture.cause().getMessage());
        }
        Channel channel = connectFuture.channel();
        // 存储节点ID与通道的关联，用于后续清理
        channel.attr(NODE_ID_KEY).set(nodeId);
        log.info("成功连接到节点 Successfully connected to {}:{} (node {})", ipv4, tcpPort, nodeId);
        // 非阻塞监听通道关闭事件
        channel.closeFuture().addListener(future -> {
            log.info("Channel to node {} closed", nodeId);
            nodeTCPChannel.remove(nodeId);
        });
        return channel;
    }


    public void sendAsyncMessage(KademliaMessage message) {
        executorService.submit(() -> {
            try {
                sendMessage(message);
            } catch (Exception e) {
                log.error("Async message sending failed", e);
            }
        });
    }


    /**
     * 优雅关闭所有资源
     */
    @PreDestroy
    public void stop() {
        log.info("Stopping TCPClient...");
        // 关闭所有活跃通道
        nodeTCPChannel.values().forEach(channel -> {
            if (channel.isActive()) {
                channel.close().addListener(future ->
                        log.info("Channel closed: {}", channel.remoteAddress())
                );
            }
        });
        // 关闭EventLoopGroup
        if (eventLoopGroup != null) {
            eventLoopGroup.shutdownGracefully(1, 5, TimeUnit.SECONDS)
                    .addListener(future -> log.info("EventLoopGroup has been shut down"));
        }
        // 关闭线程池
        if (executorService != null) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
            }
            log.info("ExecutorService has been shut down");
        }

        // 清空映射表
        nodeTCPChannel.clear();
    }


    public NioEventLoopGroup getEventLoopGroup() {
        return eventLoopGroup;
    }


    public RequestResponseManager getResponseManager() {
        return responseManager;
    }
}
