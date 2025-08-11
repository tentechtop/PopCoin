package com.pop.popcoinsystem.network.service.util;

import com.pop.popcoinsystem.network.protocol.message.KademliaMessage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

import static com.pop.popcoinsystem.constant.BlockChainConstants.NET_VERSION;

/**
 * 统一帧编码器：将消息编码为通用帧结构
 */
public class KademliaFrameEncoder extends MessageToByteEncoder<KademliaMessage<?>> {

    @Override
    protected void encode(ChannelHandlerContext ctx, KademliaMessage<?> msg, ByteBuf out) throws Exception {
        // 1. 序列化消息内容
        byte[] content = KademliaMessage.serialize(msg);

        // 2. 写入帧头部
        out.writeInt(msg.getType());          // 消息类型（4字节）
        out.writeInt(NET_VERSION);            // 网络版本（4字节）
        out.writeInt(content.length);         // 内容长度（4字节）

        // 3. 写入消息内容
        out.writeBytes(content);
    }
}