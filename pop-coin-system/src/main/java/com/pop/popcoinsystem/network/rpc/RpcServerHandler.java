package com.pop.popcoinsystem.network.rpc;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import java.lang.reflect.Method;
import java.util.Map;

// 服务端处理器：反射调用本地方法
public class RpcServerHandler extends SimpleChannelInboundHandler<RpcRequest> {
    private final Map<String, Object> serviceMap;

    public RpcServerHandler(Map<String, Object> serviceMap) {
        this.serviceMap = serviceMap;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, RpcRequest request) {
        RpcResponse response = new RpcResponse();
        response.setRequestId(request.getRequestId()); // 复制请求ID到响应中
        try {
            // 1. 从请求中获取调用信息
            String interfaceName = request.getInterfaceName();
            String methodName = request.getMethodName();
            Class<?>[] paramTypes = request.getParamTypes();
            Object[] parameters = request.getParameters();

            // 2. 查找本地服务实现类
            Object service = serviceMap.get(interfaceName);
            if (service == null) {
                throw new RuntimeException("服务未找到：" + interfaceName);
            }

            // 3. 反射调用本地方法
            Method method = service.getClass().getMethod(methodName, paramTypes);
            Object result = method.invoke(service, parameters);

            // 4. 封装响应结果
            response.setResult(result);
        } catch (Exception e) {
            response.setException(e);
        }
        // 5. 发送响应
        ctx.writeAndFlush(response);
    }
}