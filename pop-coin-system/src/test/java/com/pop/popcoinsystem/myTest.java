package com.pop.popcoinsystem;

import com.pop.popcoinsystem.network.KademliaNodeServer;
import com.pop.popcoinsystem.network.KademliaUdpHandler;
import com.pop.popcoinsystem.network.UDPClient;
import com.pop.popcoinsystem.network.common.Bucket;
import com.pop.popcoinsystem.network.common.ExternalNodeInfo;
import com.pop.popcoinsystem.network.common.NodeInfo;
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
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

import static java.lang.Thread.sleep;


@Slf4j
public class myTest {
    public static void main(String[] args)  {
        try{
            KademliaNodeServer kademliaNodeServer = new KademliaNodeServer(BigInteger.valueOf(1L), "127.0.0.1", 8333, 8334);
            NodeInfo nodeInfo1 = kademliaNodeServer.getNodeInfo();


            sleep(1000);
            KademliaNodeServer kademliaNodeServer2 = new KademliaNodeServer(BigInteger.valueOf(86L), "127.0.0.1", 8335, 8336);
            kademliaNodeServer2.start();
            kademliaNodeServer2.connectToBootstrapNodes(nodeInfo1);

            sleep(1000);
            KademliaNodeServer kademliaNodeServer3 = new KademliaNodeServer(BigInteger.valueOf(3L), "127.0.0.1", 8337, 8338);
            kademliaNodeServer3.start();
            kademliaNodeServer3.connectToBootstrapNodes(nodeInfo1);
            NodeInfo nodeInfo = kademliaNodeServer3.getNodeInfo();


            UDPClient udpClient = kademliaNodeServer3.getUdpClient();
            PingKademliaMessage pingKademliaMessage = new PingKademliaMessage();
            pingKademliaMessage.setSender(nodeInfo);
            pingKademliaMessage.setReceiver(nodeInfo1);
            udpClient.sendMessage(pingKademliaMessage);




        }catch (Exception e){
            e.printStackTrace();
        }
    }
}
