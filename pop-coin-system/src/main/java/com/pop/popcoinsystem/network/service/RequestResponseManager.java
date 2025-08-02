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
        //long messageId = messageIdGenerator.incrementAndGet();
        //message.setMessageId(messageId);
        long requestId = message.getRequestId();
        // 2. 创建Promise（绑定到通道的EventLoop，确保线程安全）
        Promise<KademliaMessage> promise = new DefaultPromise<>(eventLoop);
        // 3. 注册超时任务
        ScheduledFuture<?> timeoutFuture = eventLoop.schedule(() -> {
            // 超时：移除请求并标记失败
            RequestContext context = pendingRequests.remove(requestId);
            if (context != null && !promise.isDone()) {
                promise.setFailure(new TimeoutException("Request (id=" + requestId + ") timeout after " + timeout + unit));
            }
        }, timeout, unit);

        // 4. 存储请求上下文
        pendingRequests.put(requestId, new RequestContext(promise, timeoutFuture));

        // 5. 发送消息
        channel.writeAndFlush(message).addListener((ChannelFutureListener) future -> {
            if (!future.isSuccess()) {
                // 发送失败：清理上下文并标记失败
                RequestContext context = pendingRequests.remove(requestId);
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
        long messageId = response.getRequestId();
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

    /**
     * 手动清理已处理的请求（避免内存泄漏）
     */
    public void clearRequest(long messageId) {
        RequestContext context = pendingRequests.remove(messageId);
        if (context != null) {
            context.timeoutFuture.cancel(false); // 取消超时任务
        }
    }

    /**
     * 注册请求与Promise的关联，并设置超时机制
     * @param messageId 消息唯一标识
     * @param promise 用于接收响应结果的Promise
     */
    public void registerRequest(long messageId, Promise<KademliaMessage> promise) {
        // 默认超时时间5秒，也可改为接收超时参数的重载方法
        registerRequest(messageId, promise, 5, TimeUnit.SECONDS);
    }

    /**
     * 重载方法：支持自定义超时时间
     * @param messageId 消息唯一标识
     * @param promise 用于接收响应结果的Promise
     * @param timeout 超时时间
     * @param unit 时间单位
     */
    public void registerRequest(long messageId, Promise<KademliaMessage> promise, long timeout, TimeUnit unit) {
        if (messageId <= 0) {
            throw new IllegalArgumentException("Invalid messageId: " + messageId);
        }
        if (promise == null) {
            throw new IllegalArgumentException("Promise cannot be null");
        }
        if (timeout <= 0) {
            throw new IllegalArgumentException("Invalid timeout: " + timeout);
        }

        // 检查是否已有相同messageId的请求在等待，避免重复注册
        if (pendingRequests.containsKey(messageId)) {
            throw new IllegalStateException("Request with messageId " + messageId + " is already registered");
        }

        // 注册超时任务：超时未收到响应则标记Promise为失败
        ScheduledFuture<?> timeoutFuture = eventLoop.schedule(() -> {
            RequestContext context = pendingRequests.remove(messageId);
            if (context != null && !context.promise.isDone()) {
                String errorMsg = "Request (id=" + messageId + ") timed out after " + timeout + unit;
                context.promise.setFailure(new TimeoutException(errorMsg));
            }
        }, timeout, unit);

        // 将请求上下文存入映射表，关联messageId与Promise
        pendingRequests.put(messageId, new RequestContext(promise, timeoutFuture));

        // 监听Promise的完成状态，自动清理资源
        promise.addListener(future -> {
            // 无论成功、失败还是取消，都清理对应的请求记录
            RequestContext context = pendingRequests.remove(messageId);
            if (context != null) {
                context.timeoutFuture.cancel(false); // 取消超时任务
            }
        });
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