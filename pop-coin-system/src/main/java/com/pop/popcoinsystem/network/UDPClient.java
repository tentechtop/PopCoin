package com.pop.popcoinsystem.network;

import com.pop.popcoinsystem.network.common.NodeInfo;
import com.pop.popcoinsystem.network.protocol.message.KademliaMessage;
import com.pop.popcoinsystem.network.protocol.message.PingKademliaMessage;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.handler.codec.MessageToMessageEncoder;
import lombok.extern.slf4j.Slf4j;
import org.apache.el.util.MessageFactory;

import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
public class UDPClient {
    private final ExecutorService executorService;

    private final Bootstrap bootstrap;

    public UDPClient(Bootstrap bootstrap) {
        executorService = Executors.newFixedThreadPool(10);
        // 配置UDP引导器
        this.bootstrap = bootstrap;
    }

    /** 节点ID到Channel的映射 */
    public static final Map<BigInteger, Channel> nodeUDPChannel = new ConcurrentHashMap<>();
    /** 节点ID到地址的映射 */
    private final Map<BigInteger, InetSocketAddress> nodeAddresses = new ConcurrentHashMap<>();



    //发送消息
    public  void sendMessage(KademliaMessage message) throws InterruptedException {
        BigInteger id = message.getReceiver().getId();
        Channel channel = null;
        if (!nodeUDPChannel.containsKey(id)){
            //重新连接 并保存Channel
            NodeInfo receiver = message.getReceiver();
            channel = bootstrap.connect(receiver.getIpv4(), receiver.getUdpPort()).sync().channel();
            nodeUDPChannel.put(id, channel);
        }else if (!nodeUDPChannel.get(id).isActive()){
            //重新连接 并保存Channel
            NodeInfo receiver = message.getReceiver();
            channel = bootstrap.connect(receiver.getIpv4(), receiver.getUdpPort()).sync().channel();
            nodeUDPChannel.put(id, channel);
        }else if (nodeUDPChannel.get(id).isActive()){
            channel = nodeUDPChannel.get(id);
        }else {
            throw new RuntimeException("Channel is not active");
        }
        byte[] serialize = KademliaMessage.serialize(message);
        ByteBuf byteBuf = Unpooled.copiedBuffer(serialize);
        InetSocketAddress inetSocketAddress = new InetSocketAddress(message.getReceiver().getIpv4(), message.getReceiver().getUdpPort());
        DatagramPacket sendPacket = new DatagramPacket(byteBuf, inetSocketAddress);
        channel.writeAndFlush(sendPacket).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                if (!future.isSuccess()) {
                    // 处理发送失败的情况
                    System.err.println("Failed to send message: " + future.cause().getMessage());
                }else  {
                    log.info("发送成功");
                }
            }
        });



    }



    /**
     * 异步发送消息
     * @param nodeId
     * @param message
     */
    public  void sendAsyncMessage(BigInteger nodeId, KademliaMessage message) {
        executorService.submit(() -> {
            try {
                sendMessage(message);
            } catch (Exception ignored) {}
        });
    }











}
