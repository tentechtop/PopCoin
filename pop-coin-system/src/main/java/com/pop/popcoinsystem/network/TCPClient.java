package com.pop.popcoinsystem.network;

import com.pop.popcoinsystem.network.common.NodeInfo;
import com.pop.popcoinsystem.network.protocol.message.KademliaMessage;
import com.pop.popcoinsystem.network.protocol.message.RpcRequestMessage;
import com.pop.popcoinsystem.network.service.RequestResponseManager;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Promise;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.math.BigInteger;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.*;

@Slf4j
public class TCPClient {

    @Autowired
    private KademliaNodeServer kademliaNodeServer;

    private final ExecutorService executorService;
    private Bootstrap bootstrap;
    private NioEventLoopGroup eventLoopGroup;

    /** 节点ID到Channel的映射 */
    private final Map<BigInteger, Channel> nodeTCPChannel = new ConcurrentHashMap<>();
    /** 通道到RequestResponseManager的映射 */
    private final Map<Channel, RequestResponseManager> channelToResponseManager = new ConcurrentHashMap<>();

    // 用于在Channel中存储节点ID的属性键
    private static final AttributeKey<BigInteger> NODE_ID_KEY = AttributeKey.valueOf("NODE_ID");
    private static final int DEFAULT_CONNECT_TIMEOUT = 30000; // 30秒，与Netty默认保持一致

    public TCPClient() {
        executorService = Executors.newFixedThreadPool(10);
        // 全局复用一个EventLoopGroup，避免资源浪费
        eventLoopGroup = new NioEventLoopGroup();

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
                        pipeline.addLast(new KademliaTcpHandler(kademliaNodeServer));
                    }
                });
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
     * 发送消息并获取响应
     * @return Future对象，可通过该对象获取响应结果
     */
    /**
     * 发送消息并获取响应的Promise（异步处理）
     * @return Future对象，可通过该对象异步获取响应结果
     */
    public Promise<KademliaMessage> sendMessageWithResponsePromise(KademliaMessage message)
            throws InterruptedException, ConnectException {

        if (message == null || message.getReceiver() == null) {
            throw new IllegalArgumentException("Message or receiver cannot be null");
        }
        BigInteger nodeId = message.getReceiver().getId();
        Channel channel = getOrCreateChannel(message.getReceiver());
        if (channel == null || !channel.isActive()) {
            throw new ConnectException("No active channel available for node: " + nodeId);
        }

        // 获取通道对应的请求响应管理器
        RequestResponseManager responseManager = channelToResponseManager.computeIfAbsent(
                channel, k -> new RequestResponseManager(channel)
        );
        long messageId = message.getMessageId();
        // 创建Promise对象（绑定到Netty的EventLoop线程，确保线程安全）
        Promise<KademliaMessage> promise = new DefaultPromise<>(channel.eventLoop());
        try {
            // 注册请求到管理器，关联messageId和promise
            responseManager.registerRequest(messageId, promise);
            // 标记为请求消息
            message.setResponse(false);
            // 发送消息并添加发送结果监听
            channel.writeAndFlush(message).addListener((ChannelFutureListener) future -> {
                if (!future.isSuccess()) {
                    // 发送失败：完成promise的异常状态并清理
                    String errorMsg = "Failed to send message to node " + nodeId;
                    log.error(errorMsg, future.cause());
                    if (!promise.isDone()) {
                        promise.setFailure(new IOException(errorMsg, future.cause()));
                    }
                    responseManager.clearRequest(messageId);
                }
            });
            // 设置超时处理（默认5秒，可根据需求调整或改为参数传入）
            channel.eventLoop().schedule(() -> {
                if (!promise.isDone()) {
                    String errorMsg = "Timeout waiting for response from node " + nodeId + " (messageId: " + messageId + ")";
                    log.warn(errorMsg);
                    promise.setFailure(new TimeoutException(errorMsg));
                    responseManager.clearRequest(messageId);
                }
            }, 5, TimeUnit.SECONDS);

            // 监听通道关闭事件，提前终止等待
            channel.closeFuture().addListener(future -> {
                if (!promise.isDone()) {
                    String errorMsg = "Channel to node " + nodeId + " closed before response";
                    promise.setFailure(new IOException(errorMsg));
                    responseManager.clearRequest(messageId);
                }
            });

        } catch (Exception e) {
            // 处理注册或发送过程中的异常
            if (!promise.isDone()) {
                promise.setFailure(e);
            }
            responseManager.clearRequest(messageId);
            throw e;
        }

        return promise;
    }


    public KademliaMessage sendMessageWithResponse(KademliaMessage message)
            throws ConnectException, TimeoutException, InterruptedException, Exception {
        // 默认超时时间5秒，也可以提供重载方法让用户指定超时
        return sendMessageWithResponse((RpcRequestMessage)message, 5, TimeUnit.SECONDS);
    }

    /**
     * 重载方法：允许用户指定超时时间
     */
    public KademliaMessage sendMessageWithResponse(RpcRequestMessage message, long timeout, TimeUnit unit)
            throws ConnectException, TimeoutException, InterruptedException, Exception {
        if (message == null || message.getReceiver() == null) {
            throw new IllegalArgumentException("消息或接收者不能为空");
        }
        message.setRequestId(message.getMessageId());
        BigInteger nodeId = message.getReceiver().getId();
        Channel channel = getOrCreateChannel(message.getReceiver());
        if (channel == null || !channel.isActive()) {
            throw new ConnectException("节点 " + nodeId + " 无可用连接");
        }
        // 获取通道对应的请求响应管理器（内部使用Promise）
        RequestResponseManager responseManager = channelToResponseManager.computeIfAbsent(
                channel, k -> new RequestResponseManager(channel)
        );
        // 标记为请求消息（非响应）
        message.setResponse(false);
        // 发送请求并获取Promise（内部异步处理）
        Promise<KademliaMessage> promise = responseManager.sendRequest(channel, message, timeout, unit);
        try {
            // 阻塞等待结果（核心：将异步转为同步，对外屏蔽Promise）
            // 这里使用await()而非get()，避免检查异常包装
            if (!promise.await(timeout, unit)) {
                // 超时：主动取消并抛出超时异常
                promise.cancel(false);
                throw new TimeoutException("等待节点 " + nodeId + " 响应超时（" + timeout + unit + "）");
            }
            // 检查结果状态
            if (promise.isSuccess()) {
                // 成功：直接返回响应结果（用户拿到的就是最终数据）
                return promise.getNow();
            } else {
                // 失败：抛出具体异常（如连接断开、消息处理失败等）
                Throwable cause = promise.cause();
                if (cause instanceof Exception) {
                    throw (Exception) cause;
                } else {
                    throw new Exception("发送消息失败：" + cause.getMessage(), cause);
                }
            }
        } finally {
            // 清理：如果消息处理完成，从管理器中移除（避免内存泄漏）
            if (promise.isDone()) {
                responseManager.clearRequest(message.getMessageId());
            }
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
        log.info("Successfully connected to {}:{} (node {})", ipv4, tcpPort, nodeId);
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
}
