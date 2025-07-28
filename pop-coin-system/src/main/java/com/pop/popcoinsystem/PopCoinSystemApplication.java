package com.pop.popcoinsystem;

import com.pop.popcoinsystem.data.storage.POPStorage;
import com.pop.popcoinsystem.network.KademliaNodeServer;
import com.pop.popcoinsystem.network.common.NodeSettings;
import com.pop.popcoinsystem.network.enums.NodeType;
import com.pop.popcoinsystem.util.BeanCopyUtils;
import com.pop.popcoinsystem.util.CryptoUtil;
import com.pop.popcoinsystem.util.NetworkUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;

@Slf4j
@SpringBootApplication
public class PopCoinSystemApplication {


    public static void main(String[] args) throws UnknownHostException {
        ConfigurableApplicationContext application = SpringApplication.run(PopCoinSystemApplication.class, args);
        Environment env = application.getEnvironment();
        String ip = InetAddress.getLocalHost().getHostAddress();
        String port = env.getProperty("server.port");

        log.info("\n----------------------------------------------------------\n\t" +
                "Application POPCoinSystem is running! Access URLs:\n\t" +
                "Local: \t\thttp://localhost:" + port + "/\n\t" +
                "Net: "  + "\t\thttp://" + ip + ":" + port + "/\n\t" +
                "----------------------------------------------------------");

        new Thread(() -> {
            try {
                String localIp = NetworkUtil.getLocalIp();// 获取本机IP
                log.info("本机IP:{}", localIp);
                //获取节点信息 先从数据库中获取 如果没有则创建一份
                POPStorage instance = POPStorage.getInstance();
                NodeSettings nodeSetting = instance.getNodeSetting();
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
                    //生成节点ID
                    nodeSetting.setId(bigInteger);
                    nodeSetting.setNodeType(NodeType.FULL.getValue());//默认是全节点
                }else {
                    if (nodeSetting.getPublicKeyHex().isEmpty() || nodeSetting.getPrivateKeyHex().isEmpty()){
                        nodeSetting.setPrivateKeyHex(CryptoUtil.bytesToHex(privateKey.getEncoded()));
                        nodeSetting.setPublicKeyHex(CryptoUtil.bytesToHex(publicKey.getEncoded()));
                    }
                }
                nodeSetting.setIpv4(localIp);
                if (nodeSetting.getId() == null){
                    byte[] bytes = CryptoUtil.applyRIPEMD160(CryptoUtil.applySHA256(CryptoUtil.hexToBytes(nodeSetting.getPublicKeyHex())));
                    if (bytes.length != 20) {
                        throw new IllegalArgumentException("RIPEMD-160 输出必须是 20 字节");
                    }
                    BigInteger bigInteger = new BigInteger(1, bytes);
                    //生成节点ID
                    nodeSetting.setId(bigInteger);
                }
                instance.addOrUpdateNodeSetting(nodeSetting);
                log.info("节点信息:{}", nodeSetting);
                KademliaNodeServer kademliaNodeServer = new KademliaNodeServer(nodeSetting.getId(), localIp, 8333, 8334);
                kademliaNodeServer.start();
                //加入到引导节点
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }





}
