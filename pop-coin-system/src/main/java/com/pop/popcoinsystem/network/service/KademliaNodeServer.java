package com.pop.popcoinsystem.network.service;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.pop.popcoinsystem.data.block.Block;
import com.pop.popcoinsystem.network.protocol.message.*;
import com.pop.popcoinsystem.network.protocol.messageData.Handshake;
import com.pop.popcoinsystem.network.protocol.messageHandler.TransactionMessageHandler;
import com.pop.popcoinsystem.network.common.ExternalNodeInfo;
import com.pop.popcoinsystem.network.common.NodeInfo;
import com.pop.popcoinsystem.network.common.NodeSettings;
import com.pop.popcoinsystem.network.common.RoutingTable;
import com.pop.popcoinsystem.network.protocol.MessageType;
import com.pop.popcoinsystem.network.protocol.messageHandler.*;
import com.pop.popcoinsystem.event.DisruptorManager;
import com.pop.popcoinsystem.network.rpc.RequestResponseManager;
import com.pop.popcoinsystem.network.rpc.RpcServiceRegistry;
import com.pop.popcoinsystem.service.blockChain.BlockChainServiceImpl;
import com.pop.popcoinsystem.util.BeanCopyUtils;
import com.pop.popcoinsystem.util.CryptoUtil;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
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
import org.springframework.boot.util.LambdaSafe;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigInteger;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;

import static com.pop.popcoinsystem.constant.BlockChainConstants.NET_VERSION;
// 替换为带容量限制的LRU Map（需引入Guava或自定义）


@Slf4j
@Data
@Service
@NoArgsConstructor
@Scope("singleton") // 显式指定单例
public class KademliaNodeServer {

    // 消息过期时间
    public static final long MESSAGE_EXPIRATION_TIME = 30000;
    // 节点过期时间
    public static final long NODE_EXPIRATION_TIME = 60000;
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
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "kademlia-scheduler");
                thread.setDaemon(true); // 守护线程，随应用退出
                return thread;
            });
            //维护网络 首次执行立即开始，之后每 delay  秒执行一次 maintainNetwork 方法  单位秒
            long delay = 15;//15秒
            long delay1 = 15 * 4 * 2;
            long delay2 = 15 * 4 * 4;
            scheduler.scheduleAtFixedRate(this::maintainNetwork, 0, delay, TimeUnit.SECONDS);
            scheduler.scheduleAtFixedRate(this::refreshRoutingTable, delay1, delay1, TimeUnit.SECONDS);
            scheduler.scheduleAtFixedRate(this::persistToStorage, delay2, delay2, TimeUnit.SECONDS);
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
                    // 核心Channel参数优化
                    .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT) // 池化内存分配（减少GC）
                    .option(ChannelOption.SO_RCVBUF, 64*1024*1024) // 接收缓冲区
                    .option(ChannelOption.SO_REUSEADDR, true) // 允许端口复用（多线程共享端口）
                    .option(ChannelOption.SO_BROADCAST, true) // 支持广播（按需开启）
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 1000) // UDP无连接，超时设短（1秒）
                    .option(ChannelOption.RCVBUF_ALLOCATOR, new FixedRecvByteBufAllocator(1024 * 64)) // 固定接收缓冲区大小（64KB，减少动态调整开销）

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
            this.udpClient = new UDPClient(KademliaNodeServer.this);
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

                    // 3. TCP参数优化
                    .option(ChannelOption.SO_REUSEADDR, true) // 允许端口复用
                    .option(ChannelOption.SO_RCVBUF, 64* 1024 * 1024) // 接收缓冲区
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)

                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        public void initChannel(SocketChannel ch) throws Exception {
                            ChannelPipeline pipeline = ch.pipeline();
                            pipeline.addLast(new ChannelDuplexHandler() {
                                @Override
                                public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
                                    // 捕获 Connection reset 异常
                                    log.info("发生连接重置（Connection reset）");
                                    super.exceptionCaught(ctx, cause);
                                }
                            });
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
                            pipeline.addLast(new KademliaTcpHandler(KademliaNodeServer.this));
                        }
                    })
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.SO_KEEPALIVE, true);

            tcpBindFuture = nodeInfo.getIpv4() != null ? tcpBootstrap.bind(nodeInfo.getIpv4(), nodeInfo.getTcpPort()).sync() : tcpBootstrap.bind(nodeInfo.getTcpPort()).sync();
            log.info("TCP服务已启动，端口: {}", nodeInfo.getTcpPort());
            this.tcpClient = new TCPClient(KademliaNodeServer.this);
        } catch (Exception e) {
            log.error("KademliaNode startTcpServer error", e);
        }
    }

    //向所有已知节点发送Ping
    public void sendOnlineStatus() {
        List<ExternalNodeInfo> allNodes = routingTable.findALLClosest();
        for (ExternalNodeInfo node : allNodes){
            Thread.startVirtualThread(() -> {
                PingKademliaMessage pingKademliaMessage = new PingKademliaMessage();
                pingKademliaMessage.setSender(this.nodeInfo); // 本节点信息
                pingKademliaMessage.setReceiver(node.extractNodeInfo());
                udpClient.sendAsyncMessage(pingKademliaMessage);
            });
        }
    }

    // 建立定时任务 直到连接成功
    public CompletableFuture<Void> connectToNode(NodeInfo bootstrapNodeInfo) {
        CompletableFuture<Void> resultFuture = new CompletableFuture<>();
        if (bootstrapNodeInfo == null) {
            resultFuture.complete(null);
            return resultFuture;
        }

        // 重试配置参数（提取为常量，保持原逻辑）
        final int MAX_RETRIES = 32;
        final long INITIAL_RETRY_INTERVAL = 5000;
        final long MAX_RETRY_INTERVAL = 60000;

        // 启动虚拟线程执行连接逻辑（虚拟线程轻量，适合IO密集型重试场景）
        Thread.startVirtualThread(() -> {
            int retryCount = 0;
            try {
                while (retryCount < MAX_RETRIES) {
                    // 1. 创建Ping消息（提取为辅助方法，减少重复代码）
                    PingKademliaMessage pingMessage = createPingMessage(bootstrapNodeInfo);

                    try {
                        // 2. 发送Ping并等待响应（UDP异步调用保持不变）
                        CompletableFuture<KademliaMessage> responseFuture = udpClient.sendMessageWithResponse(pingMessage);
                        KademliaMessage response = responseFuture.get(5, TimeUnit.SECONDS);

                        // 3. 处理响应结果
                        if (response == null) {
                            log.warn("未收到引导节点{}的Pong消息，第{}次重试将在{}ms后进行",
                                    bootstrapNodeInfo, retryCount + 1,
                                    calculateBackoffInterval(retryCount, INITIAL_RETRY_INTERVAL, MAX_RETRY_INTERVAL));
                        } else if (response instanceof PongKademliaMessage) {
                            // 3.1 收到Pong，执行握手逻辑（提取为辅助方法）
                            performHandshake(bootstrapNodeInfo);
                            log.info("成功与引导节点{}建立连接，共尝试{}次", bootstrapNodeInfo, retryCount + 1);
                            resultFuture.complete(null); // 连接成功，完成Future
                            return;
                        } else {
                            log.warn("收到引导节点{}的非Pong响应，第{}次重试将在{}ms后进行",
                                    bootstrapNodeInfo, retryCount + 1,
                                    calculateBackoffInterval(retryCount, INITIAL_RETRY_INTERVAL, MAX_RETRY_INTERVAL));
                        }

                        // 4. 计算退避时间并等待（虚拟线程中用LockSupport更高效，避免Thread.sleep的监控器占用）
                        long backoffInterval = calculateBackoffInterval(retryCount, INITIAL_RETRY_INTERVAL, MAX_RETRY_INTERVAL);
                        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(backoffInterval));
                        retryCount++;

                    } catch (ConnectException e) {
                        // 连接被拒绝异常处理
                        log.warn("连接引导节点失败：目标节点 {}:{} 拒绝连接，第{}次重试将在{}ms后进行",
                                bootstrapNodeInfo.getIpv4(), bootstrapNodeInfo.getUdpPort(),
                                retryCount + 1, calculateBackoffInterval(retryCount, INITIAL_RETRY_INTERVAL, MAX_RETRY_INTERVAL), e);
                        handleRetry(retryCount++, INITIAL_RETRY_INTERVAL, MAX_RETRY_INTERVAL);
                    } catch (TimeoutException e) {
                        // 超时异常处理
                        log.warn("与引导节点{}通信超时，第{}次重试将在{}ms后进行",
                                bootstrapNodeInfo, retryCount + 1,
                                calculateBackoffInterval(retryCount, INITIAL_RETRY_INTERVAL, MAX_RETRY_INTERVAL), e);
                        handleRetry(retryCount++, INITIAL_RETRY_INTERVAL, MAX_RETRY_INTERVAL);
                    } catch (InterruptedException e) {
                        // 中断处理：恢复中断状态并通知调用者
                        log.error("连接线程被中断，已重试{}次", retryCount, e);
                        Thread.currentThread().interrupt(); // 保留中断状态
                        resultFuture.completeExceptionally(e);
                        return;
                    } catch (Exception e) {
                        // 其他异常处理
                        log.warn("连接引导节点时发生错误: {}，第{}次重试将在{}ms后进行",
                                e.getMessage(), retryCount + 1,
                                calculateBackoffInterval(retryCount, INITIAL_RETRY_INTERVAL, MAX_RETRY_INTERVAL), e);
                        handleRetry(retryCount++, INITIAL_RETRY_INTERVAL, MAX_RETRY_INTERVAL);
                    }
                }

                // 达到最大重试次数，标记失败
                resultFuture.completeExceptionally(
                        new Exception("已达到最大重试次数(" + MAX_RETRIES + "次)，无法连接到引导节点" + bootstrapNodeInfo)
                );

            } catch (Exception e) {
                // 捕获循环外的异常，确保Future正确完成
                resultFuture.completeExceptionally(e);
            }
        });

        return resultFuture;
    }

    /**
     * 辅助方法：创建Ping消息
     */
    private PingKademliaMessage createPingMessage(NodeInfo receiver) {
        PingKademliaMessage ping = new PingKademliaMessage();
        ping.setSender(this.nodeInfo);
        ping.setReceiver(receiver);
        ping.setReqResId();
        ping.setResponse(false);
        return ping;
    }

    /**
     * 辅助方法：执行握手逻辑
     */
    private void performHandshake(NodeInfo bootstrapNodeInfo) throws Exception {
        BlockChainServiceImpl blockChainService = this.getBlockChainService();
        byte[] genesisHash = blockChainService.GENESIS_BLOCK_HASH();
        Handshake handshake = new Handshake();
        handshake.setExternalNodeInfo(this.getExternalNodeInfo());
        handshake.setGenesisBlockHash(genesisHash);

        if (genesisHash == null) {
            handshake.setLatestBlockHash(null);
            handshake.setLatestBlockHeight(-1);
            handshake.setChainWork(new byte[0]);
        } else {
            Block mainLatestBlock = blockChainService.getMainLatestBlock();
            handshake.setLatestBlockHash(mainLatestBlock.getHash());
            handshake.setLatestBlockHeight(mainLatestBlock.getHeight());
            handshake.setChainWork(mainLatestBlock.getChainWork());
        }

        HandshakeRequestMessage handshakeMsg = new HandshakeRequestMessage(handshake);
        handshakeMsg.setSender(this.nodeInfo);
        handshakeMsg.setReceiver(bootstrapNodeInfo);
        this.getTcpClient().sendMessage(handshakeMsg);
    }

    /**
     * 辅助方法：处理重试等待（虚拟线程友好的退避逻辑）
     */
    private void handleRetry(int retryCount, long initialInterval, long maxInterval) {
        long backoff = calculateBackoffInterval(retryCount, initialInterval, maxInterval);
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(backoff));
    }

    /**
     * 计算指数退避间隔时间
     * @param retryCount 当前重试次数(从0开始)
     * @param initialInterval 初始间隔
     * @param maxInterval 最大间隔上限
     * @return 计算后的间隔时间
     */
    private long calculateBackoffInterval(int retryCount, long initialInterval, long maxInterval) {
        // 指数退避公式：initialInterval * (2^retryCount)，但不超过最大间隔
        return Math.min(initialInterval * (1L << retryCount), maxInterval);
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

    /**
     *
     * @param kademliaMessage
     * @param sender 原始发送者
     */
    public void broadcastMessage(KademliaMessage kademliaMessage,NodeInfo sender) {
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
        //去除原作者
        closest.removeIf(node -> node.getId().equals(sender.getId()));

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
        try {
            long now = System.currentTimeMillis();
            // 检查路由表中节点的活性，移除不活跃节点
            checkNodeLiveness(now);
        } catch (Exception e) {
            log.error("网络维护任务执行失败", e);
        }
    }


    private void persistToStorage(){
        log.info("开始将路由表未过期的节点持久化到存储系统");
        // 随机生成节点ID，执行FindNode操作刷新路由表（Kademlia协议核心）
        routingTable.cleanExpiredNodes(NODE_EXPIRATION_TIME);
        routingTable.persistToStorage();
    }


    /**
     * 检查节点活性：对超时未响应的节点发送发送Ping，仍无响应则移除
     */
    private void checkNodeLiveness(long now) {
        // 获取路由表中所有节点  或者本节点最近的节点 或者用抽样检测
        List<ExternalNodeInfo> allNodes = routingTable.getAllActiveNodes();
        if (allNodes.isEmpty()) {
            log.debug("路由表为空，无需检查节点活性");
            return;
        }
        for (ExternalNodeInfo node : allNodes) {
            log.info("检查节点 {} 活跃性", node.getId());
            // 跳过自身节点
            if (node.getId().equals(nodeInfo.getId())) continue;
            // 计算节点最后活跃时间与当前的差值
            long inactiveTime = now - node.getLastSeen().getTime();
            // 1. 节点已过期（超过阈值），直接移除
            if (inactiveTime > NODE_EXPIRATION_TIME) {
                log.info("节点 {} 已过期（{}s未响应），直接移除", node.getId(), inactiveTime/1000);
                routingTable.offlineNode(node.getId());
                //删除TCP中通道
                tcpClient.removeChannel(node.getId());
                continue;
            }
            log.info("节点 {} 活跃，最后活跃时间：{}s前", node.getId(), inactiveTime/1000);

            // 构建Ping消息
            PingKademliaMessage pingMsg = new PingKademliaMessage();
            pingMsg.setSender(nodeInfo);
            pingMsg.setReceiver(node.extractNodeInfo());
            udpClient.sendAsyncMessage(pingMsg);
        }
    }



    /**
     * 刷新路由表：随机生成节点ID，执行FindNode发现新节点
     * （Kademlia协议通过随机查找填充路由表，确保网络覆盖）
     */
    private void refreshRoutingTable() {
        log.info("刷新路由表");
        // 随机生成一个160位的节点ID（Kademlia通常使用160位ID）
        BigInteger randomTargetId = new BigInteger(160, new Random());
        /*BigInteger randomTargetId = nodeInfo.getId();*/
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

    public void removeNode(BigInteger id) {
        routingTable.removeNode(id);
    }

    //下线节点 并减少分数
    public void offlineNode(BigInteger id) {
        routingTable.offlineNode(id);
    }

    public BigInteger getId() {
        return nodeInfo.getId();
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
