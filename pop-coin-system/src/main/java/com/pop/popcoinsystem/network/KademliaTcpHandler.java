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


    /**
     * 通道激活时（首次建立连接），初始化RequestResponseManager
     */
    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
        Channel channel = ctx.channel();
        // 确保通道一定有对应的RequestResponseManager
        tcpClient.getChannelToResponseManager().computeIfAbsent(
                channel,
                k -> new RequestResponseManager(channel)  // 自动创建并关联
        );
        log.info("Channel激活，已为通道[{}]初始化RequestResponseManager", channel.remoteAddress());
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
        Channel channel = ctx.channel();
        RequestResponseManager responseManager = tcpClient.getChannelToResponseManager().get(ctx.channel());
        // 容错：如果没有找到，临时创建一个（避免消息丢失）
        if (responseManager == null) {
            log.warn("通道[{}]未找到RequestResponseManager，临时创建处理响应[{}]",
                    channel.remoteAddress(), response.getMessageId());
            responseManager = new RequestResponseManager(channel);
            // 可选：临时关联到map，后续由通道关闭逻辑清理
            tcpClient.getChannelToResponseManager().put(channel, responseManager);
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
