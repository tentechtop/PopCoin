package com.pop.popcoinsystem.network;

import com.pop.popcoinsystem.network.common.NodeInfo;
import com.pop.popcoinsystem.network.common.NodeSettings;
import com.pop.popcoinsystem.network.common.RoutingTable;
import com.pop.popcoinsystem.network.protocol.MessageType;
import com.pop.popcoinsystem.network.protocol.message.KademliaMessage;
import com.pop.popcoinsystem.network.protocol.message.PingKademliaMessage;
import com.pop.popcoinsystem.network.protocol.messageHandler.*;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.handler.codec.MessageToMessageEncoder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.math.BigInteger;
import java.net.DatagramPacket;
import java.util.*;
import java.util.concurrent.*;

@Slf4j
@Data
public class KademliaNodeServer {
    private NodeInfo nodeInfo;
    private final NodeSettings nodeSettings;
    //路由表
    private RoutingTable routingTable;
    private UDPClient udpClient;
    private TCPClient tcpClient;
    //节点信息
    //节点设置
    //消息序列化
    //消息处理器
    protected final transient Map<Integer, MessageHandler> messageHandlerRegistry = new ConcurrentHashMap<>();
    //UDP客服端
    //TCP客服端



    // 已广播消息记录，避免重复广播
    private final Map<String, Long> broadcastMessages = new ConcurrentHashMap<>();
    // 消息过期时间（毫秒）
    private static final long MESSAGE_EXPIRATION_TIME = 30000;
    // 节点过期时间（毫秒）
    private static final long NODE_EXPIRATION_TIME = 300000;

    //UDP服务
    private EventLoopGroup udpGroup;
    private Bootstrap udpBootstrap;

    //TCP服务  长连接 能实时获取数据  节点间主动维持持久连接 + 事件驱动的实时广播  这样能随时获取如何消息 以及任何变化
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private ServerBootstrap tcpBootstrap;
    private Bootstrap clientBootstrap;//TCP客户端



    //主要用于异步操作的状态追踪。
    private ChannelFuture udpBindFuture;
    private ChannelFuture tcpBindFuture;
    private boolean running = false;


    public KademliaNodeServer(NodeInfo nodeInfo) {
        this.nodeInfo = nodeInfo;
        this.nodeSettings = NodeSettings.Default.build();
        init();
    }

    public KademliaNodeServer(BigInteger id,String ipv4,int udpPort,int tcpPort) {
        this.nodeInfo = NodeInfo.builder().id(id).ipv4(ipv4).udpPort(udpPort).tcpPort(tcpPort).build();;
        this.nodeSettings = NodeSettings.Default.build();
        init();
    }

    private void init() {
        RoutingTable routingTable1 = new RoutingTable(nodeInfo.getId(), nodeSettings);

        //初始化UDP客户端


        //注册消息处理器
        this.registerMessageHandler(MessageType.EMPTY, new EmptyMessageHandler());
        this.registerMessageHandler(MessageType.PING, new PingMessageHandler());
        this.registerMessageHandler(MessageType.PONG, new PongMessageHandler());
        this.registerMessageHandler(MessageType.FIND_NODE_REQ, new FindNodeRequestMessageHandler());
        this.registerMessageHandler(MessageType.FIND_NODE_RES, new FindNodeResponseMessageHandler());
        this.registerMessageHandler(MessageType.SHUTDOWN, new ShutdownMessageHandler());

    }
    public void registerMessageHandler(int type, MessageHandler messageHandler) {
        this.messageHandlerRegistry.put(type, messageHandler);
    }



    public synchronized void start() throws Exception {
        if (this.running)
            return;
        try {
            // 启动服务
            startUdpDiscovererServer();
            startTcpTransmitServer();



            running = true;
        } catch (Exception e) {
            log.error("KademliaNode start error", e);
            stop();
        }
    }

    // UDP服务 - 节点发现
    public void startUdpDiscovererServer() {
        try {
            udpGroup = new NioEventLoopGroup();
            udpBootstrap = new Bootstrap();
            udpBootstrap.group(udpGroup)
                    .channel(NioDatagramChannel.class)
                    .option(ChannelOption.SO_BROADCAST, true)
                    .handler(new ChannelInitializer<NioDatagramChannel>() {
                        @Override
                        protected void initChannel(NioDatagramChannel ch) throws Exception {
                            ChannelPipeline pipeline = ch.pipeline();
                            pipeline.addLast(new KademliaUdpHandler(KademliaNodeServer.this));
                        }
                    });

            udpBindFuture = nodeInfo.getIpv4() != null ? udpBootstrap.bind(nodeInfo.getIpv4(), nodeInfo.getUdpPort()).sync() : udpBootstrap.bind(nodeInfo.getUdpPort()).sync();
            log.info("UDP服务已启动，地址{} 端口: {}", nodeInfo.getIpv4(),nodeInfo.getUdpPort());
            this.udpClient = new UDPClient(udpBootstrap);
        } catch (Exception e) {
            log.error("KademliaNode startUdpServer error", e);
        }
    }

    // TCP服务 - 数据传输
    public void startTcpTransmitServer() {
        try {
            bossGroup = new NioEventLoopGroup();
            workerGroup = new NioEventLoopGroup();
            tcpBootstrap = new ServerBootstrap();
            tcpBootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)////设置TCP长连接,一般如果两个小时内没有数据的通信时,TCP会自动发送一个活动探测数据报文
                    .childOption(ChannelOption.TCP_NODELAY, true)///将小的数据包包装成更大的帧进行传送，提高网络的负载   // 将网络数据积累到一定的数量后,服务器端才发送出去,会造成一定的延迟。希望服务是低延迟的,建议将TCP_NODELAY设置为true
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        public void initChannel(SocketChannel ch) throws Exception {
                            ChannelPipeline pipeline = ch.pipeline();
                            // 添加长度字段基于的帧解码器（处理粘包/半包问题）

                            // 保持长连接
                            // 添加TCP消息处理器
                            pipeline.addLast(new KademliaTcpHandler(KademliaNodeServer.this));
                        }
                    })
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.SO_KEEPALIVE, true);

            tcpBindFuture = nodeInfo.getIpv4() != null ? tcpBootstrap.bind(nodeInfo.getIpv4(), nodeInfo.getTcpPort()).sync() : tcpBootstrap.bind(nodeInfo.getTcpPort()).sync();
            log.info("TCP服务已启动，端口: {}", nodeInfo.getTcpPort());


            // 初始化客户端组件 - 复用服务端的workerGroup
            clientBootstrap = new Bootstrap();
            clientBootstrap.group(workerGroup)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.SO_KEEPALIVE, true)
                    .option(ChannelOption.TCP_NODELAY, true)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        public void initChannel(SocketChannel ch) throws Exception {
                            ChannelPipeline pipeline = ch.pipeline();
                            pipeline.addLast(new KademliaTcpHandler(KademliaNodeServer.this));
                        }
                    });
            this.tcpClient = new TCPClient(clientBootstrap);
        } catch (Exception e) {
            log.error("KademliaNode startTcpServer error", e);
        }
    }


    // 连接到引导节点
    public void connectToBootstrapNodes(NodeInfo bootstrapNodeInfo) throws Exception {
        if (bootstrapNodeInfo == null) return;
        //向引导节点发送Ping消息 实现握手
        PingKademliaMessage pingKademliaMessage = new PingKademliaMessage();
        pingKademliaMessage.setMessageId(UUID.randomUUID().toString());
        pingKademliaMessage.setSender(nodeInfo);
        pingKademliaMessage.setReceiver(bootstrapNodeInfo);
        pingKademliaMessage.setTimestamp(System.currentTimeMillis());
    /*    udpClient.sendMessage(pingKademliaMessage);*/
        tcpClient.sendMessage(pingKademliaMessage);
    }







    public void stop() throws InterruptedException {
        this.running = false;

        if (udpBindFuture != null) {
            udpBindFuture.channel().closeFuture().sync();
        }
        if (tcpBindFuture != null) {
            tcpBindFuture.channel().closeFuture().sync();
        }
        if (udpGroup != null) {
            udpGroup.shutdownGracefully().sync();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully().sync();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully().sync();
        }
    }

    public synchronized void stopNow() throws InterruptedException {
        this.running = false;
        if (bossGroup != null && workerGroup != null && udpGroup != null){
            bossGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync();
            workerGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync();
            udpGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync();
        }
        if (udpBindFuture != null && tcpBindFuture != null)
            try {
                this.udpBindFuture.channel().close().sync();
                this.tcpBindFuture.channel().close().sync();
            } catch (RejectedExecutionException e){
                log.error("Error when closing channel", e);
            }
    }



















    /**
     * Kademlia消息编码器
     */
    public static class UDPKademliaMessageEncoder extends MessageToMessageEncoder<KademliaMessage> {
        @Override
        protected void encode(ChannelHandlerContext ctx, KademliaMessage message, List<Object> out) throws Exception {

        }
    }

    /**
     * Kademlia消息解码器
     */
    public static class UDPKademliaMessageDecoder extends MessageToMessageDecoder<DatagramPacket> {
        @Override
        protected void decode(ChannelHandlerContext ctx, DatagramPacket packet, List<Object> out) throws Exception {

        }
    }




}
