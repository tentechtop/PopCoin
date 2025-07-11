package com.pop.popcoinsystem.network;

import com.pop.popcoinsystem.network.protocol.MessageType;
import com.pop.popcoinsystem.network.protocol.message.KademliaMessage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class KademliaTcpHandler extends SimpleChannelInboundHandler<ByteBuf> {

    private final KademliaNodeServer nodeServer;


    public KademliaTcpHandler(KademliaNodeServer nodeServer) {
        this.nodeServer = nodeServer;
    }


    @Override
    protected void channelRead0(ChannelHandlerContext channelHandlerContext, ByteBuf byteBuf) throws Exception {

        // 确保有足够的数据读取消息类型和长度信息
        if (byteBuf.readableBytes() < 12) {
            log.warn("确保有足够的数据读取消息类型和长度信息");
            return;
        }

        byteBuf.markReaderIndex();

        // 读取消息类型
        int messageType = byteBuf.readInt();
        log.info("消息类型:{}", MessageType.MessageTypeMap.get(messageType));

        // 读取总长度（内容长度字段 + 内容长度）
        int totalLength = byteBuf.readInt();
        log.info("总长度:{}", totalLength);

        // 读取内容长度
        int contentLength = byteBuf.readInt();
        log.info("内容长度:{}", contentLength);

        // 检查是否有足够的数据读取完整的消息内容
        if (byteBuf.readableBytes() < contentLength) {
            byteBuf.resetReaderIndex();
            log.warn("没有足够的数据读取完整的消息内容");
            return;
        }

        // 读取消息内容
        byte[] contentBytes = new byte[contentLength];
        byteBuf.readBytes(contentBytes);

        // 反序列化为具体的消息对象
        KademliaMessage message = KademliaMessage.deSerialize(contentBytes);
        log.info("消息内容:{}", message);






    }


    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        cause.printStackTrace();
        ctx.close();
    }



}
