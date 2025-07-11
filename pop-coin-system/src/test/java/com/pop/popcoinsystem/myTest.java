package com.pop.popcoinsystem;

import com.pop.popcoinsystem.network.KademliaNodeServer;
import com.pop.popcoinsystem.network.KademliaUdpHandler;
import com.pop.popcoinsystem.network.protocol.message.KademliaMessage;
import com.pop.popcoinsystem.network.protocol.message.PingKademliaMessage;
import com.pop.popcoinsystem.util.SerializeUtils;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.CharsetUtil;
import lombok.extern.slf4j.Slf4j;

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.UUID;


@Slf4j
public class myTest {
    public static void main(String[] args) throws Exception {
        KademliaNodeServer kademliaNodeServer = new KademliaNodeServer(BigInteger.valueOf(1L), "127.0.0.1", 8333, 8334);
        kademliaNodeServer.start();
/*        KademliaNodeServer kademliaNodeServer2 = new KademliaNodeServer(BigInteger.valueOf(2L), "127.0.0.1", 8335, 8336);
        kademliaNodeServer2.start();
        log.info("引导节点信息"+kademliaNodeServer.getNodeInfo());
        kademliaNodeServer2.connectToBootstrapNodes(kademliaNodeServer.getNodeInfo());*/


        EventLoopGroup group = new NioEventLoopGroup();
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        ChannelPipeline pipeline = ch.pipeline();

                        // 添加自定义处理器
                    }
                });

        ChannelFuture channelFuture = bootstrap.connect("127.0.0.1", 8334).sync();
        Channel channel = channelFuture.channel();
        PingKademliaMessage pingKademliaMessage = new PingKademliaMessage();
        pingKademliaMessage.setMessageId(UUID.randomUUID().toString());
        pingKademliaMessage.setData("123456");

        byte[] serialize = KademliaMessage.serialize(pingKademliaMessage);
        // 构造二进制消息
        ByteBuf byteMessage = channel.alloc().buffer();
        byteMessage.writeInt(3);         // 消息类型
        byteMessage.writeInt(4 + serialize.length);  // 内容长度 + 内容长度字段本身
        byteMessage.writeInt(serialize.length);      // 内容长度
        byteMessage.writeBytes(serialize);           // 内容
        channel.writeAndFlush(byteMessage);






        //服务端
/*
        EventLoopGroup udpGroup = new NioEventLoopGroup();
        Bootstrap udpBootstrap = new Bootstrap();
        udpBootstrap.group(udpGroup)
                .channel(NioDatagramChannel.class)
                .option(ChannelOption.SO_BROADCAST, true)
                .handler(new ChannelInitializer<NioDatagramChannel>() {
                    @Override
                    protected void initChannel(NioDatagramChannel ch) throws Exception {
                        ChannelPipeline pipeline = ch.pipeline();
                        // 添加UDP消息处理器
                        pipeline.addLast(new KademliaUdpHandler());
                    }
                });
        ChannelFuture udpBindFuture =udpBootstrap.bind("127.0.0.1", 8888).sync() ;


        byte[] serialize = SerializeUtils.serialize("123456");
        ByteBuf byteBuf = Unpooled.copiedBuffer(serialize);

        PingKademliaMessage pingKademliaMessage = new PingKademliaMessage();
        byte[] serialize1 = PingKademliaMessage.serialize(pingKademliaMessage);
        ByteBuf byteBuf1 = Unpooled.copiedBuffer(serialize1);
        InetSocketAddress inetSocketAddress = new InetSocketAddress("127.0.0.1", 8333);
        DatagramPacket sendPacket = new DatagramPacket(byteBuf1, inetSocketAddress);
        udpBindFuture.channel().writeAndFlush(
                sendPacket
        );
*/


        //客户端





    }




}
