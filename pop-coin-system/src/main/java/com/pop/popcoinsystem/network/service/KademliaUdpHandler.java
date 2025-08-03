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
        long messageId = message.getMessageId();
        // 检查：若消息已存在（未过期），则跳过广播
        if (nodeServer.getBroadcastMessages().getIfPresent(messageId) != null) {
            log.debug("消息,或者交易 {} 已处理过（未过期），跳过", messageId);
            return;
        }
        // 记录：将消息ID存入缓存（自动过期）
        nodeServer.getBroadcastMessages().put(messageId, Boolean.TRUE);
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
