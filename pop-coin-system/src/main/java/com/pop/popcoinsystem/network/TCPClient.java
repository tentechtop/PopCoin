package com.pop.popcoinsystem.network;

import com.pop.popcoinsystem.network.common.NodeInfo;
import com.pop.popcoinsystem.network.protocol.message.KademliaMessage;
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

    private final KademliaNodeServer nodeServer;



    private final ExecutorService executorService;
    private Bootstrap bootstrap;
    private NioEventLoopGroup eventLoopGroup;

    /** 节点ID到Channel的映射 */
    private final Map<BigInteger, Channel> nodeTCPChannel = new ConcurrentHashMap<>();
    // 用于在Channel中存储节点ID的属性键
    private static final AttributeKey<BigInteger> NODE_ID_KEY = AttributeKey.valueOf("NODE_ID");
    private static final int DEFAULT_CONNECT_TIMEOUT = 30000; // 30秒，与Netty默认保持一致

    public TCPClient(KademliaNodeServer nodeServer) {
        this.nodeServer = nodeServer;


        executorService = Executors.newFixedThreadPool(10);
        // 全局复用一个EventLoopGroup，避免资源浪费
        eventLoopGroup = new NioEventLoopGroup();

        // 初始化Bootstrap并复用配置
        bootstrap = new Bootstrap();
        bootstrap.group(eventLoopGroup)
                // 添加空闲检测：120秒无读写则关闭通道
                .option(ChannelOption.SO_KEEPALIVE, true)
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
                        // 3. 核心处理器（请求-响应逻辑）
                        pipeline.addLast(new KademliaTcpHandler(nodeServer));
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
     * 发送消息并获取响应（通过messageId关联请求与响应）
     * @return Future对象，可通过该对象获取响应结果
     */
    public Promise<KademliaMessage> sendMessageWithResponse(KademliaMessage message)
            throws InterruptedException, ConnectException {

        if (message == null || message.getReceiver() == null) {
            throw new IllegalArgumentException("Message or receiver cannot be null");
        }
        BigInteger nodeId = message.getReceiver().getId();
        Channel channel = getOrCreateChannel(message.getReceiver());
        if (channel == null || !channel.isActive()) {
            throw new ConnectException("No active channel available for node: " + nodeId);
        }

        // 1. 获取消息自带的messageId（无需重新生成）
        long messageId = message.getMessageId();
        log.debug("Sending request with messageId: {} to node: {}", messageId, nodeId);

        // 2. 创建Promise对象（绑定Netty的EventLoop线程）
        Promise<KademliaMessage> promise = new DefaultPromise<>(channel.eventLoop());

        // 3. 获取通道的响应处理器，存储请求与Promise的关联
        ResponseHandler responseHandler = getOrAddResponseHandler(channel);
        responseHandler.addPendingRequest(messageId, promise);

        // 4. 发送消息并监听发送结果
        channel.writeAndFlush(message).addListener((ChannelFutureListener) future -> {
            if (!future.isSuccess()) {
                String errorMsg = "Failed to send request (messageId: " + messageId + ") to node: " + nodeId;
                log.error(errorMsg, future.cause());
                // 发送失败：移除关联并标记Promise失败
                responseHandler.removePendingRequest(messageId);
                if (!promise.isDone()) {
                    promise.setFailure(new Exception(errorMsg, future.cause()));
                }
            }
        });

        // 5. 设置超时处理（30秒未收到响应则超时）
        channel.eventLoop().schedule(() -> {
            if (!promise.isDone()) {
                String errorMsg = "Timeout (30s) waiting for response (messageId: " + messageId + ") from node: " + nodeId;
                log.warn(errorMsg);
                responseHandler.removePendingRequest(messageId);
                promise.setFailure(new TimeoutException(errorMsg));
            }
        }, 30, TimeUnit.SECONDS);

        return promise;
    }
    /**
     * 从通道Pipeline中获取已有的ResponseHandler，若不存在则创建并添加
     * 确保每个通道只有一个ResponseHandler实例，避免重复处理
     */
    private ResponseHandler getOrAddResponseHandler(Channel channel) {
        // 获取通道的Pipeline
        ChannelPipeline pipeline = channel.pipeline();

        // 尝试从Pipeline中获取已存在的ResponseHandler
        ResponseHandler existingHandler = pipeline.get(ResponseHandler.class);
        if (existingHandler != null) {
            return existingHandler;
        }

        // 若不存在，则创建新的ResponseHandler并添加到Pipeline
        // 注意添加位置：应在解码器之后（确保处理的是已解码的KademliaMessage）
        ResponseHandler newHandler = new ResponseHandler();
        // 添加到"TCPKademliaMessageDecoder"之后（与初始化时的解码器名称对应）
        pipeline.addAfter("TCPKademliaMessageDecoder", "responseHandler", newHandler);

        return newHandler;
    }

    /**
     * 响应处理器：关联请求messageId与Promise，处理接收的响应消息
     */
    private static class ResponseHandler extends ChannelInboundHandlerAdapter {
        // 存储等待响应的请求：messageId -> Promise（线程安全的Map）
        private final Map<Long, Promise<KademliaMessage>> pendingRequests = new ConcurrentHashMap<>();

        /**
         * 添加待处理的请求关联
         */
        public void addPendingRequest(long messageId, Promise<KademliaMessage> promise) {
            pendingRequests.put(messageId, promise);
        }

        /**
         * 移除已处理的请求关联
         */
        public void removePendingRequest(long messageId) {
            pendingRequests.remove(messageId);
        }

        /**
         * 接收响应消息时，匹配对应的请求并完成Promise
         */
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof KademliaMessage) {
                KademliaMessage response = (KademliaMessage) msg;
                // 只处理响应类型的消息
                if (response.isResponse()) {
                    long responseMsgId = response.getMessageId();
                    // 查找对应的Promise
                    Promise<KademliaMessage> promise = pendingRequests.remove(responseMsgId);
                    if (promise != null) {
                        // 检查消息是否过期
                        if (response.isExpired()) {
                            promise.setFailure(new Exception("Response (messageId: " + responseMsgId + ") expired"));
                        } else {
                            promise.setSuccess(response); // 标记响应成功
                        }
                        return; // 已处理，不再传递
                    }
                    log.debug("Received unmatched response (messageId: {}) - no pending request", responseMsgId);
                }
            }
            // 非目标响应消息，传递给下一个处理器
            super.channelRead(ctx, msg);
        }

        /**
         * 通道发生异常时，失败所有等待的请求
         */
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.error("Channel exception, failing all pending requests", cause);
            // 失败所有未完成的Promise
            pendingRequests.values().forEach(promise -> {
                if (!promise.isDone()) {
                    promise.setFailure(new Exception("Channel error: " + cause.getMessage(), cause));
                }
            });
            pendingRequests.clear(); // 清理资源
            ctx.close(); // 关闭通道
        }

        /**
         * 通道关闭时，清理所有等待的请求
         */
        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            log.info("Channel closed, clearing pending requests");
            pendingRequests.values().forEach(promise -> {
                if (!promise.isDone()) {
                    promise.setFailure(new Exception("Channel closed before receiving response"));
                }
            });
            pendingRequests.clear();
            super.channelInactive(ctx);
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

}
