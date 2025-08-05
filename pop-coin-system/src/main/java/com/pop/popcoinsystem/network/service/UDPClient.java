package com.pop.popcoinsystem.network.service;

import com.pop.popcoinsystem.exception.ChannelInvalidException;
import com.pop.popcoinsystem.exception.ResponseTimeoutException;
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
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

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
    private static final int MAX_RETRY_COUNT = 2; // 最大重试次数
    private static final long RETRY_DELAY_BASE = 200; // 重试基础延迟（毫秒）
    private static final int IDLE_TIMEOUT_SECONDS = 60; // 空闲连接清理间隔


    /** 定期清理无效连接的定时任务 */
    private final ScheduledExecutorService cleanerExecutor = Executors.newSingleThreadScheduledExecutor();

    public UDPClient() {
        // 线程池复用，控制并发量
        this.executorService =new ThreadPoolExecutor(
                Runtime.getRuntime().availableProcessors() * 2,
                200,
                60L, TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                new ThreadFactory() {
                    private final AtomicInteger counter = new AtomicInteger();
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "business-pool-" + counter.incrementAndGet());
                        t.setDaemon(true);
                        return t;
                    }
                },
                new ThreadPoolExecutor.CallerRunsPolicy() // 任务满时让调用者处理，避免任务丢失
        );
        this.eventLoopGroup = new NioEventLoopGroup();
        // 初始化Bootstrap并复用配置
        this.bootstrap = new Bootstrap();
        bootstrap.group(eventLoopGroup)
                .channel(NioDatagramChannel.class) // UDP通道类型
                .option(ChannelOption.SO_BROADCAST, true)
                .option(ChannelOption.SO_REUSEADDR, true) // 允许端口复用
                .option(ChannelOption.SO_RCVBUF, 10* 1024 * 1024) // 接收缓冲区1MB
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                .handler(new ChannelInitializer<NioDatagramChannel>() {
                    @Override
                    protected void initChannel(NioDatagramChannel ch) throws Exception {
                        ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast(new KademliaNodeServer.TCPKademliaMessageDecoder());
                        pipeline.addLast(new KademliaNodeServer.TCPKademliaMessageEncoder());
                    }
                });

        // 6. 启动定期清理任务（每30秒执行一次）
        cleanerExecutor.scheduleAtFixedRate(this::cleanInvalidChannels, 30, 30, TimeUnit.SECONDS);
    }


    /**
     * 定期清理无效UDP通道（非阻塞操作）
     */
    private void cleanInvalidChannels() {
        List<Map.Entry<BigInteger, Channel>> invalidEntries = new ArrayList<>();
        // 先收集无效通道，避免遍历中修改map
        for (Map.Entry<BigInteger, Channel> entry : nodeUDPChannel.entrySet()) {
            if (!isChannelValid(entry.getValue())) {
                invalidEntries.add(entry);
            }
        }
        // 批量关闭并移除
        for (Map.Entry<BigInteger, Channel> entry : invalidEntries) {
            BigInteger nodeId = entry.getKey();
            Channel channel = entry.getValue();
            if (nodeUDPChannel.remove(nodeId, channel)) {
                log.info("Cleaned invalid UDP channel for node {}", nodeId);
                channel.close().addListener(future ->
                        log.debug("Closed invalid UDP channel for node {}", nodeId)
                ); // 异步关闭，不阻塞
            }
        }
    }


    /**
     * UDP通道有效性检查（适配无连接特性）
     */
    private boolean isChannelValid(Channel channel) {
        return channel != null
                && channel.isOpen()
                && channel.isWritable()
                && !channel.closeFuture().isDone();
    }


    /**
     * 同步发送UDP消息（优化版：确保线程安全和通道有效性）
     */
    /**
     * 同步发送UDP消息（修复版：使用ChannelFuture.await()实现超时控制）
     */
    public void sendMessage(KademliaMessage message) throws Exception {
        if (message == null || message.getReceiver() == null) {
            throw new IllegalArgumentException("Message or receiver cannot be null");
        }
        NodeInfo receiver = message.getReceiver();
        BigInteger nodeId = receiver.getId();
        InetSocketAddress targetAddr = new InetSocketAddress(receiver.getIpv4(), receiver.getUdpPort());

        // 获取或创建有效通道
        Channel channel = getOrCreateChannel(nodeId, targetAddr);
        if (!isChannelValid(channel)) {
            throw new ChannelInvalidException("No valid UDP channel for node: " + nodeId, null);
        }

        // 创建一个Promise用于等待发送操作完成
        CompletableFuture<Void> sendFuture = new CompletableFuture<>();

        // 绑定到Netty的EventLoop执行发送操作
        channel.eventLoop().execute(() -> {
            ChannelFuture writeFuture = channel.writeAndFlush(message);
            writeFuture.addListener((ChannelFutureListener) future -> {
                if (future.isSuccess()) {
                    sendFuture.complete(null);
                } else {
                    sendFuture.completeExceptionally(future.cause());
                }
            });
        });

        // 等待发送操作完成（使用CompletableFuture的超时机制）
        try {
            sendFuture.get(DEFAULT_OPERATION_TIMEOUT, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            // 解包原始异常
            Throwable cause = e.getCause();
            log.error("Failed to send UDP message to node {}: {}", nodeId, cause.getMessage());
            handleSendFailure(nodeId, targetAddr, message);

            if (cause instanceof Exception) {
                throw (Exception) cause;
            } else {
                throw new ConnectException("Failed to send UDP message: " + cause.getMessage());
            }
        } catch (TimeoutException e) {
            log.error("UDP send to node {} timed out after {}ms", nodeId, DEFAULT_OPERATION_TIMEOUT);
            throw new TimeoutException("UDP send operation timed out");
        }
    }



    /**
     * 异步发送消息（无返回值）
     */
    public void sendAsyncMessage(KademliaMessage message) {
        executorService.submit(() -> {
            try {
                sendMessage(message);
            } catch (Exception e) {
                log.error("Async UDP message send failed", e);
            }
        });
    }



    /**
     * 同步发送带响应的消息（兼容原有接口）
     */
    public KademliaMessage sendMessageWithResponse(KademliaMessage message)
            throws ConnectException, TimeoutException, InterruptedException, Exception {
        return sendMessageWithResponse(message, 5, TimeUnit.SECONDS);
    }


    /**
     * 同步发送带响应的消息（自定义超时）
     */
    public KademliaMessage sendMessageWithResponse(KademliaMessage message, long timeout, TimeUnit unit)
            throws Exception {
        try {
            return sendMessageWithResponseAsync(message, timeout, unit, MAX_RETRY_COUNT)
                    .get(timeout, unit);
        } catch (ExecutionException e) {
            // 解包原始异常
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            } else {
                throw new Exception("UDP request failed", cause);
            }
        }
    }


    /**
     * 异步发送带响应的消息（核心优化点）
     */
    public CompletableFuture<KademliaMessage> sendMessageWithResponseAsync(KademliaMessage message) {
        return sendMessageWithResponseAsync(message, 5, TimeUnit.SECONDS, MAX_RETRY_COUNT);
    }


    /**
     * 异步发送带响应的消息（支持自定义超时和重试）
     */
    public CompletableFuture<KademliaMessage> sendMessageWithResponseAsync(
            KademliaMessage message, long timeout, TimeUnit unit, int retryCount) {

        // 参数校验
        if (message == null || message.getReceiver() == null) {
            CompletableFuture<KademliaMessage> invalidFuture = new CompletableFuture<>();
            invalidFuture.completeExceptionally(new IllegalArgumentException("消息或接收者不能为空"));
            return invalidFuture;
        }

        BigInteger nodeId = message.getReceiver().getId();
        message.setResponse(false); // 标记为请求消息

        // 封装可重试的发送任务
        Supplier<CompletableFuture<KademliaMessage>> sendTask = () ->
                executeSendWithResponse(message, timeout, unit);

        // 执行带重试的任务
        return retryTask(sendTask, retryCount, nodeId);
    }




    /**
     * 带重试的任务执行器（指数退避策略）
     */
    private CompletableFuture<KademliaMessage> retryTask(
            Supplier<CompletableFuture<KademliaMessage>> task, int remainingRetries, BigInteger nodeId) {

        return (CompletableFuture<KademliaMessage>) task.get().handle((response, ex) -> {
            // 无异常：直接返回结果
            if (ex == null) {
                return CompletableFuture.completedFuture(response);
            }

            // 有异常：判断是否需要重试
            if (remainingRetries > 0 && isRetryableException(ex)) {
                log.warn("Node {} UDP request failed, remaining retries: {}, cause: {}",
                        nodeId, remainingRetries, ex.getMessage());
                // 指数退避后重试（使用Netty线程池避免阻塞）
                long delay = RETRY_DELAY_BASE * (1 << (remainingRetries - 1));
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        TimeUnit.MILLISECONDS.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new CompletionException(ie);
                    }
                    return retryTask(task, remainingRetries - 1, nodeId).join();
                }, eventLoopGroup);
            }

            // 不重试：返回异常
            return CompletableFuture.failedFuture(ex);
        }).thenCompose(future -> future); // 解嵌套的CompletableFuture
    }





    /**
     * 判断异常是否可重试（UDP特有场景）
     */
    private boolean isRetryableException(Throwable ex) {
        Throwable cause = ex instanceof CompletionException ? ex.getCause() : ex;
        return cause instanceof SocketException
                || cause instanceof ConnectException
                || cause instanceof ChannelInvalidException
                || cause instanceof ResponseTimeoutException;
    }


    /**
     * 执行单次发送并等待响应的核心逻辑
     */
    private CompletableFuture<KademliaMessage> executeSendWithResponse(
            KademliaMessage message, long timeout, TimeUnit unit) {

        CompletableFuture<KademliaMessage> resultFuture = new CompletableFuture<>();
        BigInteger nodeId = message.getReceiver().getId();
        long requestId = message.getRequestId();
        InetSocketAddress targetAddr = new InetSocketAddress(
                message.getReceiver().getIpv4(), message.getReceiver().getUdpPort());

        try {
            // 获取有效通道
            Channel channel = getOrCreateChannel(nodeId, targetAddr);
            if (!isChannelValid(channel)) {
                resultFuture.completeExceptionally(
                        new ChannelInvalidException("Invalid UDP channel for node: " + nodeId, null));
                return resultFuture;
            }

            // 监听通道关闭事件
            channel.closeFuture().addListener(future -> {
                if (!resultFuture.isDone()) {
                    resultFuture.completeExceptionally(
                            new ChannelInvalidException("UDP channel to node " + nodeId + " closed", null));
                }
            });

            // 发送请求并获取Netty Promise
            Promise<KademliaMessage> nettyPromise = RequestResponseManager
                    .sendRequest(channel, message, timeout, unit);

            // 转换为CompletableFuture
            nettyPromise.addListener(future -> {
                try {
                    if (future.isSuccess()) {
                        resultFuture.complete(nettyPromise.getNow());
                    } else {
                        Throwable cause = future.cause();
                        if (cause instanceof TimeoutException) {
                            resultFuture.completeExceptionally(
                                    new ResponseTimeoutException("Node " + nodeId + " response timeout"));
                        } else if (cause instanceof ChannelException) {
                            resultFuture.completeExceptionally(
                                    new ChannelInvalidException("UDP channel error: " + cause.getMessage(), cause));
                        } else {
                            resultFuture.completeExceptionally(
                                    new Exception("UDP request failed: " + cause.getMessage(), cause));
                        }
                    }
                } finally {
                    // 清理请求（幂等操作）
                    RequestResponseManager.clearRequest(requestId);
                }
            });

            // 双重超时保护
            resultFuture.orTimeout(timeout, unit)
                    .exceptionally(ex -> {
                        if (ex instanceof TimeoutException) {
                            nettyPromise.cancel(false);
                            RequestResponseManager.clearRequest(requestId);
                            throw new CompletionException(
                                    new ResponseTimeoutException("Double timeout for node " + nodeId));
                        }
                        throw new CompletionException(ex);
                    });

        } catch (Exception e) {
            resultFuture.completeExceptionally(e);
            RequestResponseManager.clearRequest(requestId);
        }

        return resultFuture;
    }



    /**
     * 处理发送失败（重建通道并重试）
     */
    private void handleSendFailure(BigInteger nodeId, InetSocketAddress targetAddr, KademliaMessage message) {
        // 提交到Netty线程池执行，避免阻塞业务线程
        eventLoopGroup.submit(() -> {
            try {
                // 移除无效通道
                Channel oldChannel = nodeUDPChannel.get(nodeId);
                if (oldChannel != null && !isChannelValid(oldChannel)) {
                    if (nodeUDPChannel.remove(nodeId, oldChannel)) {
                        oldChannel.close(); // 异步关闭
                    }
                }

                // 重建通道并重发
                Channel newChannel = createAndConnectChannel(nodeId, targetAddr);
                if (newChannel != null) {
                    nodeUDPChannel.put(nodeId, newChannel);
                    newChannel.writeAndFlush(message).addListener((ChannelFutureListener) f -> {
                        if (f.isSuccess()) {
                            log.info("Resent UDP message to node {}", nodeId);
                        } else {
                            log.error("Final retry failed for node {}", nodeId, f.cause());
                            nodeUDPChannel.remove(nodeId);
                        }
                    });
                }
            } catch (Exception e) {
                log.error("Failed to recover UDP channel for node {}", nodeId, e);
            }
        });
    }

    /**
     * 原子操作：获取或创建UDP通道（避免并发创建）
     */
    private Channel getOrCreateChannel(BigInteger nodeId, InetSocketAddress targetAddr)
            throws InterruptedException, ConnectException {

        // 1. 检查已有通道
        Channel existingChannel = nodeUDPChannel.get(nodeId);
        if (existingChannel != null && isChannelValid(existingChannel)
                && isChannelConnectedTo(existingChannel, targetAddr)) {
            return existingChannel;
        }

        // 2. 原子创建新通道（移除内部的 putIfAbsent 操作）
        return nodeUDPChannel.computeIfAbsent(nodeId, key -> {
            try {
                // 二次检查，避免并发创建（双重校验）
                Channel doubleCheck = nodeUDPChannel.get(nodeId);
                if (doubleCheck != null && isChannelValid(doubleCheck)
                        && isChannelConnectedTo(doubleCheck, targetAddr)) {
                    return doubleCheck;
                }

                // 创建新通道（computeIfAbsent 会自动将结果放入 map）
                Channel newChannel = createAndConnectChannel(nodeId, targetAddr);
                return newChannel; // 直接返回，无需手动 put
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CompletionException(e);
            } catch (ConnectException e) {
                throw new CompletionException(e);
            }
        });
    }


    /**
     * 创建并连接UDP通道（UDP的connect仅设置默认目标地址）
     */
    private Channel createAndConnectChannel(BigInteger nodeId, InetSocketAddress targetAddr)
            throws InterruptedException, ConnectException {

        log.info("Creating UDP channel to {} (node {})", targetAddr, nodeId);
        int maxRetries = 3;
        long retryDelayMs = 100;

        for (int retry = 0; retry < maxRetries; retry++) {
            try {
                // 绑定本地随机端口
                ChannelFuture bindFuture = bootstrap.bind(0);
                if (!bindFuture.await(DEFAULT_OPERATION_TIMEOUT)) {
                    throw new ConnectException("UDP bind timeout for node: " + nodeId);
                }
                if (!bindFuture.isSuccess()) {
                    Throwable cause = bindFuture.cause();
                    if (cause instanceof java.net.BindException && retry < maxRetries - 1) {
                        log.warn("Port busy, retrying bind for node {} (retry {})", nodeId, retry + 1);
                        Thread.sleep(retryDelayMs * (retry + 1));
                        continue;
                    }
                    throw new ConnectException("UDP bind failed: " + cause.getMessage());
                }

                Channel channel = bindFuture.channel();
                // 设置默认目标地址（UDP无真正连接）
                ChannelFuture connectFuture = channel.connect(targetAddr);
                if (!connectFuture.await(DEFAULT_OPERATION_TIMEOUT)) {
                    channel.close().sync(); // 释放端口
                    throw new ConnectException("UDP connect timeout (node " + nodeId + ")");
                }
                if (!connectFuture.isSuccess()) {
                    channel.close().sync();
                    throw new ConnectException("UDP connect failed: " + connectFuture.cause().getMessage());
                }

                // 关联节点ID
                channel.attr(NODE_ID_KEY).set(nodeId);
                // 通道关闭时清理映射
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

        throw new ConnectException("Failed to create UDP channel for node " + nodeId + " after " + maxRetries + " retries");
    }

    /**
     * 检查UDP通道是否关联到目标地址
     */
    private boolean isChannelConnectedTo(Channel channel, InetSocketAddress targetAddr) {
        try {
            InetSocketAddress remoteAddr = (InetSocketAddress) channel.remoteAddress();
            return remoteAddr != null && remoteAddr.equals(targetAddr);
        } catch (Exception e) {
            return false;
        }
    }


    /**
     * 优雅关闭所有资源
     */
    @PreDestroy
    public void stop() {
        log.info("Stopping UDPClient...");

        // 1. 关闭业务线程池
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
        }

        // 2. 关闭清理线程池
        cleanerExecutor.shutdown();
        try {
            if (!cleanerExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                cleanerExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            cleanerExecutor.shutdownNow();
        }

        // 3. 关闭所有UDP通道
        nodeUDPChannel.values().forEach(channel -> {
            if (channel.isOpen()) {
                channel.close().addListener(future ->
                        log.debug("Closed UDP channel: {}", channel.remoteAddress())
                );
            }
        });

        // 4. 关闭EventLoopGroup
        eventLoopGroup.shutdownGracefully(1, 5, TimeUnit.SECONDS)
                .addListener(future -> log.info("UDP EventLoopGroup shut down"));

        nodeUDPChannel.clear();
    }

}
