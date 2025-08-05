package com.pop.popcoinsystem.network.service;

import com.pop.popcoinsystem.network.common.NodeInfo;
import com.pop.popcoinsystem.network.protocol.message.KademliaMessage;
import com.pop.popcoinsystem.network.rpc.RequestResponseManager;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
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
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class TCPClient {


    private final ExecutorService executorService;
    private Bootstrap bootstrap;
    private NioEventLoopGroup eventLoopGroup;

    /** 节点ID到Channel的映射 */
    private final Map<BigInteger, Channel> nodeTCPChannel = new ConcurrentHashMap<>();


    // 用于在Channel中存储节点ID的属性键
    private static final AttributeKey<BigInteger> NODE_ID_KEY = AttributeKey.valueOf("NODE_ID");
    public static final int DEFAULT_CONNECT_TIMEOUT = 30000; // 30秒
    private static final int MAX_RETRY_COUNT = 2; // 减少重试次数，避免恶性循环
    private static final long RETRY_DELAY_BASE = 200; // 延长基础延迟，避免网络拥堵
    private static final int IDLE_TIMEOUT_SECONDS = 30; // 空闲连接超时时间


    public TCPClient() {
        // 使用缓存线程池，避免固定线程数导致的任务堆积
        executorService = new ThreadPoolExecutor(
                0,
                10,
                60L, TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                new ThreadFactory() {
                    private final AtomicInteger counter = new AtomicInteger(0);
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread thread = new Thread(r, "tcp-client-pool-" + counter.incrementAndGet());
                        thread.setDaemon(true); // 守护线程，避免阻塞程序退出
                        return thread;
                    }
                }
        );
        eventLoopGroup = new NioEventLoopGroup();
        bootstrap = new Bootstrap();
        bootstrap.group(eventLoopGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true) // 启用TCP保活机制
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                .handler(new ChannelInitializer<NioSocketChannel>() {
                    @Override
                    protected void initChannel(NioSocketChannel ch) throws Exception {
                        ChannelPipeline pipeline = ch.pipeline();
                        // 添加空闲检测，自动关闭长时间无活动的连接
                        pipeline.addLast(new IdleStateHandler(
                                IDLE_TIMEOUT_SECONDS,
                                IDLE_TIMEOUT_SECONDS,
                                IDLE_TIMEOUT_SECONDS,
                                TimeUnit.SECONDS
                        ));
                        // 独立编解码器，解除耦合
                        pipeline.addLast(new KademliaNodeServer.TCPKademliaMessageDecoder());
                        pipeline.addLast(new KademliaNodeServer.TCPKademliaMessageEncoder());
                        // 处理空闲事件
                        pipeline.addLast(new IdleStateHandlerAdapter());
                    }
                });
    }

    // 处理空闲连接的适配器
    private class IdleStateHandlerAdapter extends ChannelInboundHandlerAdapter {
        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            if (evt instanceof IdleStateEvent) {
                IdleStateEvent event = (IdleStateEvent) evt;
                if (event.state() == IdleState.ALL_IDLE) {
                    BigInteger nodeId = ctx.channel().attr(NODE_ID_KEY).get();
                    log.info("Node {} channel idle for {}s, closing", nodeId, IDLE_TIMEOUT_SECONDS);
                    ctx.close(); // 触发closeFuture监听器，自动清理映射
                }
            }
            super.userEventTriggered(ctx, evt);
        }
    }


    public KademliaMessage sendMessageWithResponse(KademliaMessage message)
            throws ConnectException, TimeoutException, InterruptedException, Exception {
        return sendMessageWithResponse(message, 5, TimeUnit.SECONDS);
    }

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
        Promise<KademliaMessage> promise = RequestResponseManager.sendRequest(channel, message, timeout, unit);
        try {
            // 使用不响应中断的等待，避免意外中断导致的异常
            if (!promise.awaitUninterruptibly(timeout, unit)) {
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
            // 无论成功失败，都清理请求（幂等操作）
            RequestResponseManager.clearRequest(message.getRequestId());
        }
    }


    public void sendMessage(KademliaMessage message) throws InterruptedException, ConnectException {
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
    }


    private void handleSendFailure(BigInteger nodeId, KademliaMessage message, Throwable cause) {
        // 只处理可恢复的网络错误
        if (!(cause instanceof SocketException) && !(cause instanceof ConnectException)) {
            log.warn("Unrecoverable error for node {}, no retry", nodeId, cause);
            return;
        }
        executorService.submit(() -> {
            try {
                // 双重检查：确保通道已被清理
                Channel oldChannel = nodeTCPChannel.get(nodeId);
                if (oldChannel != null && !isChannelValid(oldChannel)) {
                    nodeTCPChannel.remove(nodeId, oldChannel); // 原子操作移除
                    oldChannel.close().syncUninterruptibly();
                }

                // 重试连接并发送
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


    private Channel getOrCreateChannel(NodeInfo receiver) throws InterruptedException, ConnectException {
        BigInteger nodeId = receiver.getId();
        Channel existingChannel = nodeTCPChannel.get(nodeId);

        // 检查现有通道是否有效（增加更严格的判断）
        if (existingChannel != null) {
            if (isChannelValid(existingChannel)) {
                return existingChannel;
            } else {
                log.info("Cleaning invalid channel for node {}", nodeId);
                nodeTCPChannel.remove(nodeId, existingChannel); // 原子移除
                existingChannel.close().syncUninterruptibly();
            }
        }

        // 原子操作创建新通道，避免并发重复连接
        return nodeTCPChannel.computeIfAbsent(nodeId, key -> {
            try {
                return connectWithRetry(receiver.getIpv4(), receiver.getTcpPort(), nodeId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CompletionException("Interrupted while connecting to node: " + nodeId, e);
            } catch (ConnectException e) {
                throw new CompletionException("Failed to connect to node: " + nodeId, e);
            }
        });
    }


    private Channel connectWithRetry(String ipv4, int tcpPort, BigInteger nodeId)
            throws InterruptedException, ConnectException {
        InetSocketAddress address = new InetSocketAddress(ipv4, tcpPort);
        ConnectException lastException = null;

        for (int retry = 0; retry < MAX_RETRY_COUNT; retry++) {
            // 重试前检查是否已有其他线程成功创建通道
            Channel existing = nodeTCPChannel.get(nodeId);
            if (existing != null && isChannelValid(existing)) {
                log.info("Node {} already connected by another thread, using existing channel", nodeId);
                return existing;
            }

            try {
                return connectTarget(ipv4, tcpPort, nodeId);
            } catch (ConnectException e) {
                lastException = e;
                if (isRetryableError(e)) {
                    long delay = RETRY_DELAY_BASE * (1 << retry); // 指数退避
                    log.warn("Connection attempt {} failed for node {}, retrying in {}ms",
                            retry + 1, nodeId, delay, e);
                    Thread.sleep(delay);
                } else {
                    break; // 非重试错误直接退出
                }
            }
        }

        throw new ConnectException("Failed to connect to " + address + " after " +
                MAX_RETRY_COUNT + " retries: " + (lastException != null ? lastException.getMessage() : "Unknown error"));
    }

    private Channel connectTarget(String ipv4, int tcpPort, BigInteger nodeId)
            throws InterruptedException, ConnectException {
        InetSocketAddress address = new InetSocketAddress(ipv4, tcpPort);
        log.info("Connecting to {}:{} (node {})", ipv4, tcpPort, nodeId);

        ChannelFuture connectFuture = bootstrap.connect(address);
        Integer connectTimeout = (Integer) bootstrap.config().options().get(ChannelOption.CONNECT_TIMEOUT_MILLIS);
        int timeoutMillis = (connectTimeout != null) ? connectTimeout : DEFAULT_CONNECT_TIMEOUT;

        if (!connectFuture.awaitUninterruptibly(timeoutMillis)) {
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


    /** 更严格的通道有效性检查 */
    private boolean isChannelValid(Channel channel) {
        return channel != null
                && channel.isOpen()
                && channel.isActive()
                && channel.isWritable()
                && !channel.closeFuture().isDone(); // 确保未处于关闭流程中
    }

    private boolean isRetryableError(ConnectException e) {
        String message = e.getMessage();
        return message != null && (
                message.contains("Address already in use") ||
                        message.contains("timed out") ||
                        message.contains("Connection refused") ||
                        message.contains("reset by peer") ||
                        message.contains("no route to host")
        );
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



    @PreDestroy
    public void stop() {
        log.info("Stopping TCPClient...");

        // 关闭所有通道（带超时）
        for (Map.Entry<BigInteger, Channel> entry : nodeTCPChannel.entrySet()) {
            Channel channel = entry.getValue();
            if (channel.isOpen()) {
                try {
                    log.info("Closing channel to node {}", entry.getKey());
                    if (!channel.close().await(1, TimeUnit.SECONDS)) {
                        log.warn("Channel to node {} did not close in time", entry.getKey());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.error("Interrupted while closing channel to node {}", entry.getKey(), e);
                }
            }
        }

        // 优雅关闭EventLoopGroup
        if (eventLoopGroup != null) {
            eventLoopGroup.shutdownGracefully(1, 5, TimeUnit.SECONDS)
                    .addListener(future -> log.info("TCP EventLoopGroup shut down"));
        }

        // 关闭线程池
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
        }
        log.info("TCP executor service shut down");

        nodeTCPChannel.clear();
    }


}