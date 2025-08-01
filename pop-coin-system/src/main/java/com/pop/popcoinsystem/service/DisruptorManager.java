package com.pop.popcoinsystem.service;


import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.pop.popcoinsystem.data.transaction.Transaction;
import com.pop.popcoinsystem.network.protocol.messageHandler.TransactionEvent;
import com.pop.popcoinsystem.network.protocol.messageHandler.TransactionEventFactory;
import com.pop.popcoinsystem.service.BlockChainService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Disruptor管理器，负责创建和管理Disruptor实例
 * 解耦TransactionMessageHandler和BlockChainService的直接依赖
 */
@Data
@Component
public class DisruptorManager {
    // 环形缓冲区大小（必须是2的幂）
    private static final int BUFFER_SIZE = 1024;

    private Disruptor<TransactionEvent> disruptor;
    private ExecutorService executor;

    @Lazy
    @Autowired
    private BlockChainService blockChainService;

    public DisruptorManager() {

    }

    /**
     * 初始化Disruptor
     */
    @PostConstruct
    public void init() {
/*        // 创建线程池
        executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

        // 创建事件工厂
        TransactionEventFactory eventFactory = new TransactionEventFactory();

        // 构建Disruptor
        disruptor = new Disruptor<>(
                eventFactory,
                BUFFER_SIZE,
                executor,
                ProducerType.MULTI,  // 支持多生产者
                new BlockingWaitStrategy()
        );

        // 注册事件处理器（使用BlockChainService作为处理器）
        //disruptor.handleEventsWith(blockChainService);

        // 启动Disruptor
        disruptor.start();*/
    }

    /**
     * 发布交易事件
     */
    public void publishTransaction(Transaction transaction) {
        if (disruptor == null) {
            throw new IllegalStateException("Disruptor尚未初始化");
        }

        RingBuffer<TransactionEvent> ringBuffer = disruptor.getRingBuffer();
        long sequence = ringBuffer.next();
        try {
            TransactionEvent event = ringBuffer.get(sequence);
            event.setTransaction(transaction);
        } finally {
            ringBuffer.publish(sequence);
        }
    }

    /**
     * 销毁资源
     */
    @PreDestroy
    public void destroy() {
        if (disruptor != null) {
            disruptor.shutdown();
        }
        if (executor != null) {
            executor.shutdown();
        }
    }
}
