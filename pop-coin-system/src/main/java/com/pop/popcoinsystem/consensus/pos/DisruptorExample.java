package com.pop.popcoinsystem.consensus.pos;

import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.EventFactory;
import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DisruptorExample {

    // 1. 定义事件类
    static class Event {
        private String message;

        public void setMessage(String message) {
            this.message = message;
        }

        @Override
        public String toString() {
            return "Event{message='" + message + "'}";
        }
    }

    // 2. 事件工厂
    static class EventFactoryImpl implements EventFactory<Event> {
        @Override
        public Event newInstance() {
            return new Event();
        }
    }

    // 3. 事件处理器
    static class EventHandlerImpl implements EventHandler<Event> {
        @Override
        public void onEvent(Event event, long sequence, boolean endOfBatch) {
            System.out.println(Thread.currentThread().getName() + " 处理事件: " + event);
            // 模拟业务处理
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // 4. 事件生产者
    static class EventProducer {
        private final RingBuffer<Event> ringBuffer;

        public EventProducer(RingBuffer<Event> ringBuffer) {
            this.ringBuffer = ringBuffer;
        }

        public void publishEvent(String message) {
            long sequence = ringBuffer.next();
            try {
                Event event = ringBuffer.get(sequence);
                event.setMessage(message);
            } finally {
                ringBuffer.publish(sequence);
            }
        }
    }

    public static void main(String[] args) throws InterruptedException {
        // 创建线程池
        ExecutorService executor = Executors.newFixedThreadPool(4);
        // 设置环形缓冲区大小（必须是2的幂）
        int bufferSize = 1024;

        // 创建Disruptor实例
        Disruptor<Event> disruptor = new Disruptor<>(
                new EventFactoryImpl(),
                bufferSize,
                executor,
                ProducerType.SINGLE,
                new BlockingWaitStrategy()
        );

        // 设置事件处理器
        disruptor.handleEventsWith(new EventHandlerImpl());

        // 启动Disruptor
        RingBuffer<Event> ringBuffer = disruptor.start();
        EventProducer producer = new EventProducer(ringBuffer);

        // 生产10个事件
        for (int i = 0; i < 10; i++) {
            final int index = i;
            // 使用单独的线程模拟异步生产
            new Thread(() -> {
                producer.publishEvent("消息 #" + index);
                System.out.println(Thread.currentThread().getName() + " 发布消息 #" + index);
            }, "生产者线程-" + i).start();
        }

        // 等待所有事件处理完成
        Thread.sleep(2000);

        // 关闭资源
        disruptor.shutdown();
        executor.shutdown();
    }
}