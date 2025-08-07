package com.pop.popcoinsystem;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class VirtualThreadDemo {
    public static void main(String[] args) throws InterruptedException {
        // 方式1：使用Thread.startVirtualThread()创建虚拟线程
        System.out.println("=== 方式1：使用startVirtualThread ===");
        Thread virtualThread1 = Thread.startVirtualThread(() -> {
            System.out.println("虚拟线程1运行中 - " + Thread.currentThread());
            try {
                // 模拟一些工作
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        virtualThread1.join();

        // 方式2：使用Thread.ofVirtual()构建器创建虚拟线程
        System.out.println("\n=== 方式2：使用ofVirtual()构建器 ===");
        Thread virtualThread2 = Thread.ofVirtual()
                .name("my-virtual-thread")
                .start(() -> {
                    System.out.println("虚拟线程2运行中 - " + Thread.currentThread());
                });
        virtualThread2.join();

        // 方式3：使用虚拟线程池
        System.out.println("\n=== 方式3：使用虚拟线程池 ===");
        // 创建一个为每个任务创建新虚拟线程的执行器
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            // 提交多个任务到虚拟线程池
            for (int i = 0; i < 5; i++) {
                final int taskNumber = i;
                executor.submit(() -> {
                    System.out.printf("任务 %d 运行在虚拟线程: %s%n",
                            taskNumber, Thread.currentThread());
                    // 模拟任务处理时间
                    Thread.sleep(50);
                    return taskNumber;
                });
            }
        } // 执行器会自动关闭

        System.out.println("\n所有虚拟线程执行完毕");
    }
}
