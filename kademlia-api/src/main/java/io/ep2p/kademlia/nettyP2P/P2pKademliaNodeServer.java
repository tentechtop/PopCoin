package io.ep2p.kademlia.nettyP2P;


import io.ep2p.kademlia.node.DHTKademliaNodeAPI;
import io.ep2p.kademlia.node.KademliaNodeAPI;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.stream.ChunkedWriteHandler;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.math.BigInteger;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

@Getter
@Slf4j
public class P2pKademliaNodeServer{
    private final int port;
    private final String host;

    private boolean running = false;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private ChannelFuture bindFuture;


    public P2pKademliaNodeServer(int port, String host) {
        this.port = port;
        this.host = host;
    }


    public synchronized void run(KademliaNodeAPI<BigInteger, P2pConnectionInfo> kademliaNodeAPI) throws InterruptedException {
        if (this.running)
            return;
        this.bossGroup = new NioEventLoopGroup();
        this.workerGroup = new NioEventLoopGroup();

        try {
            ServerBootstrap bootstrap = new ServerBootstrap()
                    .group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .handler(new LoggingHandler(LogLevel.INFO))
                    .childHandler(new ChannelInitializer<NioSocketChannel>() {
                        @Override
                        protected void initChannel(NioSocketChannel ch) {
                            ChannelPipeline pipeline = ch.pipeline();
                        }
                    })
                    .option(ChannelOption.SO_BACKLOG, 1024);

            ChannelFuture bind = host != null ? bootstrap.bind(host, port) : bootstrap.bind(port);
            running = true;
            this.bindFuture = bind.sync();

        } catch (InterruptedException e) {
            log.error("Kademlia Node Server interrupted", e);
            stop();
            throw e;
        }

    }



    public synchronized void stopNow() throws InterruptedException {
        this.running = false;
        if (bossGroup != null && workerGroup != null){
            bossGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync();
            workerGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync();
        }
        if (bindFuture != null)
            try {
                this.bindFuture.channel().close().sync();
            } catch (RejectedExecutionException e){
                log.error("Error when closing channel", e);
            }
    }





    public synchronized void stop() throws InterruptedException {
        this.running = false;
        if (bossGroup != null && workerGroup != null){
            bossGroup.shutdownGracefully().sync();
            workerGroup.shutdownGracefully().sync();
        }
        if (bindFuture != null)
            this.bindFuture.channel().closeFuture().sync();
    }
}
