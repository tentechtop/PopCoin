package com.pop.popcoinsystem.network.service.util;

import com.pop.popcoinsystem.network.common.NodeInfo;
import com.pop.popcoinsystem.network.protocol.message.KademliaMessage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.MessageToMessageEncoder;

import java.net.InetSocketAddress;
import java.util.List;

/**
 * UDP消息包装器：将编码后的帧包装为DatagramPacket（含目标地址）
 */
public class UDPMessageWrapper extends MessageToMessageEncoder<KademliaMessage<?>> {

    @Override
    protected void encode(ChannelHandlerContext ctx, KademliaMessage<?> msg, List<Object> out) throws Exception {
        // 1. 获取目标节点地址
        NodeInfo receiver = msg.getReceiver();
        InetSocketAddress targetAddr = new InetSocketAddress(
                receiver.getIpv4(),
                receiver.getUdpPort()
        );

        // 2. 编码消息为ByteBuf（复用统一帧编码器）
        ByteBuf buf = ctx.alloc().buffer();
        try {
            new KademliaFrameEncoder().encode(ctx, msg, buf);
            // 3. 包装为DatagramPacket
            out.add(new DatagramPacket(buf.retain(), targetAddr));
        } finally {
            buf.release();
        }
    }
}