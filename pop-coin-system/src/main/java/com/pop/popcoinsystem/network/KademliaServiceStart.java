package com.pop.popcoinsystem.network;

import com.pop.popcoinsystem.network.common.BootstrapNode;
import com.pop.popcoinsystem.network.common.ExternalNodeInfo;
import com.pop.popcoinsystem.network.common.NodeInfo;
import com.pop.popcoinsystem.network.common.NodeSettings;
import com.pop.popcoinsystem.network.enums.NodeType;
import com.pop.popcoinsystem.network.rpc.RpcServiceRegistry;
import com.pop.popcoinsystem.network.service.KademliaNodeServer;
import com.pop.popcoinsystem.storage.NodeInfoStorageService;
import com.pop.popcoinsystem.util.BeanCopyUtils;
import com.pop.popcoinsystem.util.CryptoUtil;
import com.pop.popcoinsystem.util.NetworkUtil;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.List;

@Data
@Slf4j
@Configuration
@ConfigurationProperties(prefix = "kademlia") // 绑定kademlia前缀的配置
public class KademliaServiceStart {
    @Autowired
    private NodeInfoStorageService storageService;

    @Value("${kademlia.node.udpPort:8334}")
    private int udpPort;

    @Value("${kademlia.node.tcpPort:8333}")
    private int tcpPort;

    //节点类型
    @Value("${kademlia.node.nodeType:1}") //1全节点 #2轻节点 #仅出站
    private int nodeType;

    private int isBootstrap;

    // 引导节点列表（从配置文件读取）
    private List<BootstrapNode> bootstrap;

    // 1. 注入Spring管理的KademliaNodeServer实例
    @Lazy
    @Autowired
    private KademliaNodeServer kademliaNodeServer;

    private RpcServiceRegistry rpcServiceRegistry;


    // 定义单例Bean，Spring会确保仅创建一次
    @Bean
    public KademliaNodeServer kademliaNodeServer() throws Exception {
        String localIp = NetworkUtil.getLocalIp();// 获取本机IP
        NodeSettings nodeSetting = storageService.getNodeSetting();
        KeyPair keyPair = CryptoUtil.ECDSASigner.generateKeyPair();
        PrivateKey privateKey = keyPair.getPrivate();
        PublicKey publicKey = keyPair.getPublic();
        if (nodeSetting == null){
            NodeSettings build = NodeSettings.Default.build();
            nodeSetting = BeanCopyUtils.copyObject(build, NodeSettings.class);
            //生成这个节点的公钥和私钥并保存到文件夹
            nodeSetting.setPrivateKeyHex(CryptoUtil.bytesToHex(privateKey.getEncoded()));
            nodeSetting.setPublicKeyHex(CryptoUtil.bytesToHex(publicKey.getEncoded()));
            byte[] bytes = CryptoUtil.applyRIPEMD160(CryptoUtil.applySHA256(publicKey.getEncoded()));
            if (bytes.length != 20) {
                throw new IllegalArgumentException("RIPEMD-160 输出必须是 20 字节");
            }
            BigInteger bigInteger = new BigInteger(1, bytes);
            nodeSetting.setId(bigInteger);
        }else {
            if (nodeSetting.getPublicKeyHex().isEmpty() || nodeSetting.getPrivateKeyHex().isEmpty()){
                nodeSetting.setPrivateKeyHex(CryptoUtil.bytesToHex(privateKey.getEncoded()));
                nodeSetting.setPublicKeyHex(CryptoUtil.bytesToHex(publicKey.getEncoded()));
            }
        }
        nodeSetting.setNodeType(nodeType);//默认是全节点
        nodeSetting.setIpv4(localIp);
        nodeSetting.setTcpPort(tcpPort);
        nodeSetting.setUdpPort(udpPort);
        if (nodeSetting.getId() == null){
            byte[] bytes = CryptoUtil.applyRIPEMD160(CryptoUtil.applySHA256(publicKey.getEncoded()));
            if (bytes.length != 20) {
                throw new IllegalArgumentException("RIPEMD-160 输出必须是 20 字节");
            }
            BigInteger id = new BigInteger(1, bytes);
            //生成节点ID
            nodeSetting.setId(id);
        }
        storageService.addOrUpdateNodeSetting(nodeSetting);
        log.info("节点信息:{}", nodeSetting);
        NodeInfo nodeInfo = NodeInfo.builder()
                .id(nodeSetting.getId())
                .ipv4(nodeSetting.getIpv4())
                .udpPort(udpPort)
                .tcpPort(tcpPort)
                .build();
        KademliaNodeServer server = new KademliaNodeServer();
        server.setNodeInfo(nodeInfo);
        ExternalNodeInfo externalNodeInfo = storageService.getNodeSelfNode();
        if (externalNodeInfo == null){
            externalNodeInfo = new ExternalNodeInfo();
            externalNodeInfo.setScore(60);//分数是由接受来定义的
        }
        externalNodeInfo.setId(nodeSetting.getId());
        externalNodeInfo.setIpv4(nodeSetting.getIpv4());
        externalNodeInfo.setTcpPort(nodeSetting.getTcpPort());
        externalNodeInfo.setUdpPort(nodeSetting.getUdpPort());
        externalNodeInfo.setNodeType(nodeSetting.getNodeType());
        externalNodeInfo.setPublicKey(CryptoUtil.hexToBytes(nodeSetting.getPublicKeyHex()));
        server.setExternalNodeInfo(externalNodeInfo);


        storageService.addOrUpdateSelfNode(externalNodeInfo);
        //自己要用单独的KEY保存不再放在路由表中
        server.setNodeSettings(NodeSettings.Default.build());
        server.init();
        return server;
    }




    @Bean
    public CommandLineRunner registerRpcService(RpcServiceRegistry registry) {
        return args -> {
            try {
                log.info("正在启动网络......");
                kademliaNodeServer.setRpcServiceRegistry(registry);
                kademliaNodeServer.start();  // 启动服务器
                try {
                    log.debug("是否连接引导节点:{}",isBootstrap);
                    //连接到引导节点 确保入网
                    if (isBootstrap==1){
                        log.info("正在连接引导节点......:{}",bootstrap);
                        if (bootstrap != null && !bootstrap.isEmpty()) {
                            for (BootstrapNode bootstrapNode : bootstrap) {
                                NodeInfo nodeInfo = NodeInfo.builder()
                                        .id(bootstrapNode.getNodeId())
                                        .ipv4(bootstrapNode.getIp())
                                        .tcpPort(bootstrapNode.getTcpPort())
                                        .udpPort(bootstrapNode.getUdpPort())
                                        .build();
                                kademliaNodeServer.connectToNode(nodeInfo);
                            }
                        } else {
                            log.warn("未配置任何引导节点");
                        }
                    }
                    //连接到历史节点
                    kademliaNodeServer.sendOnlineStatus();
                }catch (Exception e){
                    log.error("引导节点连接失败:{}", e.getMessage());
                }
            } catch (Exception e) {
                log.error("节点启动失败", e);  // 使用log代替e.printStackTrace()
            }
        };
    }







}
