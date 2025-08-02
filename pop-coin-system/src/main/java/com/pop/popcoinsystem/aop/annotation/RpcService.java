package com.pop.popcoinsystem.aop.annotation;

import java.lang.annotation.*;

/**
 * 标记需要注册到RPC的服务接口
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RpcService {

}