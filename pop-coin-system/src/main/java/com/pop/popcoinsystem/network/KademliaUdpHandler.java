package com.pop.popcoinsystem.network;

import com.pop.popcoinsystem.network.common.NodeInfo;
import com.pop.popcoinsystem.network.protocol.MessageType;
import com.pop.popcoinsystem.network.protocol.message.KademliaMessage;
import com.pop.popcoinsystem.network.protocol.message.PingKademliaMessage;
import com.pop.popcoinsystem.network.protocol.message.PongKademliaMessage;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;  //这里很容易变成java的 一定一定要注意
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.UUID;

import static java.lang.Thread.sleep;


@Slf4j
public class KademliaUdpHandler extends SimpleChannelInboundHandler<DatagramPacket> {
    private  KademliaNodeServer nodeServer;

    public KademliaUdpHandler(KademliaNodeServer nodeServer) {
        this.nodeServer = nodeServer;
    }

    public KademliaUdpHandler() {

    }

    @Override
    protected void channelRead0(ChannelHandlerContext channelHandlerContext, DatagramPacket datagramPacket) throws Exception {
        ByteBuf content = datagramPacket.content();
        //读取二进制消息
        // 1. 创建正确大小的字节数组
        byte[] bytes = new byte[content.readableBytes()];
        // 2. 将ByteBuf中的数据读取到字节数组中
        content.readBytes(bytes);
        KademliaMessage kademliaMessage = KademliaMessage.deSerialize(bytes);
        System.out.println("收到消息"+kademliaMessage.getSender().getUdpPort());
        System.out.println("消息类型"+kademliaMessage.getType()+"中文类型"+ MessageType.MessageTypeMap.get(kademliaMessage.getType()));

        //调用对应的处理器


        sleep(3000);

        PongKademliaMessage pongKademliaMessage = new PongKademliaMessage();
        pongKademliaMessage.setMessageId(UUID.randomUUID().toString());
        pongKademliaMessage.setSender(nodeServer.getNodeInfo());
        pongKademliaMessage.setReceiver(kademliaMessage.getSender());
        pongKademliaMessage.setTimestamp(System.currentTimeMillis());
        // 序列化响应消息
        byte[] responseBytes = PongKademliaMessage.serialize(pongKademliaMessage);
        // 创建响应数据包并发送
        ByteBuf byteBuf = Unpooled.copiedBuffer(responseBytes);
        InetSocketAddress inetSocketAddress = new InetSocketAddress(pongKademliaMessage.getReceiver().getIpv4(), pongKademliaMessage.getReceiver().getUdpPort());
        DatagramPacket responsePacket = new DatagramPacket(byteBuf, inetSocketAddress);
        channelHandlerContext.writeAndFlush(responsePacket);
    }

/*
    @Override
    protected void channelRead0(ChannelHandlerContext channelHandlerContext, KademliaMessage message) throws Exception {
        log.info("收到消息"+ message);
    }
*/




    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        cause.printStackTrace();
        ctx.close();
    }



}
