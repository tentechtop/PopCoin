package com.pop.popcoinsystem.network.service;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.pop.popcoinsystem.data.block.Block;
import com.pop.popcoinsystem.network.protocol.message.HandshakeRequestMessage;
import com.pop.popcoinsystem.network.protocol.messageData.Handshake;
import com.pop.popcoinsystem.network.protocol.messageHandler.TransactionMessageHandler;
import com.pop.popcoinsystem.network.common.ExternalNodeInfo;
import com.pop.popcoinsystem.network.common.NodeInfo;
import com.pop.popcoinsystem.network.common.NodeSettings;
import com.pop.popcoinsystem.network.common.RoutingTable;
import com.pop.popcoinsystem.network.protocol.MessageType;
import com.pop.popcoinsystem.network.protocol.message.FindNodeRequestMessage;
import com.pop.popcoinsystem.network.protocol.message.KademliaMessage;
import com.pop.popcoinsystem.network.protocol.message.PingKademliaMessage;
import com.pop.popcoinsystem.network.protocol.messageHandler.*;
import com.pop.popcoinsystem.event.DisruptorManager;
import com.pop.popcoinsystem.network.rpc.RpcServiceRegistry;
import com.pop.popcoinsystem.service.blockChain.BlockChainServiceImpl;
import com.pop.popcoinsystem.util.BeanCopyUtils;
import com.pop.popcoinsystem.util.CryptoUtil;
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


import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;

import static com.pop.popcoinsystem.constant.BlockChainConstants.GENESIS_BLOCK_HASH_HEX;
import static com.pop.popcoinsystem.constant.BlockChainConstants.NET_VERSION;
// 替换为带容量限制的LRU Map（需引入Guava或自定义）


@Slf4j
@Data
@Service
@NoArgsConstructor
@Scope("singleton") // 显式指定单例
public class KademliaNodeServer {

    // 消息过期时间（毫秒）
    public static final long MESSAGE_EXPIRATION_TIME = 30000;
    // 节点过期时间（毫秒）
    public static final long NODE_EXPIRATION_TIME = 60000;//5分钟心跳一次 10分钟过期
    //节点信息
    private NodeInfo nodeInfo;

    private RpcServiceRegistry rpcServiceRegistry;

    @Lazy
    @Autowired
    private BlockChainServiceImpl blockChainService;

    //额外信息  包含了节点信息  外网ip  外网端口  内网ip  内网端口  节点状态  节点版本  节点类型  节点描述 节点分数 等待
    private ExternalNodeInfo externalNodeInfo;

    private NodeSettings nodeSettings;
    //路由表
    private RoutingTable routingTable;
    //节点设置
    //消息处理器
    public static final  Map<Integer, MessageHandler> KademliaMessageHandler = new ConcurrentHashMap<>();
    //UDP客服端
    //TCP客服端
    private UDPClient udpClient;
    private TCPClient tcpClient;

    // 替换原有ConcurrentHashMap为Guava Cache：自动过期+最大容量
    private final Cache<Long, Boolean> broadcastMessages = CacheBuilder.newBuilder()
            .maximumSize(10000) // 最大缓存10000条消息（防止内存溢出）
            .expireAfterWrite(30, TimeUnit.SECONDS) // 写入后30秒自动过期（无需手动清理）
            .concurrencyLevel(Runtime.getRuntime().availableProcessors()) // 并发级别（默认4，可设为CPU核心数）
            .build();


    public Cache<Long, Boolean> getBroadcastMessages() {
        return broadcastMessages;
    }





    //UDP服务
    private EventLoopGroup udpGroup;
    private Bootstrap udpBootstrap;

    //TCP服务  长连接 能实时获取数据  节点间主动维持持久连接 + 事件驱动的实时广播  这样能随时获取如何消息 以及任何变化
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private ServerBootstrap tcpBootstrap;

    private DisruptorManager disruptorManager;

    // 在KademliaNodeServer的start()方法中初始化定时任务
    private ScheduledExecutorService scheduler;

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

    public void init() {
        routingTable = new RoutingTable(nodeInfo.getId(), nodeSettings);
        routingTable.recoverFromNodeList();

        //注册消息处理器
        this.registerMessageHandler(MessageType.EMPTY.getCode(), new EmptyMessageHandler());
        this.registerMessageHandler(MessageType.PING.getCode(), new PingMessageHandler());
        this.registerMessageHandler(MessageType.PONG.getCode(), new PongMessageHandler());
        this.registerMessageHandler(MessageType.FIND_NODE_REQ.getCode(), new FindNodeRequestMessageHandler());
        this.registerMessageHandler(MessageType.FIND_NODE_RES.getCode(), new FindNodeResponseMessageHandler());
        this.registerMessageHandler(MessageType.HANDSHAKE_RES.getCode(), new HandshakeResponseMessageHandle());
        this.registerMessageHandler(MessageType.HANDSHAKE_REQ.getCode(), new HandshakeRequestMessageHandle());
        this.registerMessageHandler(MessageType.TRANSACTION.getCode(), new TransactionMessageHandler());
        this.registerMessageHandler(MessageType.BLOCK.getCode(), new BlockMessageHandler());
        this.registerMessageHandler(MessageType.SHUTDOWN.getCode(), new ShutdownMessageHandler());

        this.registerMessageHandler(MessageType.RPC_REQUEST.getCode(), new RpcRequestMessageHandler());
        this.registerMessageHandler(MessageType.RPC_RESPONSE.getCode(), new RpcResponseMessageHandler());


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
            scheduler = Executors.newSingleThreadScheduledExecutor();
            //维护网络 首次执行立即开始，之后每 delay  秒执行一次 maintainNetwork 方法  单位秒
            long delay = 30;
            scheduler.scheduleAtFixedRate(this::maintainNetwork, delay, delay, TimeUnit.SECONDS);
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
                            // 帧解码器：解析类型(4) + 版本(4) + 内容长度(4) + 内容结构
                            pipeline.addLast(new LengthFieldBasedFrameDecoder(
                                    10 * 1024 * 1024,  // 最大帧长度
                                    8,                 // 长度字段偏移量（跳过类型4字节 + 版本4字节）
                                    4,                 // 长度字段长度（内容长度字段，4字节）
                                    0,                 // 长度调整值（总长度 = 内容长度 + 12字节头部）
                                    0                  // 不跳过字节
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
                            // 帧解码器：解析类型(4) + 版本(4) + 内容长度(4) + 内容结构
                            pipeline.addLast(new LengthFieldBasedFrameDecoder(
                                    10 * 1024 * 1024,  // 最大帧长度
                                    8,                 // 长度字段偏移量（跳过类型4字节 + 版本4字节）
                                    4,                 // 长度字段长度（内容长度字段，4字节）
                                    0,                 // 长度调整值（总长度 = 内容长度 + 12字节头部）
                                    0                  // 不跳过字节
                            ));
                            pipeline.addLast(new LengthFieldPrepender(4));
                            pipeline.addLast(new TCPKademliaMessageDecoder());
                            pipeline.addLast(new TCPKademliaMessageEncoder());
                            pipeline.addLast(new KademliaTcpHandler(KademliaNodeServer.this,tcpClient));
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
        //向引导节点发送Ping消息 回复pong之后 将引导节点加入网络
        PingKademliaMessage pingKademliaMessage = new PingKademliaMessage();
        pingKademliaMessage.setSender(this.nodeInfo);//本节点信息
        pingKademliaMessage.setReceiver(bootstrapNodeInfo);
        udpClient.sendMessage(pingKademliaMessage);

        //向引导节点发送握手请求 收到握手回复后检查 自己的区块链信息
        BlockChainServiceImpl blockChainService = this.getBlockChainService();
        Block mainLatestBlock = blockChainService.getMainLatestBlock();
        Handshake handshake = new Handshake();
        handshake.setExternalNodeInfo(this.getExternalNodeInfo());//携带我的节点信息
        handshake.setGenesisBlockHash(CryptoUtil.hexToBytes(GENESIS_BLOCK_HASH_HEX));
        handshake.setLatestBlockHash(mainLatestBlock.getHash());
        handshake.setLatestBlockHeight(mainLatestBlock.getHeight());
        handshake.setChainWork(mainLatestBlock.getChainWork());
        HandshakeRequestMessage handshakeRequestMessage = new HandshakeRequestMessage(handshake);
        handshakeRequestMessage.setSender(this.nodeInfo);//本节点信息
        handshakeRequestMessage.setReceiver(bootstrapNodeInfo);
        this.getTcpClient().sendMessage(handshakeRequestMessage);

    }


    @PreDestroy
    public void stop() throws InterruptedException {
        this.running = false;
        if (scheduler != null){
            scheduler.shutdown(); // 优雅关闭
        }
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

    public void broadcastMessage(KademliaMessage kademliaMessage) {
        long messageId = kademliaMessage.getMessageId();
        long now = System.currentTimeMillis();
        // 检查：若消息已存在（未过期），则跳过广播
        if (broadcastMessages.getIfPresent(messageId) != null) {
            log.debug("消息 {} 已广播过（未过期），跳过", messageId);
            return;
        }
        // 记录：将消息ID存入缓存（自动过期）
        broadcastMessages.put(messageId, Boolean.TRUE);
        //将消息发送给已知节点
        List<ExternalNodeInfo> closest = this.getRoutingTable().findClosest(this.nodeInfo.getId());
        //去除自己
        closest.removeIf(node -> node.getId().equals(this.nodeInfo.getId()));
        log.debug("广播消息: {}",closest);
        for (ExternalNodeInfo externalNodeInfo: closest) {
            try {
                kademliaMessage.setReceiver(BeanCopyUtils.copyObject(externalNodeInfo, NodeInfo.class));
                udpClient.sendAsyncMessage(kademliaMessage);
            }catch (Exception e){
                log.error("Error when sending message to node: {}", externalNodeInfo.getId(), e);
            }
        }
    }

    /**
     * 维护网络
     */
    public void maintainNetwork() {
        log.debug("开始维护网络");
        RoutingTable routingTable = getRoutingTable();
        try {
            long now = System.currentTimeMillis();
            // 2. 检查路由表中节点的活性，移除不活跃节点
            checkNodeLiveness(now);
            // 3. 随机生成节点ID，执行FindNode操作刷新路由表（Kademlia协议核心）
            refreshRoutingTable();
            // 4. 持久化路由表（可选，节点重启后可恢复）
            routingTable.persistToStorage();
        } catch (Exception e) {
            log.error("网络维护任务执行失败", e);
        }
    }


    /**
     * 检查节点活性：对超时未响应的节点发送发送Ping，仍无响应则移除
     */
    private void checkNodeLiveness(long now) {
        // 获取路由表中所有节点  或者本节点最近的节点 或者用抽样检测
        List<ExternalNodeInfo> allNodes = routingTable.getAllNodes();
        if (allNodes.isEmpty()) {
            log.debug("路由表为空，无需检查节点活性");
            return;
        }
        for (ExternalNodeInfo node : allNodes) {
            // 跳过自身节点
            if (node.getId().equals(nodeInfo.getId())) continue;

            // 计算节点最后活跃时间与当前的差值
            long inactiveTime = now - node.getLastSeen().getTime();

            // 1. 节点已过期（超过阈值），直接移除
            if (inactiveTime > NODE_EXPIRATION_TIME) {
                log.info("节点 {} 已过期（{}ms未响应），直接移除", node.getId(), inactiveTime);
                routingTable.delete(node);
                continue;
            }

            // 2. 节点即将过期（接近阈值阈值），发送Ping确认活性
            if (inactiveTime > NODE_EXPIRATION_TIME * 0.8) {
                log.debug("节点 {} 即将过期，发送Ping确认活性", node.getId());
                sendPingCheckPing(node);
            }
        }
    }

    /**
     * 向节点发送Ping消息检查活性，超时未响应则移除
     */
    private void sendPingCheckPing(ExternalNodeInfo node) {
        try {
            // 构建Ping消息
            PingKademliaMessage pingMsg = new PingKademliaMessage();
            pingMsg.setSender(nodeInfo);
            pingMsg.setReceiver(BeanCopyUtils.copyObject(node, NodeInfo.class));
            udpClient.sendAsyncMessage(pingMsg);
        } catch (Exception e) {
            log.error("向节点 {} 发送Ping检查失败", node.getId(), e);
        }
    }


    /**
     * 清理过期的广播消息
     */
/*
    private void cleanExpiredBroadcastMessages(long now) {
        broadcastMessages.entrySet().removeIf(entry ->
                now - entry.getValue() > MESSAGE_EXPIRATION_TIME
        );
        log.debug("清理 过期广播消息，剩余: {}", broadcastMessages.size());
    }
*/




    /**
     * 刷新路由表：随机生成节点ID，执行FindNode发现新节点
     * （Kademlia协议通过随机查找填充路由表，确保网络覆盖）
     */
    private void refreshRoutingTable() {
        // 随机生成一个160位的节点ID（Kademlia通常使用160位ID）
        BigInteger randomTargetId = new BigInteger(160, new Random());

        // 查找当前路由表中离目标ID最近的节点
        List<ExternalNodeInfo> closestNodes = routingTable.findClosest(randomTargetId);
        if (closestNodes.isEmpty()) {
            log.debug("路由表为空，无法执行FindNode刷新");
            return;
        }
        // 向这些节点发送FindNode请求，获取更多节点信息
        for (ExternalNodeInfo peer : closestNodes) {
            try {
                FindNodeRequestMessage findNodeMsg = new FindNodeRequestMessage();
                findNodeMsg.setSender(nodeInfo);
                findNodeMsg.setReceiver(BeanCopyUtils.copyObject(peer, NodeInfo.class));
                findNodeMsg.setData(randomTargetId); // 要查找的目标ID

                udpClient.sendAsyncMessage(findNodeMsg);
                log.debug("向节点 {} 发送FindNode请求，目标ID: {}", peer.getId(), randomTargetId);
            } catch (Exception e) {
                log.error("向节点 {} 发送FindNode失败", peer.getId(), e);
            }
        }
    }

    public NodeInfo getNodeInfo(BigInteger nodeId) {
        return routingTable.getNodeInfo(nodeId);
    }


    public static class UDPKademliaMessageEncoder extends MessageToMessageEncoder<KademliaMessage<?>> {
        @Override
        protected void encode(ChannelHandlerContext channelHandlerContext, KademliaMessage<?> kademliaMessage, List<Object> list) throws Exception {
            // 序列化消息
            byte[] data = KademliaMessage.serialize(kademliaMessage);
            // 创建 ByteBuf 并写入消息数据
            ByteBuf buf = Unpooled.buffer(data.length + 12); // 4(类型) + 4(网络版本) + 4(内容长)
            buf.writeInt(kademliaMessage.getType());  // 写入消息类型 4
            //写入网络版本
            buf.writeInt(NET_VERSION);//4
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
            log.debug("消息类型:{}", MessageType.getDescriptionByCode(messageType));
            // 读取总长度（内容长度字段 + 内容长度）
            int netVersion = byteBuf.readInt();
            log.debug("网络版本:{}", netVersion);
            //是否和我的网络版本一致
            if (netVersion != NET_VERSION) {
                log.warn("网络版本不一致");
                return;
            }
            // 读取内容长度
            int contentLength = byteBuf.readInt();
            log.debug("内容长度:{}", contentLength);
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
            log.debug("UDP解码消息内容:{}", message);
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
                //写入网络版本
                byteBuf.writeInt(NET_VERSION);//4
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
            if (byteBuf.readableBytes() < 12) {
                log.warn("确保有足够的数据读取消息类型和长度信息");
                return;
            }
            // 标记当前读取位置
            byteBuf.markReaderIndex();
            // 读取消息类型
            int messageType = byteBuf.readInt();
            log.debug("消息类型:{}", MessageType.getDescriptionByCode(messageType));
            // 读取总长度（内容长度字段 + 内容长度）
            int netVersion = byteBuf.readInt();
            log.debug("网络版本:{}", netVersion);
            //是否和我的网络版本一致
            if (netVersion != NET_VERSION) {
                log.warn("网络版本不一致");
                return;
            }
            // 读取内容长度
            int contentLength = byteBuf.readInt();
            log.debug("内容长度:{}", contentLength);
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
            // 添加到输出列表
            list.add(message);
        }
    }
}
