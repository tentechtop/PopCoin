package com.pop.popcoinsystem.network;

import com.pop.popcoinsystem.network.common.NodeInfo;
import com.pop.popcoinsystem.network.protocol.MessageType;
import com.pop.popcoinsystem.network.protocol.message.KademliaMessage;
import com.pop.popcoinsystem.network.protocol.messageHandler.MessageHandler;
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
        log.info("TCP接收 {}", nodeServer.getNodeInfo());

        if (nodeServer.getBroadcastMessages().getIfPresent(messageId) != null) {
            log.info("TCP接收已处理的消息 {}，丢弃", messageId);
            return;
        }
        // 记录：标记为已处理
        nodeServer.getBroadcastMessages().put(messageId, Boolean.TRUE);
        MessageHandler messageHandler = KademliaMessageHandler.get(message.getType());
        KademliaMessage<? extends Serializable> kademliaMessage = messageHandler.handleMesage(nodeServer, message);
        if (kademliaMessage != null){
            log.info("一问一答 {}", kademliaMessage);
            ChannelFuture channelFuture = channelHandlerContext.writeAndFlush(kademliaMessage);
            channelFuture.addListener(future -> {
                if (future.isSuccess()) {
                    log.info("TCP发送成功 {}", kademliaMessage.getMessageId());
                } else {
                    log.info("TCP发送失败 {}", kademliaMessage.getMessageId());
                }
            });
        }
    }




    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        cause.printStackTrace();
        ctx.close();
    }



}
