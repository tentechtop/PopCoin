package com.pop.popcoinsystem.network.rpc;

import com.pop.popcoinsystem.util.SerializeUtils;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ReplayingDecoder;

import java.util.List;

// 解码器：将字节反序列化为对象
public class RpcDecoder extends ReplayingDecoder<Void> {
    private Class<?> clazz;

    public RpcDecoder(Class<?> clazz) {
        this.clazz = clazz;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        int length = in.readInt(); // 读取长度
        byte[] bytes = new byte[length];
        in.readBytes(bytes);       // 读取内容
        Object obj = SerializeUtils.deSerialize(bytes);
        out.add(obj);
    }
}