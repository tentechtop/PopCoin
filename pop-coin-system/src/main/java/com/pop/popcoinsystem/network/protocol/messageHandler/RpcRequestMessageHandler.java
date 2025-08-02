package com.pop.popcoinsystem.network.protocol.messageHandler;

import com.pop.popcoinsystem.network.KademliaNodeServer;
import com.pop.popcoinsystem.network.common.NodeInfo;
import com.pop.popcoinsystem.network.protocol.message.KademliaMessage;
import com.pop.popcoinsystem.network.protocol.message.RpcRequestMessage;
import com.pop.popcoinsystem.network.protocol.message.RpcResponseMessage;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import java.io.Serializable;
import java.lang.reflect.Method;

@Slf4j
public class RpcRequestMessageHandler implements MessageHandler {


    @Override
    public KademliaMessage<? extends Serializable> handleMesage(KademliaNodeServer kademliaNodeServer, KademliaMessage<?> message) throws InterruptedException {
        return doHandle(kademliaNodeServer, (RpcRequestMessage) message);
    }


    protected RpcResponseMessage doHandle(KademliaNodeServer kademliaNodeServer, @NotNull RpcRequestMessage rpcRequest) throws InterruptedException {
        log.info("收到RpcRequest -> 调用请求");
        NodeInfo me = kademliaNodeServer.getNodeInfo();
        NodeInfo sender = rpcRequest.getSender();

        long requestId = rpcRequest.getRequestId();
        RpcResponseMessage response = new RpcResponseMessage();
        response.setRequestId(requestId);
        response.setSender(me);
        response.setReceiver(sender);

        try {
            // 从 RpcRequestData 中获取调用信息
            String serviceName = rpcRequest.getServiceName();
            String methodName = rpcRequest.getMethodName();
            Class<?>[] paramTypes = rpcRequest.getParamTypes();
            Object[] parameters = rpcRequest.getParameters();

            log.info("RPC调用开始 -> 请求ID: {}, 服务: {}, 方法: {}", requestId, serviceName, methodName);


            // 验证必要参数
            if (serviceName == null || methodName == null) {
                throw new IllegalArgumentException("服务名和方法名不能为空");
            }
            // 获取注册的服务实例
            Object service = kademliaNodeServer.getRpcServiceRegistry().getService(serviceName);
            if (service == null) {
                throw new RuntimeException("未找到服务: " + serviceName);
            }

            // 反射查找方法
            Method method = null;
            try {
                method = service.getClass().getMethod(methodName, paramTypes);
            } catch (NoSuchMethodException e) {
                // 尝试查找所有方法，包括父类和接口中的方法
                Method[] methods = service.getClass().getMethods();
                for (Method m : methods) {
                    if (m.getName().equals(methodName) &&
                            matchParameterTypes(m.getParameterTypes(), paramTypes)) {
                        method = m;
                        break;
                    }
                }
                if (method == null) {
                    throw new NoSuchMethodException("服务 " + serviceName + " 中未找到方法: " + methodName);
                }
            }
            // 执行方法调用
            long startTime = System.currentTimeMillis();
            Object result = method.invoke(service, parameters);
            long endTime = System.currentTimeMillis();
            log.info("RPC调用成功 -> 请求ID: {}, 耗时: {}ms", requestId, (endTime - startTime));

            // 设置返回结果
            if (result instanceof Serializable) {
                response.setResult((Serializable) result);
            } else if (result != null) {
                // 如果结果不可序列化，尝试转换为字符串或处理
                log.warn("RPC返回结果不可序列化，将转换为字符串: {}", result.getClass().getName());
                response.setResult(result.toString());
            }
        } catch (Exception e) {
            response.setException(e);
        }
        return response;
    }


    /**
     * 检查参数类型是否匹配（考虑自动装箱/拆箱和类型继承）
     */
    private boolean matchParameterTypes(Class<?>[] declaredTypes, Class<?>[] actualTypes) {
        if (declaredTypes.length != actualTypes.length) {
            return false;
        }

        for (int i = 0; i < declaredTypes.length; i++) {
            Class<?> declared = declaredTypes[i];
            Class<?> actual = actualTypes[i];

            // 检查是否为基本类型与包装类型的匹配
            if ((declared.isPrimitive() && isWrapperType(actual, declared)) ||
                    (actual.isPrimitive() && isWrapperType(declared, actual))) {
                continue;
            }

            // 检查类型是否兼容
            if (!declared.isAssignableFrom(actual)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 检查包装类型是否匹配基本类型
     */
    private boolean isWrapperType(Class<?> wrapperType, Class<?> primitiveType) {
        if (primitiveType == int.class && wrapperType == Integer.class) return true;
        if (primitiveType == boolean.class && wrapperType == Boolean.class) return true;
        if (primitiveType == long.class && wrapperType == Long.class) return true;
        if (primitiveType == double.class && wrapperType == Double.class) return true;
        if (primitiveType == float.class && wrapperType == Float.class) return true;
        if (primitiveType == short.class && wrapperType == Short.class) return true;
        if (primitiveType == byte.class && wrapperType == Byte.class) return true;
        if (primitiveType == char.class && wrapperType == Character.class) return true;
        return false;
    }

}
