package com.pop.popcoinsystem.network.rpc;

// 本地服务实现（服务端）
public class HelloServiceImpl implements HelloService {
    @Override
    public String sayHello(String name) {
        return "Hello, " + name + "!";
    }
}