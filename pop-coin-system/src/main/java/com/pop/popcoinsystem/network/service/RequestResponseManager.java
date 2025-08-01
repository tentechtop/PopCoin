package com.pop.popcoinsystem.network.service;

import com.pop.popcoinsystem.network.protocol.message.KademliaMessage;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.EventLoop;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.Promise;
import io.netty.util.concurrent.ScheduledFuture;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

public class RequestResponseManager {
    // 存储等待响应的请求：messageId -> 上下文（包含Promise和超时任务）
    private final ConcurrentHashMap<Long, RequestContext> pendingRequests = new ConcurrentHashMap<>();
    // 用于生成唯一messageId（原子递增，确保唯一性）
    private final AtomicLong messageIdGenerator = new AtomicLong(0);
    // 通道的EventLoop（用于执行超时任务）
    private final EventLoop eventLoop;

    public RequestResponseManager(Channel channel) {
        this.eventLoop = channel.eventLoop();
    }

    /**
     * 发送请求并返回Promise（异步结果）
     * @param channel 通信通道
     * @param message 请求消息
     * @param timeout 超时时间
     * @param unit 时间单位
     * @return 响应的Promise
     */
    public Promise<KademliaMessage> sendRequest(Channel channel, KademliaMessage message, long timeout, TimeUnit unit) {
        // 1. 生成唯一messageId（覆盖消息原有ID，确保全局唯一）
        long messageId = messageIdGenerator.incrementAndGet();
        message.setMessageId(messageId);
        message.setResponse(true); // 标记为请求

        // 2. 创建Promise（绑定到通道的EventLoop，确保线程安全）
        Promise<KademliaMessage> promise = new DefaultPromise<>(eventLoop);

        // 3. 注册超时任务
        ScheduledFuture<?> timeoutFuture = eventLoop.schedule(() -> {
            // 超时：移除请求并标记失败
            RequestContext context = pendingRequests.remove(messageId);
            if (context != null && !promise.isDone()) {
                promise.setFailure(new TimeoutException("Request (id=" + messageId + ") timeout after " + timeout + unit));
            }
        }, timeout, unit);

        // 4. 存储请求上下文
        pendingRequests.put(messageId, new RequestContext(promise, timeoutFuture));

        // 5. 发送消息
        channel.writeAndFlush(message).addListener((ChannelFutureListener) future -> {
            if (!future.isSuccess()) {
                // 发送失败：清理上下文并标记失败
                RequestContext context = pendingRequests.remove(messageId);
                if (context != null) {
                    context.timeoutFuture.cancel(false); // 取消超时任务
                    if (!promise.isDone()) {
                        promise.setFailure(future.cause());
                    }
                }
            }
        });

        return promise;
    }

    /**
     * 处理收到的响应消息
     */
    public void handleResponse(KademliaMessage response) {
        if (!response.isResponse()) {
            return; // 只处理响应消息
        }
        long messageId = response.getMessageId();
        RequestContext context = pendingRequests.remove(messageId);
        if (context != null) {
            context.timeoutFuture.cancel(false); // 取消超时任务
            if (!context.promise.isDone()) {
                context.promise.setSuccess(response); // 标记响应成功
            }
        }
    }

    /**
     * 清理所有请求（通道关闭时调用）
     */
    public void clear() {
        pendingRequests.values().forEach(context -> {
            context.timeoutFuture.cancel(false);
            if (!context.promise.isDone()) {
                context.promise.setFailure(new CancellationException("Channel closed"));
            }
        });
        pendingRequests.clear();
    }

    // 内部类：请求上下文（包含Promise和超时任务）
    private static class RequestContext {
        final Promise<KademliaMessage> promise;
        final ScheduledFuture<?> timeoutFuture;

        RequestContext(Promise<KademliaMessage> promise, ScheduledFuture<?> timeoutFuture) {
            this.promise = promise;
            this.timeoutFuture = timeoutFuture;
        }
    }
}