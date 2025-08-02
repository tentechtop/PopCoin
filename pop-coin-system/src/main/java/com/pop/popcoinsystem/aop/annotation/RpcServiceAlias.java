package com.pop.popcoinsystem.aop.annotation;

import java.lang.annotation.*;

/**
 * 为RPC服务接口指定简短别名
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface RpcServiceAlias {
    String value(); // 服务别名（如"TransactionService"）
}