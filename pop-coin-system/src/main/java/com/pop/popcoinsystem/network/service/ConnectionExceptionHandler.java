package com.pop.popcoinsystem.network.service;

import com.pop.popcoinsystem.network.service.TCPClient;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;
import lombok.extern.slf4j.Slf4j;

import java.math.BigInteger;
import java.net.SocketException;
import java.util.Map;

@Slf4j
public class ConnectionExceptionHandler extends ChannelDuplexHandler {

    // 用于存储节点ID的属性键（从TCPClient中复用）
    private static final AttributeKey<BigInteger> NODE_ID_KEY = TCPClient.NODE_ID_KEY;

    // 节点ID到Channel的映射（通过构造函数注入）
    private final Map<BigInteger, io.netty.channel.Channel> nodeTCPChannel;

    // Kademlia节点服务器（通过构造函数注入）
    private final KademliaNodeServer kademliaNodeServer;

    /**
     * 构造函数，注入必要的依赖
     */
    public ConnectionExceptionHandler(
            Map<BigInteger, io.netty.channel.Channel> nodeTCPChannel,
            KademliaNodeServer kademliaNodeServer) {
        this.nodeTCPChannel = nodeTCPChannel;
        this.kademliaNodeServer = kademliaNodeServer;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        // 处理 Connection reset 异常
        if (cause instanceof SocketException) {
            handleSocketException(ctx, (SocketException) cause);
            return;
        } else {
            // 其他异常交给后续处理器
            super.exceptionCaught(ctx, cause);
        }
    }

    /**
     * 专门处理SocketException的方法
     */
    private void handleSocketException(ChannelHandlerContext ctx, SocketException e) {
        String errorMessage = e.getMessage();
        BigInteger nodeId = ctx.channel().attr(NODE_ID_KEY).get();

        // 处理连接重置异常
        if (errorMessage != null && errorMessage.contains("Connection reset")) {
            log.warn("节点 {} 发生连接重置（Connection reset），下线节点并关闭通道", nodeId, e);
            closeChannelAndCleanup(ctx, nodeId);
            return;
        }

        // 处理其他Socket异常（可根据需要扩展）
        log.warn("节点 {} 发生Socket异常: {}", nodeId, errorMessage, e);

        // 判断是否需要关闭通道（例如连接被拒绝、端口未开放等）
        if (errorMessage != null && (
                errorMessage.contains("Connection refused") ||
                        errorMessage.contains("Broken pipe") ||
                        errorMessage.contains("No route to host")
        )) {
            log.warn("节点 {} 发生不可恢复的Socket异常，关闭通道", nodeId);
            closeChannelAndCleanup(ctx, nodeId);
            return; // 处理完成，终止
        } else {
            // 不处理的异常传递给下一个处理器
            try {
                log.warn("传递异常给下一个处理器", e);
                super.exceptionCaught(ctx, e);
            } catch (Exception ex) {
                log.error("传递异常时发生错误", ex);
            }
        }
    }

    /**
     * 关闭通道并清理相关资源
     */
    private void closeChannelAndCleanup(ChannelHandlerContext ctx, BigInteger nodeId) {
        // 关闭通道（异步操作）
        ctx.channel().close().addListener(future -> {
            if (future.isSuccess()) {
                log.debug("节点 {} 的通道已成功关闭", nodeId);
            } else {
                log.error("节点 {} 的通道关闭失败", nodeId, future.cause());
            }
        });

        // 从映射中移除节点
        if (nodeId != null) {
            nodeTCPChannel.remove(nodeId);
            // 标记节点下线
            kademliaNodeServer.offlineNode(nodeId);
        }
    }
}

