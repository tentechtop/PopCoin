package com.pop.popcoinsystem.network;

import com.pop.popcoinsystem.network.common.NodeInfo;
import com.pop.popcoinsystem.network.protocol.message.KademliaMessage;
import com.pop.popcoinsystem.network.protocol.message.PingKademliaMessage;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.handler.codec.MessageToMessageEncoder;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.el.util.MessageFactory;

import java.math.BigInteger;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
public class UDPClient {
    private final ExecutorService executorService;

    private Bootstrap bootstrap;
    private NioEventLoopGroup group;

    public UDPClient() {
        executorService = Executors.newFixedThreadPool(10);
    }

    /** 节点ID到Channel的映射 */
    private final Map<BigInteger, Channel> nodeUDPChannel = new ConcurrentHashMap<>();
    /** 节点ID到地址的映射 */
    private final Map<BigInteger, InetSocketAddress> nodeAddresses = new ConcurrentHashMap<>();



    //发送消息
    public  void sendMessage(KademliaMessage message) throws InterruptedException {
        BigInteger id = message.getReceiver().getId();
        Channel channel = null;
        if (!nodeUDPChannel.containsKey(id) || !nodeUDPChannel.get(id).isActive()){
            //重新连接 并保存Channel
            NodeInfo receiver = message.getReceiver();
            channel = connectTarget(receiver.getIpv4(), receiver.getUdpPort());
            nodeUDPChannel.put(id, channel);
        }
        channel = nodeUDPChannel.get(id);
        channel.writeAndFlush(message);
    }


    public Channel connectTarget(String ipv4, int tcpPort) throws InterruptedException {
        group = new NioEventLoopGroup();
        bootstrap = new Bootstrap();
        bootstrap.group(group)
                .channel(NioDatagramChannel.class)
                .option(ChannelOption.SO_BROADCAST, true)
                .handler(new ChannelInitializer<NioDatagramChannel>() {
                    @Override
                    protected void initChannel(NioDatagramChannel ch) throws Exception {
                        ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast(new LengthFieldBasedFrameDecoder(
                                10 * 1024 * 1024,  // 最大帧长度
                                4,                 // 长度字段偏移量（跳过类型字段）
                                4,                 // 长度字段长度（总长度字段）
                                -8,                // 长度调整值 = 内容长度 - 总长度 = -8
                                0                 // 跳过前12字节（类型+总长度+内容长度）  目前不跳过
                        ));
                        pipeline.addLast(new LengthFieldPrepender(4));
                        pipeline.addLast(new KademliaNodeServer.UDPKademliaMessageEncoder());
                        pipeline.addLast(new KademliaNodeServer.UDPKademliaMessageDecoder());
                    }
                });
        ChannelFuture connect = bootstrap.connect(ipv4, tcpPort).sync();
        Channel channel = connect.channel();
        connect.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture channelFuture) throws Exception {
                if (channelFuture.isSuccess()) {
                    log.info("UDP:Connect to " + ipv4 + ":" + tcpPort + " success");
                } else {
                    log.error("UDP:Connect to " + ipv4 + ":" + tcpPort + " failed");
                    throw new ConnectException("Connect to " + ipv4 + ":" + tcpPort + " failed");
                }
            }
        });
        //异步
        executorService.submit(() -> {
            try {
                channel.closeFuture().sync();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });
        return channel;
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
     * stop
     */
    @PreDestroy
    public void stop() {
        if (group != null) {
            group.shutdownGracefully();
        }
    }

}
