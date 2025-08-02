package com.pop.popcoinsystem.network.rpc;

import com.pop.popcoinsystem.util.SerializeUtils;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

import java.io.Serializable;

// 编码器：将对象序列化为字节
public class RpcEncoder extends MessageToByteEncoder<Object> {
    private Class<?> clazz;

    public RpcEncoder(Class<?> clazz) {
        this.clazz = clazz;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, Object msg, ByteBuf out) {
        if (clazz.isInstance(msg)) {
            byte[] bytes = SerializeUtils.serialize(msg);
            out.writeInt(bytes.length); // 先写长度
            out.writeBytes(bytes);      // 再写内容
        }
    }
}
