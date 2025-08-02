package com.pop.popcoinsystem.network;

import com.pop.popcoinsystem.network.common.NodeInfo;
import com.pop.popcoinsystem.network.protocol.MessageType;
import com.pop.popcoinsystem.network.protocol.message.KademliaMessage;
import com.pop.popcoinsystem.network.protocol.messageHandler.MessageHandler;
import com.pop.popcoinsystem.network.service.RequestResponseManager;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;

import static com.pop.popcoinsystem.network.KademliaNodeServer.KademliaMessageHandler;
import static com.pop.popcoinsystem.network.KademliaNodeServer.MESSAGE_EXPIRATION_TIME;

@Slf4j
public class KademliaTcpHandler extends SimpleChannelInboundHandler<KademliaMessage> {

    private final KademliaNodeServer nodeServer;


    public KademliaTcpHandler(KademliaNodeServer nodeServer) {
        if (nodeServer == null) {
            throw new NullPointerException("传入的KademliaNodeServer为null！请检查是否正确传入实例");
        }
        this.nodeServer = nodeServer;
    }



    @Override
    protected void channelRead0(ChannelHandlerContext channelHandlerContext, KademliaMessage message) throws Exception {
        long messageId = message.getMessageId();
        boolean single = message.isSingle();
        if (single){
            //单播消息
            log.info("TCP接收单播消息 {}", messageId);
            if (message.isResponse()){
                log.info("TCP接收单播响应消息 {}", messageId);

            }else {
                log.info("TCP接收单播请求消息 {}", messageId);
                
            }
        }


        if (nodeServer.getBroadcastMessages().getIfPresent(messageId) != null) {
            log.info("TCP接收已处理的消息 {}，丢弃", messageId);
            return;
        }
        // 记录：标记为已处理
        nodeServer.getBroadcastMessages().put(messageId, Boolean.TRUE);
        MessageHandler messageHandler = KademliaMessageHandler.get(message.getType());
        KademliaMessage<? extends Serializable> kademliaMessage = messageHandler.handleMesage(nodeServer, message);
        if (kademliaMessage != null){
            nodeServer.getTcpClient().sendMessage(kademliaMessage);
        }
    }




    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        cause.printStackTrace();
        ctx.close();
    }



}
