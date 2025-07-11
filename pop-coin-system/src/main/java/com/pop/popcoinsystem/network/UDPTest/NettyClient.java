package com.pop.popcoinsystem.network.UDPTest;

import com.pop.popcoinsystem.network.KademliaNodeServer;
import com.pop.popcoinsystem.network.KademliaUdpHandler;
import com.pop.popcoinsystem.util.SerializeUtils;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;


import java.net.InetSocketAddress;
import java.nio.charset.Charset;

public class NettyClient {

    public static void main(String[] args) {
        EventLoopGroup group = new NioEventLoopGroup();
        try {
            Bootstrap b = new Bootstrap();
            b.group(group).channel(NioDatagramChannel.class)
                    .handler(new ChannelInitializer<NioDatagramChannel>() {
                        @Override
                        protected void initChannel(NioDatagramChannel ch) throws Exception {
                            ChannelPipeline pipeline = ch.pipeline();
                            //添加编码
                            pipeline.addLast(new MyClientHandler());
                        }
                    });
            Channel ch = b.bind("127.0.0.1",7398).sync().channel();
            //向目标端口发送信息
            String s = "你好端口7397的bugstack虫洞栈，我是客户端小爱，你在吗！";
            byte[] message = SerializeUtils.serialize(s);
            // 关键修改：使用Netty的DatagramPacket（DefaultDatagramPacket）
            ByteBuf byteBuf = Unpooled.copiedBuffer(message);
            ch.writeAndFlush(new DatagramPacket(byteBuf, new InetSocketAddress("127.0.0.1", 7397))).sync();

            ch.closeFuture().await();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            group.shutdownGracefully();
        }
    }

}