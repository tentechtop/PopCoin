package com.pop.popcoinsystem.network.service;

import com.pop.popcoinsystem.network.common.NodeInfo;
import com.pop.popcoinsystem.network.protocol.message.KademliaMessage;
import com.pop.popcoinsystem.network.rpc.RequestResponseManager;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.Promise;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

import java.math.BigInteger;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class UDPClient {
    private final KademliaNodeServer kademliaNodeServer;
    private final ExecutorService executorService;
    private Bootstrap bootstrap;
    private NioEventLoopGroup eventLoopGroup;
    /** 节点ID到Channel的映射 */
    private final Map<BigInteger, Channel> nodeUDPChannel = new ConcurrentHashMap<>();
    /** 用于在Channel中存储节点ID的属性键 */
    private static final AttributeKey<BigInteger> NODE_ID_KEY = AttributeKey.valueOf("NODE_ID");
    private static final int DEFAULT_OPERATION_TIMEOUT = 5000; // 默认操作超时（毫秒）


    public UDPClient(KademliaNodeServer kademliaNodeServer) {
        this.kademliaNodeServer = kademliaNodeServer;
        // 线程池复用，控制并发量
        executorService = Executors.newVirtualThreadPerTaskExecutor();
        // 全局复用EventLoopGroup（重量级资源，避免频繁创建）
        this.eventLoopGroup = new NioEventLoopGroup();
        // 初始化Bootstrap并复用配置
        this.bootstrap = new Bootstrap();
        bootstrap.group(eventLoopGroup)
                .channel(NioDatagramChannel.class) // UDP通道类型
                .option(ChannelOption.SO_BROADCAST, true)
                // 新增：允许端口复用（解决TIME_WAIT导致的端口占用）
                .option(ChannelOption.SO_REUSEADDR, true)
                // 新增：设置接收缓冲区大小，减少因缓冲区满导致的隐性失败
                .option(ChannelOption.SO_RCVBUF, 1024 * 1024)
                .handler(new ChannelInitializer<NioDatagramChannel>() {
                    @Override
                    protected void initChannel(NioDatagramChannel ch) throws Exception {
                        ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast(new KademliaNodeServer.TCPKademliaMessageDecoder());
                        pipeline.addLast(new KademliaNodeServer.TCPKademliaMessageEncoder());
                    }
                });
    }


    /**
     * 同步发送UDP消息（确保线程安全和通道有效性）
     */
    /**
     * 同步发送UDP消息，使用虚拟线程处理等待操作
     */
    public void sendMessage(KademliaMessage message) {
        // 使用虚拟线程执行可能阻塞的操作，避免阻塞平台线程
        executorService.submit(() -> {
            try {
                if (message == null || message.getReceiver() == null) {
                    throw new IllegalArgumentException("Message or receiver cannot be null");
                }
                NodeInfo receiver = message.getReceiver();
                BigInteger nodeId = receiver.getId();
                InetSocketAddress targetAddr = new InetSocketAddress(receiver.getIpv4(), receiver.getUdpPort());

                // 获取或创建有效的UDP通道
                Channel channel = getOrCreateChannel(nodeId, targetAddr);
                if (channel == null || !channel.isOpen()) {
                    throw new ConnectException("No valid UDP channel for node: " + nodeId);
                }

                // 发送消息并添加结果监听
                ChannelFuture future = channel.writeAndFlush(message);
                future.addListener((ChannelFutureListener) f -> {
                    if (!f.isSuccess()) {
                        log.error("Failed to send UDP message to node {}: {}", nodeId, f.cause().getMessage());
                        // 发送失败时在虚拟线程中处理重试逻辑
                        //下线节点
                        kademliaNodeServer.offlineNode(nodeId);
                    }
                });

                // 等待发送操作完成（在虚拟线程中阻塞不会消耗平台线程资源）
                if (!future.await(DEFAULT_OPERATION_TIMEOUT, TimeUnit.MILLISECONDS)) {
                    throw new ConnectException("UDP send to node " + nodeId + " timed out");
                }
                if (!future.isSuccess()) {
                    throw new ConnectException("UDP send failed: " + future.cause().getMessage());
                }
            } catch (Exception e) {
                log.error("Failed to send UDP message: {}", e.getMessage());
            }
        });
    }


    /**
     * 发送消息并等待响应，使用虚拟线程处理阻塞等待
     */
    public CompletableFuture<KademliaMessage> sendMessageWithResponse(KademliaMessage message) {
        // 返回CompletableFuture，让调用者可以灵活处理异步结果
        return CompletableFuture.supplyAsync(() -> {
            try {
                return sendMessageWithResponse(message, 5, TimeUnit.SECONDS);;
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, executorService);
    }



    public KademliaMessage sendMessageWithResponse(KademliaMessage message, long timeout, TimeUnit unit)
            throws ConnectException, TimeoutException, InterruptedException, Exception {
        if (message == null || message.getReceiver() == null) {
            throw new IllegalArgumentException("消息或接收者不能为空");
        }
        //请求ID已经在消息创建阶段设置
        long requestId = message.getRequestId();
        NodeInfo receiver = message.getReceiver();
        BigInteger nodeId = receiver.getId();
        InetSocketAddress targetAddr = new InetSocketAddress(receiver.getIpv4(), receiver.getUdpPort());
        Channel channel = getOrCreateChannel(nodeId, targetAddr);
        if (channel == null || !channel.isActive()) {
            throw new ConnectException("节点 " + nodeId + " 无可用连接");
        }

        // 标记为请求消息（非响应）
        message.setResponse(false);
        // 发送请求并获取Promise（内部异步处理）
        Promise<KademliaMessage> promise = RequestResponseManager.sendRequest(channel, message, timeout, unit);
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
                RequestResponseManager.clearRequest(message.getRequestId());
            }
        }
    }


    /**
     * 处理发送失败（尝试重建通道并重发）
     */
    private void handleSendFailure(BigInteger nodeId, InetSocketAddress targetAddr, KademliaMessage message) {
        try {
            // 移除并关闭旧通道（同步等待关闭完成，确保端口释放）
            Channel oldChannel = nodeUDPChannel.remove(nodeId);
            if (oldChannel != null && oldChannel.isOpen()) {
                log.info("Closing invalid UDP channel for node {}", nodeId);
                oldChannel.close().sync(); // 同步关闭，确保端口释放
            }
            // 重建通道并重发
            Channel newChannel = createAndConnectChannel(nodeId, targetAddr);
            nodeUDPChannel.put(nodeId, newChannel);
            newChannel.writeAndFlush(message).addListener((ChannelFutureListener) f -> {
                if (f.isSuccess()) {
                    log.info("Resent UDP message to node {} after failure", nodeId);
                } else {
                    log.error("Failed to resend UDP message to node {}", nodeId, f.cause());
                }
            });
        } catch (Exception e) {
            log.error("Failed to recover UDP channel for node {}", nodeId, e);
        }
    }


    /**
     * 原子操作：获取已存在的有效通道或创建新通道
     */
    private Channel getOrCreateChannel(BigInteger nodeId, InetSocketAddress targetAddr) throws InterruptedException, ConnectException {
        // 检查现有通道是否有效
        Channel existingChannel = nodeUDPChannel.get(nodeId);
        if (existingChannel != null) {
            if (existingChannel.isOpen() && isChannelConnectedTo(existingChannel, targetAddr)) {
                return existingChannel;
            } else {
                // 无效通道立即关闭并清理（同步等待）
                log.info("Cleaning up invalid UDP channel for node {}", nodeId);
                nodeUDPChannel.remove(nodeId);
                if (existingChannel.isOpen()) {
                    existingChannel.close().sync(); // 确保端口释放
                }
            }
        }

        // 原子操作创建新通道
        return nodeUDPChannel.computeIfAbsent(nodeId, key -> {
            try {
                return createAndConnectChannel(nodeId, targetAddr);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while creating UDP channel for node: " + nodeId, e);
            } catch (ConnectException e) {
                throw new RuntimeException("Failed to create UDP channel for node: " + nodeId, e);
            }
        });
    }


    /**
     * 创建UDP通道并通过connect绑定默认目标地址（UDP的connect仅设置默认目标，非真正连接）
     */
    private Channel createAndConnectChannel(BigInteger nodeId, InetSocketAddress targetAddr) throws InterruptedException, ConnectException {
        log.info("Creating UDP channel to {} (node {})", targetAddr, nodeId);
        int maxRetries = 3; // 最大重试次数
        long retryDelayMs = 100; // 初始重试延迟

        for (int retry = 0; retry < maxRetries; retry++) {
            try {
                // 绑定本地临时端口（0表示随机分配）
                ChannelFuture bindFuture = bootstrap.bind(0);
                if (!bindFuture.await(DEFAULT_OPERATION_TIMEOUT, TimeUnit.MILLISECONDS)) {
                    throw new ConnectException("UDP bind timed out for node: " + nodeId);
                }
                if (!bindFuture.isSuccess()) {
                    // 提取底层异常判断是否为端口占用
                    Throwable cause = bindFuture.cause();
                    if (cause instanceof java.net.BindException && cause.getMessage().contains("Address already in use")
                            && retry < maxRetries - 1) {
                        // 端口占用且未达最大重试次数，等待后重试
                        log.warn("Port busy, retrying bind for node {} (retry {})", nodeId, retry + 1);
                        Thread.sleep(retryDelayMs * (retry + 1)); // 指数退避
                        continue;
                    }
                    throw new ConnectException("UDP bind failed: " + cause.getMessage());
                }

                Channel channel = bindFuture.channel();
                // 绑定默认目标地址（UDP的connect仅设置默认目标）
                ChannelFuture connectFuture = channel.connect(targetAddr);
                if (!connectFuture.await(DEFAULT_OPERATION_TIMEOUT, TimeUnit.MILLISECONDS)) {
                    channel.close().sync(); // 确保绑定的端口被释放
                    throw new ConnectException("UDP connect timed out (node " + nodeId + ")");
                }
                if (!connectFuture.isSuccess()) {
                    channel.close().sync(); // 释放端口
                    throw new ConnectException("UDP connect failed: " + connectFuture.cause().getMessage());
                }

                // 存储节点ID与通道关联
                channel.attr(NODE_ID_KEY).set(nodeId);
                // 通道关闭时清理映射（同步等待关闭完成）
                channel.closeFuture().addListener((ChannelFutureListener) future -> {
                    log.info("UDP channel to node {} closed", nodeId);
                    nodeUDPChannel.remove(nodeId);
                });

                log.info("UDP channel created for node {} (target: {})", nodeId, targetAddr);
                return channel;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }
        }

        throw new ConnectException("Failed to bind UDP port after " + maxRetries + " retries (node " + nodeId + ")");
    }


    /**
     * 检查UDP通道是否关联到目标地址（UDP的connect只是设置默认目标）
     */
    private boolean isChannelConnectedTo(Channel channel, InetSocketAddress targetAddr) {
        try {
            // 获取UDP通道的默认目标地址
            InetSocketAddress remoteAddr = (InetSocketAddress) channel.remoteAddress();
            return remoteAddr != null && remoteAddr.equals(targetAddr);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 异步发送消息
     * @param message
     */
    public  void sendAsyncMessage(KademliaMessage message) {
        executorService.submit(() -> {
            try {
                sendMessage(message);
            } catch (Exception ignored) {}
        });
    }

    /**
     * 优雅关闭所有资源（避免资源泄漏）
     */
    @PreDestroy
    public void stop() {
        log.info("Stopping UDPClient...");

        // 关闭所有活跃通道（同步等待）
        for (Channel channel : nodeUDPChannel.values()) {
            if (channel.isOpen()) {
                try {
                    channel.close().sync(); // 同步关闭，确保端口释放
                    log.info("UDP channel closed: {}", channel.remoteAddress());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.error("Interrupted while closing UDP channel", e);
                }
            }
        }

        // 关闭EventLoopGroup
        eventLoopGroup.shutdownGracefully(1, 5, TimeUnit.SECONDS)
                .addListener(future -> log.info("UDP EventLoopGroup shut down"));

        // 关闭线程池
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
        }
        log.info("UDP executor service shut down");

        nodeUDPChannel.clear();
    }

    /**
     * 移除并关闭指定节点ID对应的通道
     * 确保并发安全，避免重复关闭或移除无效通道
     * @param nodeId 待移除通道的节点ID
     */
    public void removeChannel(BigInteger nodeId) {
        if (nodeId == null) {
            log.warn("尝试移除通道时节点ID为null，忽略操作");
            return;
        }
        // 从映射中原子性移除通道（避免其他线程同时操作）
        Channel channel = nodeUDPChannel.remove(nodeId);
        if (channel != null) {
            // 异步关闭通道，不阻塞调用线程
            channel.close().addListener((ChannelFutureListener) future -> {
                if (future.isSuccess()) {
                    log.info("成功移除并关闭节点 {} 的通道", nodeId);
                } else {
                    log.error("移除节点 {} 的通道时关闭失败", nodeId, future.cause());
                }
            });
        } else {
            log.debug("节点 {} 不存在有效通道，无需移除", nodeId);
        }
    }
}
