package com.pop.popcoinsystem.network.protocol;

import com.pop.popcoinsystem.network.rpc.RpcServiceRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.util.Map;

@Slf4j
@Service
public class RpcInvoker {
    private final RpcServiceRegistry rpcServiceRegistry;

    public RpcInvoker(RpcServiceRegistry rpcServiceRegistry) {
        this.rpcServiceRegistry = rpcServiceRegistry;
    }

    /**
     * 执行RPC调用
     * @param serviceName 服务名称
     * @param methodName 方法名称
     * @param paramTypes 参数类型
     * @param parameters 参数值
     * @param requestId 请求ID
     * @return 调用结果
     * @throws Exception 调用过程中可能发生的异常
     */
    public Object invoke(String serviceName, String methodName,
                         Class<?>[] paramTypes, Object[] parameters,
                         long requestId) throws Exception {
        // 验证必要参数
        if (serviceName == null || methodName == null) {
            throw new IllegalArgumentException("服务名和方法名不能为空");
        }

        // 获取注册的服务实例
        Map<String, Object> serviceList = rpcServiceRegistry.getService();
        log.info("服务实例: {}", serviceList);

        Object service = rpcServiceRegistry.getService(serviceName);
        if (service == null) {
            throw new RuntimeException("未找到服务: " + serviceName);
        }

        // 查找方法
        Method method = findMethod(service, methodName, paramTypes);

        // 执行方法调用并计时
        long startTime = System.currentTimeMillis();
        Object result = method.invoke(service, parameters);
        long endTime = System.currentTimeMillis();

        log.info("RPC调用成功 -> 请求ID: {}, 耗时: {}ms", requestId, (endTime - startTime));

        return result;
    }

    /**
     * 查找匹配的方法
     */
    private Method findMethod(Object service, String methodName, Class<?>[] paramTypes) throws NoSuchMethodException {
        Method method = null;
        try {
            // 首先尝试直接获取方法
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
                throw new NoSuchMethodException("服务 " + service.getClass().getName() +
                        " 中未找到方法: " + methodName);
            }
        }
        return method;
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
