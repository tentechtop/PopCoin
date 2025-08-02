package com.pop.popcoinsystem.service.transaction;

import com.pop.popcoinsystem.aop.annotation.RpcService;
import com.pop.popcoinsystem.aop.annotation.RpcServiceAlias;

import java.io.Serializable;
@RpcServiceAlias("TransactionService")// 标记为需要注册的RPC服务
public interface TransactionService extends Serializable {

    String sayHello(String name);
}
