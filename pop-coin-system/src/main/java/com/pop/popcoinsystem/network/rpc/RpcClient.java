package com.pop.popcoinsystem.network.rpc;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.Promise;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;

public class RpcClient {
    private final String host;
    private final int port;
    private Channel channel;
    private final Map<String, Promise<Object>> requestMap = new ConcurrentHashMap<>();
    private static final AtomicLong requestIdGenerator = new AtomicLong();

    public RpcClient(String host, int port) {
        this.host = host;
        this.port = port;
        init(); // 初始化 Netty 客户端
    }

    private void init() {
        EventLoopGroup group = new NioEventLoopGroup();
        try {
            Bootstrap bootstrap = new Bootstrap()
                    .group(group)
                    .channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ChannelPipeline pipeline = ch.pipeline();
                            // 添加编解码器（自定义）
                            pipeline.addLast(new RpcEncoder(RpcRequest.class));
                            pipeline.addLast(new RpcDecoder(RpcResponse.class));
                            // 客户端处理器：接收响应并唤醒等待线程
                            pipeline.addLast(new RpcClientHandler(requestMap));
                        }
                    });
            // 连接服务端
            ChannelFuture future = bootstrap.connect(host, port).sync();
            this.channel = future.channel();
        } catch (InterruptedException e) {
            group.shutdownGracefully();
        }
    }

    // 发送请求并同步等待结果
    public Object sendRequest(RpcRequest request) throws InterruptedException, ExecutionException {
        String requestId = String.valueOf(requestIdGenerator.incrementAndGet());
        // 创建 Promise 用于等待响应
        request.setRequestId(requestId); // 设置请求ID
        Promise<Object> promise = new DefaultPromise<>(channel.eventLoop());

        requestMap.put(requestId, promise);

        // 发送请求（实际中应将 requestId 放入请求中，用于匹配响应）
        channel.writeAndFlush(request).sync();

        // 阻塞等待结果（模拟本地方法的同步调用）
        return promise.get();
    }
}
