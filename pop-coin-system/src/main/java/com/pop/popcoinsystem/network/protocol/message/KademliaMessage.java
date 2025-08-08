package com.pop.popcoinsystem.network.protocol.message;

import com.google.common.base.Objects;
import com.pop.popcoinsystem.network.common.NodeInfo;
import com.pop.popcoinsystem.util.SerializeUtils;
import com.pop.popcoinsystem.util.TimeGenerator;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;


@Getter
@Setter
@ToString
public abstract class KademliaMessage<D extends Serializable> {
    private long messageId = generateMessageId();//广播场景：需要messageId全局唯一（避免同一消息被重复转发，导致风暴）。
    private int type;
    private NodeInfo sender;//消息发送者
    private NodeInfo receiver;//消息接收者
    private long timestamp = System.currentTimeMillis(); // 时间戳 发送时间
    private D data;//消息数据


    //设置请求响应ID
    // 请求-响应关联标识（仅用于单播）
    // 作用：单播场景下，请求和响应的reqResId必须相同，用于匹配"一问一答"
    // 广播场景下可忽略（设为0或null）
    private long requestId = 0;//仅用于单播的请求 - 响应关联（同一对请求 - 响应的reqResId相同，不影响广播）。
    private boolean isResponse;//是请求 true=响应消息，false=请求消息


    //如果reqResId = 0 则不是单播消息 不会交给请求响应处理器
    //是否单播消息
    public boolean isSingle() {
        return this.requestId != 0;
    }

    //是否是响应消息
    public boolean isResponse() {
        return this.isResponse;
    }

    public void setReqResId() {
        this.requestId =  TimeGenerator.generateUniqueTransactionTime();
    }

    /**
     * 构造消息
     */
    public KademliaMessage(int type, D data) {
        this.messageId = generateMessageId();
        this.type = type;
        this.sender = sender;
        this.receiver = receiver;
        this.data = data;
        this.timestamp = System.currentTimeMillis();
    }




    //设置是请求 还是响应
    public void setResponse(boolean response) {
        this.isResponse = response;
    }



    /**
     * 检查消息是否过期  超过10分钟就是过期
     */
    public boolean isExpired() {
        return System.currentTimeMillis() - this.timestamp > 10 * 60 * 1000;
    }

    public KademliaMessage() {
        this.timestamp = System.currentTimeMillis();
        this.messageId = generateMessageId();
    }


    protected KademliaMessage(int type) {
        this.type = type;
    }

    public KademliaMessage(int type, NodeInfo sender, D data) {
        this.messageId = generateMessageId();
        this.type = type;
        this.sender = sender;
        this.data = data;
        this.timestamp = System.currentTimeMillis();
    }


    // 生成唯一消息ID（可通过UUID实现）
    private long generateMessageId() {
        // 64位随机数，十六进制表示（16个字符）
        //return new Random().nextLong();
        return  TimeGenerator.generateUniqueTransactionTime();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        KademliaMessage<?> that = (KademliaMessage<?>) o;
        return Objects.equal(getData(), that.getData()) && Objects.equal(getType(), that.getType()) && Objects.equal(getSender(), that.getSender()) && Objects.equal(getTimestamp(), that.getTimestamp());
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getData(), getType(), getSender(), getTimestamp());
    }

    public static byte[] serialize(KademliaMessage message) {
        return SerializeUtils.serialize(message);
    }

    public static KademliaMessage deSerialize(byte[] bytes) {
        return (KademliaMessage)SerializeUtils.deSerialize(bytes);
    }
}
