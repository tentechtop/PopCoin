package com.pop.popcoinsystem;

import com.pop.popcoinsystem.network.KademliaNodeServer;
import com.pop.popcoinsystem.network.KademliaUdpHandler;
import com.pop.popcoinsystem.network.common.ExternalNodeInfo;
import com.pop.popcoinsystem.network.common.RoutingTable;
import com.pop.popcoinsystem.network.protocol.message.KademliaMessage;
import com.pop.popcoinsystem.network.protocol.message.PingKademliaMessage;
import com.pop.popcoinsystem.util.SerializeUtils;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.util.CharsetUtil;
import lombok.extern.slf4j.Slf4j;

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.UUID;

import static java.lang.Thread.sleep;


@Slf4j
public class myTest {
    public static void main(String[] args)  {
        try{
            KademliaNodeServer kademliaNodeServer = new KademliaNodeServer(BigInteger.valueOf(1L), "127.0.0.1", 8333, 8334);
            kademliaNodeServer.start();

            sleep(1000);
            KademliaNodeServer kademliaNodeServer2 = new KademliaNodeServer(BigInteger.valueOf(86L), "127.0.0.1", 8335, 8336);
            kademliaNodeServer2.start();
            kademliaNodeServer2.connectToBootstrapNodes(kademliaNodeServer.getNodeInfo());

            sleep(1000);
            KademliaNodeServer kademliaNodeServer3 = new KademliaNodeServer(BigInteger.valueOf(3L), "127.0.0.1", 8337, 8338);
            kademliaNodeServer3.start();
            kademliaNodeServer3.connectToBootstrapNodes(kademliaNodeServer.getNodeInfo());


            sleep(3000);
            log.info("所有节点已启动并连接"); // 添加这行检查程序是否执行到这里
            //打印路由表
            log.info("路由表：{}", kademliaNodeServer.getRoutingTable());
            RoutingTable routingTable = kademliaNodeServer.getRoutingTable();
            List<ExternalNodeInfo> closest = routingTable.findClosest(BigInteger.valueOf(1L));
            for (ExternalNodeInfo nodeInfo : closest) {
                log.info("最接近的节点：{}", nodeInfo);
                log.info("节点距离：{}", nodeInfo.getDistance());
            }
        }catch (Exception e){
            e.printStackTrace();
        }
    }
}
