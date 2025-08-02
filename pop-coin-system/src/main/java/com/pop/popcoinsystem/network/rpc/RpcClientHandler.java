package com.pop.popcoinsystem.network.rpc;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.concurrent.Promise;

import java.util.Map;

// 客户端处理器：接收响应并设置到 Promise
public class RpcClientHandler extends SimpleChannelInboundHandler<RpcResponse> {
    private final Map<String, Promise<Object>> requestMap;

    public RpcClientHandler(Map<String, Promise<Object>> requestMap) {
        this.requestMap = requestMap;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, RpcResponse response) {
        String requestId = response.getRequestId(); // 需在响应中携带请求ID
        Promise<Object> promise = requestMap.remove(requestId);
        if (promise != null) {
            if (response.getException() != null) {
                promise.setFailure(response.getException());
            } else {
                promise.setSuccess(response.getResult());
            }
        }
    }
}