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
import java.net.SocketException;
import java.util.Map;
import java.util.concurrent.*;

@Slf4j
public class TCPClient {


    private final ExecutorService executorService;
    private Bootstrap bootstrap;
    private NioEventLoopGroup eventLoopGroup;

    /** 节点ID到Channel的映射 */
    private final Map<BigInteger, Channel> nodeTCPChannel = new ConcurrentHashMap<>();

    private RequestResponseManager responseManager;

    // 用于在Channel中存储节点ID的属性键
    private static final AttributeKey<BigInteger> NODE_ID_KEY = AttributeKey.valueOf("NODE_ID");
    public static final int DEFAULT_CONNECT_TIMEOUT = 30000; // 30秒，与Netty默认保持一致
    private static final int MAX_RETRY_COUNT = 3; // 连接重试次数
    private static final long RETRY_DELAY_BASE = 100; // 重试基础延迟(ms)


    public TCPClient(RequestResponseManager requestResponseManager) {
        executorService = Executors.newFixedThreadPool(10);
        // 全局复用一个EventLoopGroup，避免资源浪费
        eventLoopGroup = new NioEventLoopGroup();
        responseManager = requestResponseManager;

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

        BigInteger nodeId = message.getReceiver().getId();
        Channel channel = getOrCreateChannel(message.getReceiver());
        if (channel == null || !isChannelValid(channel)) {
            throw new ConnectException("节点 " + nodeId + " 无可用连接");
        }

        message.setResponse(false);
        Promise<KademliaMessage> promise = responseManager.sendRequest(channel, message, timeout, unit);
        try {
            if (!promise.await(timeout, unit)) {
                promise.cancel(false);
                throw new TimeoutException("等待节点 " + nodeId + " 响应超时（" + timeout +" "+ unit + "）");
            }
            if (promise.isSuccess()) {
                return promise.getNow();
            } else {
                Throwable cause = promise.cause();
                if (cause instanceof Exception) {
                    throw (Exception) cause;
                } else {
                    throw new Exception("发送消息失败：" + cause.getMessage(), cause);
                }
            }
        } finally {
            if (promise.isDone()) {
                responseManager.clearRequest(message.getRequestId());
            }
        }
    }


    public void sendMessage(KademliaMessage message) throws InterruptedException, ConnectException {
        try {
            if (message == null || message.getReceiver() == null) {
                throw new IllegalArgumentException("Message or receiver cannot be null");
            }
            BigInteger nodeId = message.getReceiver().getId();
            Channel channel = getOrCreateChannel(message.getReceiver());
            if (channel != null && isChannelValid(channel)) {
                channel.writeAndFlush(message).addListener((ChannelFutureListener) future -> {
                    if (!future.isSuccess()) {
                        log.error("Failed to send message to node {}: {}", nodeId, future.cause().getMessage());
                        handleSendFailure(nodeId, message, future.cause());
                    }
                });
            } else {
                throw new ConnectException("No active channel available for node: " + nodeId);
            }
        } catch (Exception e) {
            log.error("Failed to send message: {}", e.getMessage());
        }
    }



    /**
     * 处理消息发送失败的情况
     */
    /**
     * 处理发送失败，增加智能重试策略
     */
    private void handleSendFailure(BigInteger nodeId, KademliaMessage message, Throwable cause) {
        // 只处理可恢复的错误（如连接重置、超时等）
        if (!(cause instanceof SocketException) && !(cause instanceof ConnectException)) {
            log.warn("Unrecoverable error for node {}, no retry", nodeId, cause);
            return;
        }

        executorService.submit(() -> {
            try {
                // 强制关闭旧通道并清理
                Channel oldChannel = nodeTCPChannel.remove(nodeId);
                if (oldChannel != null) {
                    log.info("Closing faulty channel for node {}", nodeId);
                    oldChannel.close().syncUninterruptibly(); // 同步关闭确保资源释放
                }

                // 带重试机制重建连接
                NodeInfo receiver = message.getReceiver();
                Channel newChannel = connectWithRetry(
                        receiver.getIpv4(),
                        receiver.getTcpPort(),
                        nodeId
                );
                if (newChannel != null) {
                    nodeTCPChannel.put(nodeId, newChannel);
                    newChannel.writeAndFlush(message).addListener((ChannelFutureListener) f -> {
                        if (f.isSuccess()) {
                            log.info("Resent message to node {} after failure", nodeId);
                        } else {
                            log.error("Final retry failed for node {}", nodeId, f.cause());
                            nodeTCPChannel.remove(nodeId);
                        }
                    });
                }
            } catch (Exception e) {
                log.error("Failed to recover connection for node {}", nodeId, e);
            }
        });
    }


    /**
     * 获取或创建通道，增强原子性和资源清理
     */
    private Channel getOrCreateChannel(NodeInfo receiver) throws InterruptedException, ConnectException {
        BigInteger nodeId = receiver.getId();
        Channel existingChannel = nodeTCPChannel.get(nodeId);

        // 检查现有通道是否有效
        if (existingChannel != null) {
            if (isChannelValid(existingChannel)) {
                return existingChannel;
            } else {
                // 无效通道立即清理
                log.info("Cleaning invalid channel for node {}", nodeId);
                nodeTCPChannel.remove(nodeId);
                existingChannel.close().syncUninterruptibly(); // 同步关闭
            }
        }

        // 原子操作创建新通道，避免并发重复连接
        return nodeTCPChannel.computeIfAbsent(nodeId, key -> {
            try {
                return connectWithRetry(receiver.getIpv4(), receiver.getTcpPort(), nodeId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while connecting to node: " + nodeId, e);
            } catch (ConnectException e) {
                throw new RuntimeException("Failed to connect to node: " + nodeId, e);
            }
        });
    }



    /**
     * 带重试机制的连接方法
     */
    private Channel connectWithRetry(String ipv4, int tcpPort, BigInteger nodeId)
            throws InterruptedException, ConnectException {
        InetSocketAddress address = new InetSocketAddress(ipv4, tcpPort);
        ConnectException lastException = null;

        for (int retry = 0; retry < MAX_RETRY_COUNT; retry++) {
            try {
                return connectTarget(ipv4, tcpPort, nodeId);
            } catch (ConnectException e) {
                lastException = e;
                // 只对特定错误重试（如地址占用、连接超时）
                if (isRetryableError(e)) {
                    long delay = RETRY_DELAY_BASE * (1 << retry); // 指数退避
                    log.warn("Connection attempt {} failed for node {}, retrying in {}ms",
                            retry + 1, nodeId, delay, e);
                    Thread.sleep(delay);
                } else {
                    // 非重试错误直接抛出
                    break;
                }
            }
        }

        throw new ConnectException("Failed to connect to " + address + " after " +
                MAX_RETRY_COUNT + " retries: " + lastException.getMessage());
    }

    /**
     * 实际连接目标节点
     */
    private Channel connectTarget(String ipv4, int tcpPort, BigInteger nodeId)
            throws InterruptedException, ConnectException {
        InetSocketAddress address = new InetSocketAddress(ipv4, tcpPort);
        log.info("Connecting to {}:{} (node {})", ipv4, tcpPort, nodeId);

        ChannelFuture connectFuture = bootstrap.connect(address);
        Integer connectTimeout = (Integer) bootstrap.config().options().get(ChannelOption.CONNECT_TIMEOUT_MILLIS);
        int timeoutMillis = (connectTimeout != null) ? connectTimeout : DEFAULT_CONNECT_TIMEOUT;

        if (!connectFuture.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
            throw new ConnectException("Connection to " + address + " timed out after " + timeoutMillis + "ms");
        }

        if (!connectFuture.isSuccess()) {
            String errorMsg = "Failed to connect to " + address;
            log.error(errorMsg, connectFuture.cause());
            throw new ConnectException(errorMsg + ": " + connectFuture.cause().getMessage());
        }

        Channel channel = connectFuture.channel();
        channel.attr(NODE_ID_KEY).set(nodeId);
        log.info("Successfully connected to {}:{} (node {})", ipv4, tcpPort, nodeId);

        // 监听通道关闭，确保映射表同步清理
        channel.closeFuture().addListener((ChannelFutureListener) future -> {
            log.info("Channel to node {} closed", nodeId);
            nodeTCPChannel.remove(nodeId);
        });

        return channel;
    }




    /**
     * 检查通道是否有效（TCP需同时满足活跃和可写）
     */
    private boolean isChannelValid(Channel channel) {
        return channel.isOpen() && channel.isActive() && channel.isWritable();
    }

    /**
     * 判断错误是否可重试
     */
    private boolean isRetryableError(ConnectException e) {
        String message = e.getMessage();
        return message.contains("Address already in use") ||
                message.contains("timed out") ||
                message.contains("Connection refused") ||
                message.contains("reset by peer");
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
     * 优雅关闭所有资源，确保端口和连接释放
     */
    @PreDestroy
    public void stop() {
        log.info("Stopping TCPClient...");

        // 同步关闭所有通道
        for (Map.Entry<BigInteger, Channel> entry : nodeTCPChannel.entrySet()) {
            Channel channel = entry.getValue();
            if (channel.isOpen()) {
                try {
                    log.info("Closing channel to node {}", entry.getKey());
                    channel.close().sync(); // 同步等待关闭完成
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.error("Interrupted while closing channel to node {}", entry.getKey(), e);
                }
            }
        }

        // 关闭EventLoopGroup
        if (eventLoopGroup != null) {
            eventLoopGroup.shutdownGracefully(1, 5, TimeUnit.SECONDS)
                    .addListener(future -> log.info("TCP EventLoopGroup shut down"));
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
            log.info("TCP executor service shut down");
        }

        nodeTCPChannel.clear();
    }


    public NioEventLoopGroup getEventLoopGroup() {
        return eventLoopGroup;
    }


    public RequestResponseManager getResponseManager() {
        return responseManager;
    }
}
