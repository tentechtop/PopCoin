package com.pop.popcoinsystem;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class VirtualThreadWaitDemo {
    public static void main(String[] args) throws Exception {
        // 方式1：使用Thread.join()等待虚拟线程完成
        System.out.println("=== 方式1：使用join()等待虚拟线程 ===");
        Thread vt1 = Thread.startVirtualThread(() -> {
            try {
                System.out.println("虚拟线程1开始工作");
                TimeUnit.SECONDS.sleep(1); // 模拟任务耗时
                System.out.println("虚拟线程1完成工作");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        vt1.join(); // 等待虚拟线程完成

        System.out.println("虚拟线程1已结束\n");

        // 方式2：与CompletableFuture结合（使用虚拟线程池）
        System.out.println("=== 方式2：与CompletableFuture结合 ===");
        // 创建虚拟线程池

        ExecutorService virtualExecutor1 = Executors.newVirtualThreadPerTaskExecutor();


        try (ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            // 提交任务到虚拟线程池，返回CompletableFuture
            CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
                try {
                    System.out.println("CompletableFuture任务开始（虚拟线程）");
                    TimeUnit.SECONDS.sleep(1); // 模拟任务耗时
                    return "任务结果";
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return "任务被中断";
                }
            }, virtualExecutor); // 指定使用虚拟线程池

            // 等待结果（同步等待）
            String result = future.get(); // 阻塞等待完成
            System.out.println("获取到结果：" + result);

            // 异步等待（非阻塞，使用回调）
            CompletableFuture<Void> asyncFuture = future.thenAcceptAsync(res -> {
                System.out.println("异步处理结果：" + res);
            }, virtualExecutor);

            asyncFuture.get(); // 等待异步回调完成
        } // 虚拟线程池自动关闭

        System.out.println("\n所有操作完成");
    }
}
