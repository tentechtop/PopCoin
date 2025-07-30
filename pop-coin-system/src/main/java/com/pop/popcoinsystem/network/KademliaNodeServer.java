package com.pop.popcoinsystem.network;

import com.pop.popcoinsystem.network.common.ExternalNodeInfo;
import com.pop.popcoinsystem.network.common.NodeInfo;
import com.pop.popcoinsystem.network.common.NodeSettings;
import com.pop.popcoinsystem.network.common.RoutingTable;
import com.pop.popcoinsystem.network.protocol.MessageType;
import com.pop.popcoinsystem.network.protocol.message.KademliaMessage;
import com.pop.popcoinsystem.network.protocol.message.PingKademliaMessage;
import com.pop.popcoinsystem.network.protocol.messageHandler.*;
import com.pop.popcoinsystem.util.BeanCopyUtils;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.*;



import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;

@Slf4j
@Data
public class KademliaNodeServer {
    //节点信息
    private NodeInfo nodeInfo;

    //额外信息  包含了节点信息  外网ip  外网端口  内网ip  内网端口  节点状态  节点版本  节点类型  节点描述 节点分数 等待
    private ExternalNodeInfo externalNodeInfo;

    private final NodeSettings nodeSettings;
    //路由表
    private RoutingTable routingTable;
    //节点设置
    //消息处理器
    public static final  Map<Integer, MessageHandler> KademliaMessageHandler = new ConcurrentHashMap<>();
    //UDP客服端
    //TCP客服端
    private UDPClient udpClient;
    private TCPClient tcpClient;

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


    //主要用于异步操作的状态追踪。
    private ChannelFuture udpBindFuture;
    private ChannelFuture tcpBindFuture;
    private boolean running = false;

    public KademliaNodeServer(NodeInfo nodeInfo) {
        this.nodeInfo = nodeInfo;
        this.externalNodeInfo = BeanCopyUtils.copyObject(nodeInfo, ExternalNodeInfo.class);
        //补充数据 如网络类型
        this.nodeSettings = NodeSettings.Default.build();
        init();
    }

    public KademliaNodeServer(BigInteger id,String ipv4,int udpPort,int tcpPort) {
        this.nodeInfo = NodeInfo.builder().id(id).ipv4(ipv4).udpPort(udpPort).tcpPort(tcpPort).build();
        this.externalNodeInfo = BeanCopyUtils.copyObject(nodeInfo, ExternalNodeInfo.class);
        //补充数据 如网络类型

        this.nodeSettings = NodeSettings.Default.build();
        init();
    }

    private void init() {
        routingTable = new RoutingTable(nodeInfo.getId(), nodeSettings);
        //注册消息处理器
        this.registerMessageHandler(MessageType.EMPTY.getCode(), new EmptyMessageHandler());
        this.registerMessageHandler(MessageType.PING.getCode(), new PingMessageHandler());
        this.registerMessageHandler(MessageType.PONG.getCode(), new PongMessageHandler());
        this.registerMessageHandler(MessageType.FIND_NODE_REQ.getCode(), new FindNodeRequestMessageHandler());
        this.registerMessageHandler(MessageType.FIND_NODE_RES.getCode(), new FindNodeResponseMessageHandler());
        this.registerMessageHandler(MessageType.HANDSHAKE_RES.getCode(), new HandshakeResponseMessageHandle());
        this.registerMessageHandler(MessageType.HANDSHAKE_REQ.getCode(), new HandshakeRequestMessageHandle());
        this.registerMessageHandler(MessageType.TRANSACTION.getCode(), new TransactionMessageHandler());

        this.registerMessageHandler(MessageType.SHUTDOWN.getCode(), new ShutdownMessageHandler());

        //初始化UDP客户端
        this.udpClient = new UDPClient();
        this.tcpClient = new TCPClient();
    }
    public void registerMessageHandler(int type, MessageHandler messageHandler) {
        KademliaMessageHandler.put(type, messageHandler);
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
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000) // 连接超时设置
                    .option(ChannelOption.SO_BROADCAST, true)
                    .handler(new ChannelInitializer<NioDatagramChannel>() {
                        @Override
                        protected void initChannel(NioDatagramChannel ch) throws Exception {
                            ChannelPipeline pipeline = ch.pipeline();
                            pipeline.addLast(new LengthFieldBasedFrameDecoder(
                                    10 * 1024 * 1024,  // 最大帧长度
                                    4,                 // 长度字段偏移量（跳过类型字段）
                                    4,                 // 长度字段长度（总长度字段）
                                    -8,                // 长度调整值 = 内容长度 - 总长度 = -8
                                    0                 // 跳过前12字节（类型+总长度+内容长度）  目前不跳过
                            ));
                            pipeline.addLast(new LengthFieldPrepender(4));
                            pipeline.addLast(new UDPKademliaMessageEncoder());
                            pipeline.addLast(new UDPKademliaMessageDecoder());
                            pipeline.addLast(new KademliaUdpHandler(KademliaNodeServer.this));
                        }
                    });

            udpBindFuture = nodeInfo.getIpv4() != null ? udpBootstrap.bind(nodeInfo.getIpv4(), nodeInfo.getUdpPort()).sync() : udpBootstrap.bind(nodeInfo.getUdpPort()).sync();
            log.info("UDP服务已启动，地址{} 端口: {}", nodeInfo.getIpv4(),nodeInfo.getUdpPort());
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
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000) // 连接超时设置
                    .childOption(ChannelOption.SO_KEEPALIVE, true)////设置TCP长连接,一般如果两个小时内没有数据的通信时,TCP会自动发送一个活动探测数据报文
                    .childOption(ChannelOption.TCP_NODELAY, true)///将小的数据包包装成更大的帧进行传送，提高网络的负载   // 将网络数据积累到一定的数量后,服务器端才发送出去,会造成一定的延迟。希望服务是低延迟的,建议将TCP_NODELAY设置为true
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        public void initChannel(SocketChannel ch) throws Exception {
                            ChannelPipeline pipeline = ch.pipeline();
                            // 添加长度字段基于的帧解码器（处理粘包/半包问题）
                            // 配置长度字段解码器：
                            // - 最大帧长度：10MB
                            // - 长度字段偏移：4（类型字段之后）
                            // - 长度字段长度：4（内容长度字段）
                            // - 长度调整：0（内容紧跟长度字段）
                            // - 初始跳过字节数：4（跳过类型字段，直接处理内容）
                            pipeline.addLast(new LengthFieldBasedFrameDecoder(
                                    10 * 1024 * 1024,  // 最大帧长度
                                    4,                 // 长度字段偏移量（跳过类型字段）
                                    4,                 // 长度字段长度（总长度字段）
                                    -8,                // 长度调整值 = 内容长度 - 总长度 = -8
                                    0                 // 跳过前12字节（类型+总长度+内容长度）  目前不跳过
                            ));
                            pipeline.addLast(new LengthFieldPrepender(4));
                            pipeline.addLast(new TCPKademliaMessageDecoder());
                            pipeline.addLast(new TCPKademliaMessageEncoder());
                            pipeline.addLast(new KademliaTcpHandler(KademliaNodeServer.this));
                        }
                    })
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.SO_KEEPALIVE, true);

            tcpBindFuture = nodeInfo.getIpv4() != null ? tcpBootstrap.bind(nodeInfo.getIpv4(), nodeInfo.getTcpPort()).sync() : tcpBootstrap.bind(nodeInfo.getTcpPort()).sync();
            log.info("TCP服务已启动，端口: {}", nodeInfo.getTcpPort());
        } catch (Exception e) {
            log.error("KademliaNode startTcpServer error", e);
        }
    }


    // 连接到引导节点
    public void connectToBootstrapNodes(NodeInfo bootstrapNodeInfo) throws Exception {
        if (bootstrapNodeInfo == null) return;
        //向引导节点发送Ping消息 实现握手
        PingKademliaMessage pingKademliaMessage = new PingKademliaMessage();
        pingKademliaMessage.setSender(nodeInfo);
        pingKademliaMessage.setReceiver(bootstrapNodeInfo);
        udpClient.sendMessage(pingKademliaMessage);
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











    public static class UDPKademliaMessageEncoder extends MessageToMessageEncoder<KademliaMessage<?>> {
        @Override
        protected void encode(ChannelHandlerContext channelHandlerContext, KademliaMessage<?> kademliaMessage, List<Object> list) throws Exception {
            // 序列化消息
            byte[] data = KademliaMessage.serialize(kademliaMessage);
            // 创建 ByteBuf 并写入消息数据
            ByteBuf buf = Unpooled.buffer(data.length + 12); // 4(类型) + 4(总长) + 4(内容长)
            buf.writeInt(kademliaMessage.getType());  // 写入消息类型 4
            //写入消息总长
            buf.writeInt(12 + data.length);//4
            //写入内容长度
            buf.writeInt(data.length);//4
            //写入内容
            buf.writeBytes(data);       // 写入消息内容
            NodeInfo receiver = kademliaMessage.getReceiver();
            InetSocketAddress inetSocketAddress = new InetSocketAddress(receiver.getIpv4(),receiver.getUdpPort());
            // 创建 DatagramPacket 并添加到输出列表
            list.add(new io.netty.channel.socket.DatagramPacket(buf, inetSocketAddress));
        }
    }



    public static class UDPKademliaMessageDecoder extends MessageToMessageDecoder<DatagramPacket> {

        @Override
        protected void decode(ChannelHandlerContext channelHandlerContext, DatagramPacket datagramPacket, List<Object> list) throws Exception {
            ByteBuf byteBuf = datagramPacket.content();
            if (byteBuf.readableBytes() < 12) {
                log.warn("确保有足够的数据读取消息类型和长度信息");
                return;
            }
            // 标记当前读取位置
            byteBuf.markReaderIndex();
            // 读取消息类型
            int messageType = byteBuf.readInt();
            log.info("消息类型:{}", MessageType.getDescriptionByCode(messageType));
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
            // 反序列化为消息对象
            KademliaMessage<?> message = KademliaMessage.deSerialize(contentBytes);
            log.info("UDP解码消息内容:{}", message);
            // 添加到输出列表
            list.add(message);
        }
    }






    /**
     * Kademlia消息编码器  将一个 Java 对象（类型为T）编码为ByteBuf（字节流）。
     */
    public static class TCPKademliaMessageEncoder extends MessageToByteEncoder<KademliaMessage<?>> {
        @Override
        protected void encode(ChannelHandlerContext channelHandlerContext, KademliaMessage<?> kademliaMessage, ByteBuf byteBuf) throws Exception {
            try {
                //序列化消息对象
                byte[] data = KademliaMessage.serialize(kademliaMessage);
                // 2. 写入消息类型（4字节整数）
                byteBuf.writeInt(kademliaMessage.getType());  //4
                //写入消息总长
                byteBuf.writeInt(12 + data.length);//4
                //写入内容长度
                byteBuf.writeInt(data.length);//4  //32 位（4 字节）的整数
                //写入类容
                byteBuf.writeBytes(data);
            } catch (Exception e) {
                System.err.println("Encode error: " + e.getMessage());
                throw e;
            }
        }
    }

    /**
     * Kademlia消息解码器
     */
    public static class TCPKademliaMessageDecoder extends ByteToMessageDecoder {
        @Override
        protected void decode(ChannelHandlerContext channelHandlerContext, ByteBuf byteBuf, List<Object> list) throws Exception {
            log.info("TCP开始解码");
            if (byteBuf.readableBytes() < 12) {
                log.warn("确保有足够的数据读取消息类型和长度信息");
                return;
            }
            // 标记当前读取位置
            byteBuf.markReaderIndex();
            // 读取消息类型
            int messageType = byteBuf.readInt();
            log.info("消息类型:{}", MessageType.getDescriptionByCode(messageType));
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
            // 反序列化为消息对象
            KademliaMessage<?> message = KademliaMessage.deSerialize(contentBytes);
            log.info("TCP解码消息内容:{}", message);
            // 添加到输出列表
            list.add(message);
        }
    }

}
