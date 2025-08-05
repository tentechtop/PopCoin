package com.pop.popcoinsystem.network.rpc;

import com.pop.popcoinsystem.network.protocol.message.KademliaMessage;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.EventLoop;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.Promise;
import io.netty.util.concurrent.ScheduledFuture;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
public class RequestResponseManager {
    // 请求ID -> 请求上下文，全局唯一映射
    private final ConcurrentHashMap<Long, RequestContext> pendingRequests = new ConcurrentHashMap<>();

    public RequestResponseManager() {
        // 无参构造函数，不再依赖Channel
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
        long requestId = message.getRequestId();
        // 获取通道的EventLoop
        EventLoop eventLoop = channel.eventLoop();

        // 创建Promise（绑定到通道的EventLoop，确保线程安全）
        Promise<KademliaMessage> promise = new DefaultPromise<>(eventLoop);

        // 注册超时任务
        ScheduledFuture<?> timeoutFuture = eventLoop.schedule(() -> {
            // 超时：移除请求并标记失败
            RequestContext context = pendingRequests.remove(requestId);
            if (context != null && !promise.isDone()) {
                promise.setFailure(new TimeoutException("Request (id=" + requestId + ") timeout after " + timeout +" " + unit));
            }
        }, timeout, unit);

        // 存储请求上下文
        pendingRequests.put(requestId, new RequestContext(promise, timeoutFuture));

        // 发送消息
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
            return;
        }
        long requestId = response.getRequestId();
        RequestContext context = pendingRequests.remove(requestId);
        if (context == null) {
            log.error("响应requestId={}未找到匹配的请求，可能已超时或被清理", requestId);
            return;
        }
        // 取消超时任务
        boolean cancelSuccess = context.timeoutFuture.cancel(false);
        log.debug("响应requestId={}，超时任务取消结果: {}", requestId, cancelSuccess);
        // 检查promise状态
        if (context.promise.isDone()) {
            log.error("响应requestId={}，但promise已完成（状态：{}）",
                    requestId, context.promise.isSuccess() ? "成功" : "失败");
            return;
        }
        // 标记成功
        context.promise.setSuccess(response);
        log.debug("响应requestId={}匹配成功", requestId);
    }

    /**
     * 清理所有请求
     */
    public void clear() {
        pendingRequests.values().forEach(context -> {
            context.timeoutFuture.cancel(false);
            if (!context.promise.isDone()) {
                context.promise.setFailure(new CancellationException("Request manager cleared or closed"));
            }
        });
        pendingRequests.clear();
    }

    /**
     * 手动清理已处理的请求（避免内存泄漏）
     */
    public void clearRequest(long requestId) {
        RequestContext context = pendingRequests.remove(requestId);
        if (context != null) {
            context.timeoutFuture.cancel(false); // 取消超时任务
        }
    }


    /**
     * 重载方法：支持自定义超时时间
     * @param requestId 消息唯一标识
     * @param promise 用于接收响应结果的Promise
     * @param eventLoop 事件循环
     * @param timeout 超时时间
     * @param unit 时间单位
     */
    public void registerRequest(long requestId, Promise<KademliaMessage> promise,
                                EventLoop eventLoop, long timeout, TimeUnit unit) {
        if (requestId <= 0) {
            throw new IllegalArgumentException("Invalid requestId: " + requestId);
        }
        if (promise == null) {
            throw new IllegalArgumentException("Promise cannot be null");
        }
        if (eventLoop == null) {
            throw new IllegalArgumentException("EventLoop cannot be null");
        }
        if (timeout <= 0) {
            throw new IllegalArgumentException("Invalid timeout: " + timeout);
        }

        // 检查是否已有相同requestId的请求在等待，避免重复注册
        if (pendingRequests.containsKey(requestId)) {
            throw new IllegalStateException("Request with requestId " + requestId + " is already registered");
        }

        // 注册超时任务：超时未收到响应则标记Promise为失败
        ScheduledFuture<?> timeoutFuture = eventLoop.schedule(() -> {
            RequestContext context = pendingRequests.remove(requestId);
            if (context != null && !context.promise.isDone()) {
                String errorMsg = "Request (id=" + requestId + ") timed out after " + timeout + unit;
                context.promise.setFailure(new TimeoutException(errorMsg));
            }
        }, timeout, unit);


        pendingRequests.put(requestId, new RequestContext(promise, timeoutFuture));

        // 监听Promise的完成状态，自动清理资源
        promise.addListener(future -> {
            // 无论成功、失败还是取消，都清理对应的请求记录
            RequestContext context = pendingRequests.remove(requestId);
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
