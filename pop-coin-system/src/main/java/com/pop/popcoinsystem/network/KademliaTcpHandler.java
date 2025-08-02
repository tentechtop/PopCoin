package com.pop.popcoinsystem.network;

import com.pop.popcoinsystem.network.common.NodeInfo;
import com.pop.popcoinsystem.network.protocol.MessageType;
import com.pop.popcoinsystem.network.protocol.message.KademliaMessage;
import com.pop.popcoinsystem.network.protocol.messageHandler.MessageHandler;
import com.pop.popcoinsystem.network.service.RequestResponseManager;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
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

    // 持有TCPClient引用，用于获取RequestResponseManager
    private final TCPClient tcpClient;


    public KademliaTcpHandler(KademliaNodeServer nodeServer,TCPClient tcpClient) {
        if (nodeServer == null) {
            throw new NullPointerException("传入的KademliaNodeServer为null！请检查是否正确传入实例");
        }
        this.nodeServer = nodeServer;
        this.tcpClient = tcpClient;
    }



    @Override
    protected void channelRead0(ChannelHandlerContext ctx, KademliaMessage message) throws Exception {
        long messageId = message.getMessageId();
        if (nodeServer.getBroadcastMessages().getIfPresent(messageId) != null) {
            log.info("TCP接收已处理的消息 {}，丢弃", messageId);
            return;
        }
        // 记录：标记为已处理
        nodeServer.getBroadcastMessages().put(messageId, Boolean.TRUE);

        boolean single = message.isSingle();
        if (single){
            //单播消息
            long requestId = message.getRequestId();
            log.info("TCP接收单播消息 {}", messageId);
            if (message.isResponse()){
                log.info("响应消息ID {}", requestId);
                // 2.1 响应消息：交给RequestResponseManager处理，完成客户端的Promise
                handleResponseMessage(ctx, message);
            }else {
                log.info("请求消息ID {}", requestId);
                MessageHandler messageHandler = KademliaMessageHandler.get(message.getType());
                KademliaMessage<? extends Serializable> kademliaMessage = messageHandler.handleMesage(nodeServer, message);
                if (kademliaMessage != null){
                    //响应
                    nodeServer.getTcpClient().sendMessage(kademliaMessage);
                }
            }
        }else {
            //广播消息
            MessageHandler messageHandler = KademliaMessageHandler.get(message.getType());
            messageHandler.handleMesage(nodeServer, message);
        }
    }








    /**
     * 处理响应消息：分发给对应的RequestResponseManager，触发客户端Promise
     */
    private void handleResponseMessage(ChannelHandlerContext ctx, KademliaMessage response) {
        // 从通道获取对应的RequestResponseManager
        RequestResponseManager responseManager = tcpClient.getChannelToResponseManager().get(ctx.channel());
        if (responseManager == null) {
            log.warn("未找到通道对应的RequestResponseManager，响应消息 {} 无法处理", response.getMessageId());
            return;
        }
        // 调用handleResponse，完成Promise
        responseManager.handleResponse(response);
        log.debug("响应消息 {} 已交给RequestResponseManager处理", response.getMessageId());
    }







    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        cause.printStackTrace();
        ctx.close();
    }





}
