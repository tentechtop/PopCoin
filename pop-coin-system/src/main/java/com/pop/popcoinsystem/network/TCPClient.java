package com.pop.popcoinsystem.network;

import com.pop.popcoinsystem.network.common.NodeInfo;
import com.pop.popcoinsystem.network.protocol.message.KademliaMessage;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import lombok.extern.slf4j.Slf4j;

import java.math.BigInteger;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
public class TCPClient {
    private final ExecutorService executorService;

    private Bootstrap bootstrap;
    private NioEventLoopGroup group;



    public TCPClient() {
        executorService = Executors.newFixedThreadPool(10);

    }

    /** 节点ID到Channel的映射 */  //不同协议实现的通道不用通用
    private final Map<BigInteger, Channel> nodeTCPChannel = new ConcurrentHashMap<>();



    /** 节点ID到地址的映射 */
    private final Map<BigInteger, InetSocketAddress> nodeAddresses = new ConcurrentHashMap<>();



    public  void sendMessage(KademliaMessage message) throws InterruptedException, ConnectException {
        BigInteger id = message.getReceiver().getId();
        Channel channel = null;
        if (!nodeTCPChannel.containsKey(id)  || !nodeTCPChannel.get(id).isActive()){
            //重新连接 并保存Channel
            NodeInfo receiver = message.getReceiver();
            channel = connectTarget(receiver.getIpv4(), receiver.getTcpPort());
            nodeTCPChannel.put(id, channel);
        }
        channel = nodeTCPChannel.get(id);
        channel.writeAndFlush(message);
    }


    public Channel connectTarget(String ipv4, int tcpPort) throws InterruptedException {
        group = new NioEventLoopGroup();
        bootstrap = new Bootstrap();
        bootstrap.group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast(new KademliaNodeServer.TCPKademliaMessageDecoder());
                        pipeline.addLast(new KademliaNodeServer.TCPKademliaMessageEncoder());
                    }
                });
        ChannelFuture connect = bootstrap.connect(ipv4, tcpPort).sync();
        Channel channel = connect.channel();
        connect.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture channelFuture) throws Exception {
                if (channelFuture.isSuccess()) {
                    log.info("TCP:Connect to " + ipv4 + ":" + tcpPort + " success");
                } else {
                    log.error("TCP:Connect to " + ipv4 + ":" + tcpPort + " failed");
                    throw new ConnectException("Connect to " + ipv4 + ":" + tcpPort + " failed");
                }
            }
        });
        //异步
        executorService.submit(() -> {
            try {
                channel.closeFuture().sync();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });
        return channel;
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
