package com.pop.popcoinsystem.network;

import com.pop.popcoinsystem.network.common.NodeInfo;
import com.pop.popcoinsystem.network.protocol.MessageType;
import com.pop.popcoinsystem.network.protocol.message.KademliaMessage;
import com.pop.popcoinsystem.network.protocol.message.PingKademliaMessage;
import com.pop.popcoinsystem.network.protocol.message.PongKademliaMessage;
import com.pop.popcoinsystem.network.protocol.messageHandler.MessageHandler;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;  //这里很容易变成java的 一定一定要注意
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.net.InetSocketAddress;
import java.util.UUID;


import static com.pop.popcoinsystem.network.KademliaNodeServer.KademliaMessageHandler;
import static java.lang.Thread.sleep;


@Slf4j
public class KademliaUdpHandler extends SimpleChannelInboundHandler<KademliaMessage> {
    private  KademliaNodeServer nodeServer;

    public KademliaUdpHandler(KademliaNodeServer nodeServer) {
        this.nodeServer = nodeServer;
    }

    public KademliaUdpHandler() {

    }

    @Override
    protected void channelRead0(ChannelHandlerContext channelHandlerContext, KademliaMessage message) throws Exception {
        //如果是PING消息就回复PONG
        MessageHandler messageHandler = KademliaMessageHandler.get(message.getType());
        messageHandler.handleMesage(nodeServer, message);
    }












    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        cause.printStackTrace();
        ctx.close();
    }



}
