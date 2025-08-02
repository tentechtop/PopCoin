package com.pop.popcoinsystem.network.rpc;

public class ClientDemo {
    public static void main(String[] args) {
// 1. 先启动服务端（在独立线程中，避免阻塞客户端）
        new Thread(() -> {
            try {
                RpcServer rpcServer = new RpcServer(8080);
                rpcServer.start(); // 启动服务端，绑定 8080 端口
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }).start();

        // 2. 等待服务端启动完成（简单延迟，实际中可通过更优雅的方式判断）
        try {
            Thread.sleep(1000); // 给服务端启动留时间
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        // 创建代理对象（屏蔽网络细节）
        RpcClientProxy proxy = new RpcClientProxy("localhost", 8080);
        HelloService helloService = proxy.getProxy(HelloService.class);

        // 像调用本地方法一样调用远程服务
        String result = helloService.sayHello("这样我就能拿到返回值了");
        System.out.println(result); // 输出：Hello, Netty!
    }
}