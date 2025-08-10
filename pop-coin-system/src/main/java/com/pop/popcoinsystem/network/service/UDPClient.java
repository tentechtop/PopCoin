package com.pop.popcoinsystem.network.service;

import com.pop.popcoinsystem.network.common.NodeInfo;
import com.pop.popcoinsystem.network.protocol.message.KademliaMessage;
import com.pop.popcoinsystem.network.rpc.RequestResponseManager;
import com.pop.popcoinsystem.util.SerializeUtils;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramPacket;
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
    // 全局唯一UDP通道，替代原有的nodeUDPChannel映射
    private Channel globalChannel;
    private ChannelFuture globalChannelFuture;

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
                .option(ChannelOption.SO_REUSEADDR, true) // 允许端口复用
                .option(ChannelOption.SO_RCVBUF, 1024 * 1024)
                .handler(new ChannelInitializer<NioDatagramChannel>() {
                    @Override
                    protected void initChannel(NioDatagramChannel ch) throws Exception {
                        ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast(new KademliaNodeServer.UDPKademliaMessageDecoder());
                        pipeline.addLast(new KademliaNodeServer.UDPKademliaMessageEncoder());
                    }
                });
        // 初始化全局通道（绑定一次本地临时端口）
        try {
            ChannelFuture bindFuture = bootstrap.bind(0); // 0表示随机分配一个本地端口
            globalChannelFuture = bindFuture;
            if (!bindFuture.await(DEFAULT_OPERATION_TIMEOUT)) {
                throw new IllegalStateException("Failed to bind UDP channel");
            }
            this.globalChannel = bindFuture.channel();
            log.info("Global UDP channel bound to local port: {}", globalChannel.localAddress());
        } catch (Exception e) {
            log.error("Failed to initialize global UDP channel", e);
            throw new RuntimeException(e);
        }
    }

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

                // 发送消息（通过DatagramPacket指定目标地址）
                ChannelFuture future = globalChannel.writeAndFlush(new DatagramPacket(
                        Unpooled.copiedBuffer(SerializeUtils.serialize(message)),
                        targetAddr
                ));
                future.addListener((ChannelFutureListener) f -> {
                    if (!f.isSuccess()) {
                        log.error("Failed to send UDP message to {}: {}", targetAddr, f.cause().getMessage());
                        kademliaNodeServer.offlineNode(receiver.getId());
                    }
                });
                if (!future.await(DEFAULT_OPERATION_TIMEOUT)) {
                    throw new ConnectException("UDP send to " + targetAddr + " timed out");
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
                return sendMessageWithResponse(message, 5, TimeUnit.SECONDS);
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

        // 发送消息（通过DatagramPacket指定目标地址）
        ChannelFuture future = globalChannel.writeAndFlush(new DatagramPacket(
                Unpooled.copiedBuffer(SerializeUtils.serialize(message)),
                targetAddr
        ));
        future.addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess()) {
                log.error("Failed to send UDP message to {}: {}", targetAddr, f.cause().getMessage());
                kademliaNodeServer.offlineNode(receiver.getId());
            }
        });
        // 标记为请求消息（非响应）
        message.setResponse(false);
        // 发送请求并获取Promise（内部异步处理）
        Promise<KademliaMessage> promise = RequestResponseManager.sendRequest(globalChannel, message, timeout, unit);
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
    }

}
