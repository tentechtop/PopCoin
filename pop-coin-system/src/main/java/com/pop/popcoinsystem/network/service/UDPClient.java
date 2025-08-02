package com.pop.popcoinsystem.network.service;

import com.pop.popcoinsystem.network.common.NodeInfo;
import com.pop.popcoinsystem.network.protocol.message.KademliaMessage;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.util.AttributeKey;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

import java.math.BigInteger;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Slf4j
public class UDPClient {
    private final ExecutorService executorService;
    private Bootstrap bootstrap;
    private NioEventLoopGroup eventLoopGroup;
    /** 节点ID到Channel的映射 */
    private final Map<BigInteger, Channel> nodeUDPChannel = new ConcurrentHashMap<>();
    /** 用于在Channel中存储节点ID的属性键 */
    private static final AttributeKey<BigInteger> NODE_ID_KEY = AttributeKey.valueOf("NODE_ID");
    private static final int DEFAULT_OPERATION_TIMEOUT = 5000; // 默认操作超时（毫秒）

    public UDPClient() {
        // 线程池复用，控制并发量
        this.executorService = Executors.newFixedThreadPool(10);
        // 全局复用EventLoopGroup（重量级资源，避免频繁创建）
        this.eventLoopGroup = new NioEventLoopGroup();
        // 初始化Bootstrap并复用配置
        this.bootstrap = new Bootstrap();
        bootstrap.group(eventLoopGroup)
                .channel(NioDatagramChannel.class) // UDP通道类型
                .option(ChannelOption.SO_BROADCAST, true)
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
    public  void sendMessage(KademliaMessage message)  {
        try {
            if (message == null || message.getReceiver() == null) {
                throw new IllegalArgumentException("Message or receiver cannot be null");
            }
            NodeInfo receiver = message.getReceiver();
            BigInteger nodeId = receiver.getId();
            InetSocketAddress targetAddr = new InetSocketAddress(receiver.getIpv4(), receiver.getUdpPort());

            // 获取或创建有效的UDP通道（原子操作避免竞态条件）
            Channel channel = getOrCreateChannel(nodeId, targetAddr);
            if (channel == null || !channel.isOpen()) { // UDP通道用isOpen()判断有效性（无连接状态）
                throw new ConnectException("No valid UDP channel for node: " + nodeId);
            }
            // 发送消息并添加结果监听
            ChannelFuture future = channel.writeAndFlush(message);
            future.addListener((ChannelFutureListener) f -> {
                if (!f.isSuccess()) {
                    log.error("Failed to send UDP message to node {}: {}", nodeId, f.cause().getMessage());
                    handleSendFailure(nodeId, targetAddr, message); // 处理发送失败
                }
            });
            // 等待发送操作完成（避免异步丢失异常）
            if (!future.await(DEFAULT_OPERATION_TIMEOUT, TimeUnit.MILLISECONDS)) {
                throw new ConnectException("UDP send to node " + nodeId + " timed out");
            }
            if (!future.isSuccess()) {
                throw new ConnectException("UDP send failed: " + future.cause().getMessage());
            }
        }catch (Exception e){
            log.error("Failed to send UDP message: {}", e.getMessage());
        }
    }


    /**
     * 处理发送失败（尝试重建通道并重发）
     */
    private void handleSendFailure(BigInteger nodeId, InetSocketAddress targetAddr, KademliaMessage message) {
        try {
            // 移除无效通道
            Channel oldChannel = nodeUDPChannel.remove(nodeId);
            if (oldChannel != null && oldChannel.isOpen()) {
                oldChannel.close();
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
        if (existingChannel != null && existingChannel.isOpen() && isChannelConnectedTo(existingChannel, targetAddr)) {
            return existingChannel;
        }
        // 原子操作创建新通道（避免并发重复创建）
        return nodeUDPChannel.computeIfAbsent(nodeId, key -> {
            try {
                return createAndConnectChannel(nodeId, targetAddr);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // 恢复中断状态
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

        // 绑定本地临时端口（UDP客户端通常不需要固定本地端口）
        ChannelFuture bindFuture = bootstrap.bind(0); // 0表示随机端口
        if (!bindFuture.await(DEFAULT_OPERATION_TIMEOUT, TimeUnit.MILLISECONDS)) {
            throw new ConnectException("UDP bind timed out for node: " + nodeId);
        }
        if (!bindFuture.isSuccess()) {
            throw new ConnectException("UDP bind failed: " + bindFuture.cause().getMessage());
        }

        Channel channel = bindFuture.channel();
        // 为UDP通道设置默认目标地址（类似TCP的connect，简化发送逻辑）
        ChannelFuture connectFuture = channel.connect(targetAddr);
        if (!connectFuture.await(DEFAULT_OPERATION_TIMEOUT, TimeUnit.MILLISECONDS)) {
            channel.close(); // 绑定成功但连接超时，需要关闭通道
            throw new ConnectException("UDP connect to " + targetAddr + " timed out (node " + nodeId + ")");
        }
        if (!connectFuture.isSuccess()) {
            channel.close(); // 连接失败，关闭通道
            throw new ConnectException("UDP connect failed: " + connectFuture.cause().getMessage());
        }

        // 存储节点ID与通道的关联
        channel.attr(NODE_ID_KEY).set(nodeId);
        // 监听通道关闭事件，自动清理映射
        channel.closeFuture().addListener(future -> {
            log.info("UDP channel to node {} closed", nodeId);
            nodeUDPChannel.remove(nodeId);
        });

        log.info("UDP channel created for node {} (target: {})", nodeId, targetAddr);
        return channel;
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

        // 关闭所有活跃通道
        nodeUDPChannel.values().forEach(channel -> {
            if (channel.isOpen()) {
                channel.close().addListener(future ->
                        log.info("UDP channel closed: {}", channel.remoteAddress())
                );
            }
        });

        // 关闭EventLoopGroup（优雅退出IO线程）
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

        // 清空映射表
        nodeUDPChannel.clear();
    }


}
