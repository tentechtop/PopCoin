package com.pop.popcoinsystem.network;

import com.pop.popcoinsystem.network.protocol.MessageType;
import com.pop.popcoinsystem.network.protocol.message.KademliaMessage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class KademliaTcpHandler extends SimpleChannelInboundHandler<KademliaMessage> {

    private final KademliaNodeServer nodeServer;


    public KademliaTcpHandler(KademliaNodeServer nodeServer) {
        this.nodeServer = nodeServer;
    }



    @Override
    protected void channelRead0(ChannelHandlerContext channelHandlerContext, KademliaMessage message) throws Exception {
        log.info("TCP数据处理:{}", message);

    }




    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        cause.printStackTrace();
        ctx.close();
    }



}
