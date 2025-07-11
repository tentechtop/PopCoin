package com.pop.popcoinsystem.network;

import com.pop.popcoinsystem.network.common.NodeInfo;
import com.pop.popcoinsystem.network.protocol.message.KademliaMessage;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import lombok.extern.slf4j.Slf4j;

import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
public class TCPClient {
    private final ExecutorService executorService;

    private final Bootstrap tcpBootstrap;



    public TCPClient(Bootstrap tcpBootstrap) {
        executorService = Executors.newFixedThreadPool(10);
        this.tcpBootstrap = tcpBootstrap;


    }

    /** 节点ID到Channel的映射 */
    public static final Map<BigInteger, Channel> nodeTCPChannel = new ConcurrentHashMap<>();
    /** 节点ID到地址的映射 */
    private final Map<BigInteger, InetSocketAddress> nodeAddresses = new ConcurrentHashMap<>();


    public  void sendMessage(KademliaMessage message) throws InterruptedException {
        BigInteger id = message.getReceiver().getId();
        Channel channel = null;
        if (!nodeTCPChannel.containsKey(id)){
            //重新连接 并保存Channel
            NodeInfo receiver = message.getReceiver();
            ChannelFuture connect = tcpBootstrap.connect(receiver.getIpv4(), receiver.getTcpPort());
            if (connect.isSuccess()){
                log.info("Connect to " + receiver.getIpv4() + ":" + receiver.getTcpPort() + " success");
                channel = connect.sync().channel();
            }
            nodeTCPChannel.put(id, channel);
        }else if (!nodeTCPChannel.get(id).isActive()){
            //重新连接 并保存Channel
            NodeInfo receiver = message.getReceiver();
            ChannelFuture connect = tcpBootstrap.connect(receiver.getIpv4(), receiver.getTcpPort());
            if (connect.isSuccess()){
                log.info("Connect to " + receiver.getIpv4() + ":" + receiver.getTcpPort() + " success");
                channel = connect.sync().channel();
            }
            nodeTCPChannel.put(id, channel);
        }else if (nodeTCPChannel.get(id).isActive()){
            channel = nodeTCPChannel.get(id);
        }else {
            throw new RuntimeException("Channel is not active");
        }

        byte[] serialize = KademliaMessage.serialize(message);
        // 构造二进制消息
        ByteBuf byteMessage = channel.alloc().buffer();
        byteMessage.writeInt(1);         // 消息类型
        byteMessage.writeInt(4 + serialize.length);  // 内容长度 + 内容长度字段本身
        byteMessage.writeInt(serialize.length);      // 内容长度
        byteMessage.writeBytes(serialize);           // 内容
        channel.writeAndFlush(byteMessage);
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
