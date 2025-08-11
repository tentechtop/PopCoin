package com.pop.popcoinsystem.network.service.util;

import com.pop.popcoinsystem.network.protocol.message.KademliaMessage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import lombok.extern.slf4j.Slf4j;

import static com.pop.popcoinsystem.constant.BlockChainConstants.NET_VERSION;

/**
 * 统一帧解码器：处理TCP/UDP通用的帧结构
 * 帧格式：[类型(4字节)] + [版本(4字节)] + [内容长度(4字节)] + [内容(n字节)]
 */
@Slf4j
public class KademliaFrameDecoder extends LengthFieldBasedFrameDecoder {

    // 协议固定头部长度：类型(4) + 版本(4) + 内容长度(4) = 12字节
    public static final int HEADER_LENGTH = 12;
    // 最大帧长度（10MB）
    public static final int MAX_FRAME_LENGTH = 10 * 1024 * 1024;

    public KademliaFrameDecoder() {
        super(
                MAX_FRAME_LENGTH,    // 最大帧长度
                8,                   // 长度字段偏移量（跳过类型4字节 + 版本4字节）
                4,                   // 长度字段长度（内容长度占4字节）
                0,                   // 长度调整值（总长度 = 内容长度 + 12字节头部）
                0                    // 不跳过头部字节（后续需手动解析类型和版本）
        );
    }

    @Override
    protected Object decode(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
        // 1. 调用父类解码获取完整帧
        ByteBuf frame = (ByteBuf) super.decode(ctx, in);
        if (frame == null) {
            return null; // 帧不完整，等待后续数据
        }

        try {
            // 2. 解析帧头部信息
            int messageType = frame.readInt();          // 消息类型
            int netVersion = frame.readInt();           // 网络版本
            int contentLength = frame.readInt();        // 内容长度

            // 3. 校验网络版本
            if (netVersion != NET_VERSION) {
                log.warn("网络版本不匹配：收到{}，预期{}", netVersion, NET_VERSION);
                return null;
            }

            // 4. 读取内容字节
            byte[] content = new byte[contentLength];
            frame.readBytes(content);

            // 5. 反序列化消息体
            KademliaMessage<?> message = KademliaMessage.deSerialize(content);
            if (message != null) {
                message.setType(messageType); // 补充消息类型（反序列化可能丢失）
                return message;
            } else {
                log.error("消息反序列化失败，类型：{}，长度：{}", messageType, contentLength);
                return null;
            }
        } finally {
            frame.release(); // 释放缓冲区
        }
    }
}