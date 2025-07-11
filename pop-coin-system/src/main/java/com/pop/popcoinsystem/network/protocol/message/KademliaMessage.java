package com.pop.popcoinsystem.network.protocol.message;

import com.google.common.base.Objects;
import com.pop.popcoinsystem.network.common.NodeInfo;
import com.pop.popcoinsystem.util.SerializeUtils;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.util.UUID;

@Getter
@Setter
@ToString
public abstract class KademliaMessage<D extends Serializable> {
    // 消息唯一标识（用于去重）
    private String messageId;
    private int type;
    private NodeInfo sender;//消息发送者
    private NodeInfo receiver;//消息接收者
    private long timestamp;// 时间戳 发送时间
    private D data;//消息数据
    //过期时间
    private long expireTime;

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
        this.expireTime = System.currentTimeMillis() + 1000 * 60 * 5;
    }


    /**
     * 检查消息是否过期
     */
    public boolean isExpired() {
        return System.currentTimeMillis() > expireTime;
    }

    public KademliaMessage() {
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
    private String generateMessageId() {
        return UUID.randomUUID().toString();
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
