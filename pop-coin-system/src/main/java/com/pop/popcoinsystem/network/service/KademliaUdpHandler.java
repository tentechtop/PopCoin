package com.pop.popcoinsystem.network.service;

import com.pop.popcoinsystem.network.protocol.message.KademliaMessage;
import com.pop.popcoinsystem.network.protocol.messageHandler.MessageHandler;
import com.pop.popcoinsystem.network.rpc.RequestResponseManager;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;


import static com.pop.popcoinsystem.network.service.KademliaNodeServer.KademliaMessageHandler;
import static java.lang.Thread.sleep;


@Slf4j
public class KademliaUdpHandler extends SimpleChannelInboundHandler<KademliaMessage> {
    private final KademliaNodeServer nodeServer;
    private final UDPClient udpClient;
    private RequestResponseManager responseManager;

    public KademliaUdpHandler(KademliaNodeServer nodeServer,UDPClient udpClient) {
        if (nodeServer == null) {
            throw new NullPointerException("传入的KademliaNodeServer为null！请检查是否正确传入实例");
        }
        this.nodeServer = nodeServer;
        this.udpClient = udpClient;
        // 初始化响应管理器（从TCPClient获取全局唯一实例）
        this.responseManager = udpClient.getResponseManager();
    }


    @Override
    protected void channelRead0(ChannelHandlerContext ctx, KademliaMessage message) throws Exception {
        long messageId = message.getMessageId();
        // 检查：若消息已存在（未过期），则跳过广播
        if (nodeServer.getBroadcastMessages().getIfPresent(messageId) != null) {
            log.debug("消息,或者交易 {} 已处理过（未过期），跳过", messageId);
            return;
        }
        // 记录：将消息ID存入缓存（自动过期）
        nodeServer.getBroadcastMessages().put(messageId, Boolean.TRUE);

        boolean single = message.isSingle();
        if (single){
            //单播消息
            long requestId = message.getRequestId();
            if (message.isResponse()){
                log.debug("响应消息ID {}", requestId);
                // 响应消息：交给RequestResponseManager处理，完成客户端的Promise
                handleResponseMessage(ctx, message);
            }else {
                log.debug("收到请求消息，requestId: {}", requestId);
                // 处理请求消息并生成响应
                handleRequestMessage(ctx, message);
            }
        }else {
            //广播消息
            MessageHandler messageHandler = KademliaMessageHandler.get(message.getType());
            messageHandler.handleMesage(nodeServer, message);
        }





        MessageHandler messageHandler = KademliaMessageHandler.get(message.getType());
        KademliaMessage<? extends Serializable> kademliaMessage = messageHandler.handleMesage(nodeServer, message);
        if (kademliaMessage != null){
            nodeServer.getUdpClient().sendMessage(kademliaMessage);
        }
    }

    /**
     * 处理响应消息：分发给对应的RequestResponseManager，触发客户端Promise
     */
    private void handleResponseMessage(ChannelHandlerContext ctx, KademliaMessage response) {
        if (responseManager == null) {
            log.error("响应管理器未初始化，无法处理响应消息");
            return;
        }
        long requestId = response.getRequestId();
        try {
            // 核心逻辑：通过requestId匹配等待中的请求并完成Promise
            responseManager.handleResponse(response);
            log.debug("响应消息 requestId={} 已成功处理", requestId);
        } catch (Exception e) {
            log.error("处理响应消息 requestId={} 时发生异常", requestId, e);
        }
        log.debug("响应消息 {} 已交给RequestResponseManager处理", response.getMessageId());
    }



    /**
     * 处理请求消息：调用对应的处理器并发送响应
     */
    private void handleRequestMessage(ChannelHandlerContext ctx, KademliaMessage message) {
        try {
            MessageHandler messageHandler = KademliaMessageHandler.get(message.getType());
            KademliaMessage<? extends Serializable> response = messageHandler.handleMesage(nodeServer, message);
            if (response != null) {
                // 标记为响应消息
                response.setResponse(true);
                nodeServer.getTcpClient().sendMessage(response);//已经优化 会复用通道
            }
        } catch (Exception e) {
            log.error("处理请求消息 {} 时发生异常", message.getRequestId(), e);
        }
    }




    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        cause.printStackTrace();
        ctx.close();
    }


}
