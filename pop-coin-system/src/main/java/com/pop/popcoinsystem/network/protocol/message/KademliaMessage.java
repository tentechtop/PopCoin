package com.pop.popcoinsystem.network.protocol.message;

import com.google.common.base.Objects;
import com.pop.popcoinsystem.network.common.NodeInfo;
import com.pop.popcoinsystem.util.SerializeUtils;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.util.Random;
import java.util.UUID;

@Getter
@Setter
@ToString
public abstract class KademliaMessage<D extends Serializable> {
    // 消息唯一标识（用于去重）序列化后仅占8 字节（二进制）
    private long messageId = generateMessageId();
    private int type;
    private NodeInfo sender;//消息发送者
    private NodeInfo receiver;//消息接收者
    private long timestamp = System.currentTimeMillis(); // 时间戳 发送时间
    private D data;//消息数据


    /**
     * 构造消息
     */
    public KademliaMessage(int type, NodeInfo sender, NodeInfo receiver, D data) {
        this.messageId = generateMessageId();
        this.type = type;
        this.sender = sender;
        this.receiver = receiver;
        this.data = data;
        this.timestamp = System.currentTimeMillis();
    }


    /**
     * 检查消息是否过期  超过10分钟就是过期
     */
    public boolean isExpired() {
        return System.currentTimeMillis() - timestamp > 10 * 60 * 1000;
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
        return new Random().nextLong();
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
