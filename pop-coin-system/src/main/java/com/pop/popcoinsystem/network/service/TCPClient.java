package com.pop.popcoinsystem.network.service;

import com.pop.popcoinsystem.exception.ChannelInvalidException;
import com.pop.popcoinsystem.exception.ResponseTimeoutException;
import com.pop.popcoinsystem.network.common.NodeInfo;
import com.pop.popcoinsystem.network.protocol.message.KademliaMessage;
import com.pop.popcoinsystem.network.protocol.message.RpcRequestMessage;
import com.pop.popcoinsystem.network.rpc.RequestResponseManager;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

@Slf4j
public class TCPClient {
    private final KademliaNodeServer kademliaNodeServer;
    private final ExecutorService executorService;
    private Bootstrap bootstrap;
    private NioEventLoopGroup eventLoopGroup;

    /** 节点ID到Channel的映射 */
    private final Map<BigInteger, Channel> nodeTCPChannel = new ConcurrentHashMap<>();

    /** 定期清理无效连接的定时任务 */
    private final ScheduledExecutorService cleanerExecutor = Executors.newSingleThreadScheduledExecutor();

    // 用于在Channel中存储节点ID的属性键
    public static final AttributeKey<BigInteger> NODE_ID_KEY = AttributeKey.valueOf("NODE_ID");
    public static final int DEFAULT_CONNECT_TIMEOUT = 30000; // 30秒
    private static final int MAX_RETRY_COUNT = 2; // 减少重试次数，避免恶性循环
    private static final long RETRY_DELAY_BASE = 200; // 延长基础延迟，避免网络拥堵


    public TCPClient(KademliaNodeServer kademliaNodeServer) {
        this.kademliaNodeServer = kademliaNodeServer;
        executorService = Executors.newVirtualThreadPerTaskExecutor();
        eventLoopGroup = new NioEventLoopGroup();
        bootstrap = new Bootstrap();
        bootstrap.group(eventLoopGroup)
                .channel(NioSocketChannel.class)
                // 3. TCP参数优化
                .option(ChannelOption.SO_REUSEADDR, true) // 允许端口复用
                .option(ChannelOption.SO_RCVBUF, 10* 1024 * 1024) // 接收缓冲区1MB
                .handler(new ChannelInitializer<NioSocketChannel>() {
                    @Override
                    protected void initChannel(NioSocketChannel ch) throws Exception {
                        ChannelPipeline pipeline = ch.pipeline();
                        // 1. 添加异常处理器（放在编解码器之前，优先捕获异常）
                        pipeline.addLast(new ConnectionExceptionHandler(nodeTCPChannel, kademliaNodeServer));
                        pipeline.addLast(new LengthFieldBasedFrameDecoder(
                                10 * 1024 * 1024,  // 最大帧长度
                                0,                 // 长度字段偏移量 - 从开头就读取长度
                                4,                 // 长度字段长度
                                0,                 // 长度调整值
                                4,                 // 跳过长度字段本身
                                true
                        ));
                        pipeline.addLast(new KademliaNodeServer.TCPKademliaMessageDecoder());
                        pipeline.addLast(new KademliaNodeServer.TCPKademliaMessageEncoder());
                        pipeline.addLast(new KademliaTcpHandler(kademliaNodeServer));
                    }
                });

        // 6. 启动定期清理任务（每30秒执行一次）
        cleanerExecutor.scheduleAtFixedRate(this::cleanInvalidChannels, 30, 30, TimeUnit.SECONDS);
    }


    /**
     * 批量清理无效通道（非阻塞操作）
     */
    private void cleanInvalidChannels() {
        List<Map.Entry<BigInteger, Channel>> invalidEntries = new ArrayList<>();
        // 先收集无效通道，避免遍历中修改map
        for (Map.Entry<BigInteger, Channel> entry : nodeTCPChannel.entrySet()) {
            if (!isChannelValid(entry.getValue())) {
                invalidEntries.add(entry);
            }
        }
        // 批量关闭并移除
        for (Map.Entry<BigInteger, Channel> entry : invalidEntries) {
            BigInteger nodeId = entry.getKey();
            Channel channel = entry.getValue();
            if (nodeTCPChannel.remove(nodeId, channel)) {
                log.info("Cleaned invalid channel for node {} in scheduled task", nodeId);
                channel.close().addListener(future ->
                        log.debug("Closed invalid channel for node {}", nodeId)
                ); // 异步关闭，不阻塞
            }
        }
    }


    /**
     * 优化发送逻辑：完全异步化，避免阻塞
     */
    public void sendMessage(KademliaMessage message) throws InterruptedException, ConnectException {
        if (message == null || message.getReceiver() == null) {
            throw new IllegalArgumentException("Message or receiver cannot be null");
        }
        BigInteger nodeId = message.getReceiver().getId();
        Channel channel = getOrCreateChannel(message.getReceiver());
        if (channel != null && isChannelValid(channel)) {
            // 绑定到Netty的EventLoop执行，避免业务线程阻塞
            channel.eventLoop().execute(() ->
                    channel.writeAndFlush(message).addListener((ChannelFutureListener) future -> {
                        if (!future.isSuccess()) {
                            Throwable cause = future.cause();
                            log.error("Failed to send message to node {}: {}", nodeId, cause.getMessage());
                            // 处理 Connection reset 异常
                            if (cause instanceof SocketException && "Connection reset".equals(cause.getMessage())) {
                                log.warn("节点 {} 发送消息时连接重置，清理通道", nodeId);
                                // 关闭通道并移除映射
                                channel.close();
                                nodeTCPChannel.remove(nodeId);
                                // 记录失败（触发冷却机制）
                                kademliaNodeServer.offlineNode(nodeId);
                            } else {
                                //其他异常
                                channel.close();
                                nodeTCPChannel.remove(nodeId);
                                log.error("发送消息失败：{}", cause.getMessage());
                                kademliaNodeServer.offlineNode(nodeId);
                            }
                        }
                    })
            );
        } else {
            throw new ConnectException("No active channel available for node: " + nodeId);
        }
    }

    public void sendAsyncMessage(KademliaMessage message) {
        executorService.submit(() -> {
            try {
                sendMessage(message);
            } catch (Exception e) {
                log.error("异步消息发送失败Async message sending failed", e);
            }
        });
    }

    public KademliaMessage sendMessageWithResponse(KademliaMessage message)
            throws ConnectException, TimeoutException, InterruptedException, Exception {
        return sendMessageWithResponse(message, 5, TimeUnit.SECONDS);
    }

    public KademliaMessage sendMessageWithResponse(KademliaMessage message, long timeout, TimeUnit unit)
            throws ConnectException, TimeoutException, InterruptedException, Exception {
        try {
            return sendMessageWithResponseAsync(message, timeout, unit, 3).get(timeout, unit);
        } catch (ExecutionException e) {
            // 解包原始异常
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            } else {
                throw new Exception("发送消息失败", cause);
            }
        }
    }


    /**
     * 优化后的带响应消息发送方法：异步化、精细化异常、增强重试
     * @return 异步结果，包含响应消息或具体异常
     */
    public CompletableFuture<KademliaMessage> sendMessageWithResponseAsync(KademliaMessage message) {
        return sendMessageWithResponseAsync(message, 5, TimeUnit.SECONDS, 1); // 默认重试1次
    }

    /**
     * 带参数的异步发送方法（支持自定义超时和重试次数）
     * @param message 消息体
     * @param timeout 超时时间
     * @param unit 时间单位
     * @param retryCount 最大重试次数（0表示不重试）
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

        // 初始化首次发送的消息（确保有请求ID）
        if (message.getRequestId() <= 0) {
            message.setReqResId(); // 确保首次发送有ID
        }

        // 关键修改：每次执行任务时使用新请求ID的消息副本
        Supplier<CompletableFuture<KademliaMessage>> sendTask = () -> {
            // 复制消息并生成新请求ID（首次执行也会用新ID，避免原消息ID冲突）
            KademliaMessage messageWithNewId = copyMessageWithNewRequestId(message);
            return executeSendWithResponse(messageWithNewId, timeout, unit);
        };

        // 执行带重试的任务
        return retryTask(sendTask, retryCount, nodeId);
    }


    /**
     * 带重试的任务执行器（仅重试可恢复的异常）
     */
    private CompletableFuture<KademliaMessage> retryTask(
            Supplier<CompletableFuture<KademliaMessage>> task, int remainingRetries, BigInteger nodeId) {

        // 执行单次任务
        return (CompletableFuture<KademliaMessage>) task.get().handle((response, ex) -> {
            // 无异常：直接返回结果
            if (ex == null) {
                return CompletableFuture.completedFuture(response);
            }
            // 有异常：判断是否需要重试
            if (false && remainingRetries > 0 && isRetryableException(ex) ) {
                log.warn("节点 {} 发送失败，剩余重试次数: {}，原因: {}",
                        nodeId, remainingRetries, ex.getMessage());
                // 指数退避后重试
                long delay = RETRY_DELAY_BASE * (1 << (remainingRetries - 1));
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        TimeUnit.MILLISECONDS.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new CompletionException(ie);
                    }
                    return retryTask(task, remainingRetries - 1, nodeId).join();
                }, eventLoopGroup); // 使用Netty线程池执行重试，避免阻塞业务线程
            }

            // 不重试：返回异常
            return CompletableFuture.failedFuture(ex);
        }).thenCompose(future -> future); // 解嵌套的CompletableFuture
    }

    /**
     * 判断异常是否可重试（网络抖动、临时连接失败等）
     */
    private boolean isRetryableException(Throwable ex) {
        Throwable cause = ex instanceof CompletionException ? ex.getCause() : ex;
        // 处理 Connection reset 异常
        if (cause instanceof SocketException && "Connection reset".equals(cause.getMessage())) {
            return false;
        }
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
        log.info("发送请求到节点: {}，请求ID: {}", nodeId, requestId);

        try {
            // 1. 获取通道（同步操作，已优化为非阻塞获取）
            Channel channel = getOrCreateChannel(message.getReceiver());
            if (!isChannelValid(channel)) {
                resultFuture.completeExceptionally(
                        new ChannelInvalidException("通道无效，无法发送请求到节点: " + nodeId, null));
                return resultFuture;
            }

            // 2. 监听通道关闭事件：若在等待响应期间通道关闭，立即失败
            ChannelFuture closeFuture = channel.closeFuture();
            closeFuture.addListener(future -> {
                if (!resultFuture.isDone()) {
                    resultFuture.completeExceptionally(
                            new ChannelInvalidException("等待响应期间，节点 " + nodeId + " 的通道已关闭", null));
                }
            });

            // 3. 发送请求并获取Netty的Promise
            Promise<KademliaMessage> nettyPromise = RequestResponseManager.sendRequest(channel, message, timeout, unit);

            // 4. 将Netty的Promise转换为CompletableFuture
            nettyPromise.addListener(future -> {
                try {
                    if (future.isSuccess()) {
                        resultFuture.complete(nettyPromise.getNow());
                    } else {
                        Throwable cause = future.cause();
                        // 精细化异常类型
                        if (cause instanceof TimeoutException) {
                            resultFuture.completeExceptionally(
                                    new ResponseTimeoutException("等待节点 " + nodeId + " 响应超时"));
                        } else if (cause instanceof ChannelException) {
                            resultFuture.completeExceptionally(
                                    new ChannelInvalidException("通道操作失败: " + cause.getMessage(), cause));
                        } else {
                            resultFuture.completeExceptionally(
                                    new Exception("发送请求失败: " + cause.getMessage(), cause));
                        }
                    }
                } finally {
                    // 确保请求清理（幂等操作，由RequestResponseManager保证线程安全）
                    RequestResponseManager.clearRequest(requestId);
                }
            });

            // 5. 额外的超时保护（防止Netty Promise未正确触发超时）
            resultFuture.orTimeout(timeout, unit)
                    .exceptionally(ex -> {
                        if (ex instanceof TimeoutException) {
                            nettyPromise.cancel(false); // 超时后取消Netty Promise
                            RequestResponseManager.clearRequest(requestId);
                            throw new CompletionException(
                                    new ResponseTimeoutException("双重超时保护：节点 " + nodeId + " 响应超时"));
                        }
                        throw new CompletionException(ex);
                    });

        } catch (Exception e) {
            // 捕获通道获取过程中的异常
            resultFuture.completeExceptionally(e);
            RequestResponseManager.clearRequest(requestId); // 确保清理
        }

        return resultFuture;
    }

    /**
     * 优化通道获取：减少锁竞争，增强并发安全性
     */
    private Channel getOrCreateChannel(NodeInfo receiver) throws InterruptedException, ConnectException {
        BigInteger nodeId = receiver.getId();
        // 1. 先尝试获取已有通道（非阻塞）
        Channel existingChannel = nodeTCPChannel.get(nodeId);
        if (existingChannel != null && isChannelValid(existingChannel)) {
            return existingChannel;
        }
        // 2. 若无效，清理并创建新通道（依赖computeIfAbsent的原子性）
        return nodeTCPChannel.computeIfAbsent(nodeId, key -> {
            try {
                // 二次检查（可选，防止其他线程在get和computeIfAbsent之间已创建通道）
                Channel existing = nodeTCPChannel.get(nodeId);
                if (isChannelValid(existing)) {
                    return existing;
                }
                // 执行连接（此时computeIfAbsent已确保只有一个线程进入此逻辑）
                Channel newChannel = connectWithRetry(receiver.getIpv4(), receiver.getTcpPort(), nodeId);
                // 无需putIfAbsent，computeIfAbsent会自动将newChannel放入map
                return newChannel;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CompletionException(e);
            } catch (ConnectException e) {
                throw new CompletionException(e);
            }
        });
    }
    /** 通道有效性检查 */
    private boolean isChannelValid(Channel channel) {
        return channel != null
                && channel.isOpen()
                && channel.isActive()
                && channel.isWritable()
                && !channel.closeFuture().isDone(); // 确保未处于关闭流程中
    }


    private Channel connectWithRetry(String ipv4, int tcpPort, BigInteger nodeId)
            throws InterruptedException, ConnectException {
        InetSocketAddress address = new InetSocketAddress(ipv4, tcpPort);
        ConnectException lastException = null;
        for (int retry = 0; retry < MAX_RETRY_COUNT; retry++) {
            // 重试前检查是否已有其他线程成功创建通道
            Channel existing = nodeTCPChannel.get(nodeId);
            if (isChannelValid(existing)) {
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
            //下线节点
            kademliaNodeServer.offlineNode(nodeId);
        }
        if (!connectFuture.isSuccess()) {
            kademliaNodeServer.offlineNode(nodeId);
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


    /**
     * 复制消息并生成新的唯一请求ID
     */
    private KademliaMessage copyMessageWithNewRequestId(KademliaMessage original) {
        // 假设KademliaMessage有全参构造器或 Builder 模式，此处根据实际实现调整
        KademliaMessage newMessage = new RpcRequestMessage();
        // 复制原始消息的核心属性
        newMessage.setReceiver(original.getReceiver());
        newMessage.setSender(original.getSender());
        newMessage.setData(original.getData());
        newMessage.setType(original.getType());
        newMessage.setResponse(false); // 保持为请求消息
        // 生成新的唯一请求ID（使用UUID或自增ID，此处示例用UUID）
        newMessage.setReqResId();
        return newMessage;
    }


    /**
     * 资源释放优化：优雅关闭所有资源
     */
    @PreDestroy
    public void destroy() {
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

        // 3. 关闭Netty线程池和所有通道
        nodeTCPChannel.values().forEach(channel -> channel.close().addListener(future ->
                log.debug("Channel closed during destroy")
        ));
        eventLoopGroup.shutdownGracefully();
        nodeTCPChannel.clear();
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
        Channel channel = nodeTCPChannel.remove(nodeId);
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