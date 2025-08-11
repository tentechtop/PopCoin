package com.pop.popcoinsystem.network.service.util;

import com.pop.popcoinsystem.network.protocol.message.KademliaMessage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.MessageToMessageDecoder;

import java.net.InetSocketAddress;
import java.util.List;

/**
 * UDP帧解码适配器：将DatagramPacket转换为ByteBuf，供统一帧解码器处理
 */
public class UDPFrameDecoderAdapter extends MessageToMessageDecoder<DatagramPacket> {

    private final KademliaFrameDecoder frameDecoder = new KademliaFrameDecoder();

    @Override
    protected void decode(ChannelHandlerContext ctx, DatagramPacket packet, List<Object> out) throws Exception {
        // 1. 提取DatagramPacket中的数据缓冲区
        ByteBuf content = packet.content().retain(); // 保留引用，避免被自动释放

        // 2. 调用统一帧解码器处理
        Object decoded = frameDecoder.decode(ctx, content);
        if (decoded != null) {
            // 3. 补充发送者地址信息（可选）
            KademliaMessage<?> message = (KademliaMessage<?>) decoded;
            InetSocketAddress senderAddr = packet.sender();
            message.getSender().setIpv4(senderAddr.getHostString());
            message.getSender().setUdpPort(senderAddr.getPort());
            out.add(message);
        }
        content.release(); // 释放缓冲区
    }
}