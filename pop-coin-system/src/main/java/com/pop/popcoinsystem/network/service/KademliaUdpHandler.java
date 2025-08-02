package com.pop.popcoinsystem.network.service;

import com.pop.popcoinsystem.network.protocol.message.KademliaMessage;
import com.pop.popcoinsystem.network.protocol.messageHandler.MessageHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;


import static com.pop.popcoinsystem.network.service.KademliaNodeServer.KademliaMessageHandler;
import static java.lang.Thread.sleep;


@Slf4j
public class KademliaUdpHandler extends SimpleChannelInboundHandler<KademliaMessage> {
    private  KademliaNodeServer nodeServer;

    public KademliaUdpHandler(KademliaNodeServer nodeServer) {
        this.nodeServer = nodeServer;
    }

    public KademliaUdpHandler() {}

    @Override
    protected void channelRead0(ChannelHandlerContext channelHandlerContext, KademliaMessage message) throws Exception {
        MessageHandler messageHandler = KademliaMessageHandler.get(message.getType());
        KademliaMessage<? extends Serializable> kademliaMessage = messageHandler.handleMesage(nodeServer, message);
        if (kademliaMessage != null){
            nodeServer.getUdpClient().sendMessage(kademliaMessage);
        }
    }


    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        cause.printStackTrace();
        ctx.close();
    }


}
