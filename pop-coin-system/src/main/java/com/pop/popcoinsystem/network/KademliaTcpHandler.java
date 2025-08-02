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
    // 响应管理器引用（通过TCPClient获取全局实例）
    private RequestResponseManager responseManager;

    public KademliaTcpHandler(KademliaNodeServer nodeServer,TCPClient tcpClient) {
        if (nodeServer == null) {
            throw new NullPointerException("传入的KademliaNodeServer为null！请检查是否正确传入实例");
        }
        this.nodeServer = nodeServer;
        this.tcpClient = tcpClient;
        // 初始化响应管理器（从TCPClient获取全局唯一实例）
        this.responseManager = tcpClient.getResponseManager();
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
            if (message.isResponse()){
                log.info("响应消息ID {}", requestId);
                // 2.1 响应消息：交给RequestResponseManager处理，完成客户端的Promise
                log.info("响应内容 {}", message.getData());
                handleResponseMessage(ctx, message);
            }else {
                log.info("收到请求消息，requestId: {}", requestId);
                // 处理请求消息并生成响应
                handleRequestMessage(ctx, message);
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
                // 通过当前通道直接回复，避免再次查找通道
                ctx.channel().writeAndFlush(response).addListener(future -> {
                    if (future.isSuccess()) {
                        log.info("请求消息 {} 的响应已发送", message.getRequestId());
                    } else {
                        log.error("请求消息 {} 的响应发送失败", message.getRequestId(), future.cause());
                    }
                });
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
