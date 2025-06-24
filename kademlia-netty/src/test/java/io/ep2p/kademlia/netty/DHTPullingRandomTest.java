package io.ep2p.kademlia.netty;

import io.ep2p.kademlia.NodeSettings;
import io.ep2p.kademlia.model.LookupAnswer;
import io.ep2p.kademlia.model.StoreAnswer;
import io.ep2p.kademlia.netty.builder.NettyKademliaDHTNodeBuilder;
import io.ep2p.kademlia.netty.common.NettyConnectionInfo;
import io.ep2p.kademlia.node.DHTKademliaNodeAPI;
import io.ep2p.kademlia.node.KeyHashGenerator;
import io.ep2p.kademlia.services.DHTStoreServiceFactory;
import io.ep2p.kademlia.services.PullingDHTStoreService;
import io.ep2p.kademlia.services.PushingDHTStoreService;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * DHT拉取随机测试类 - 测试基于Kademlia协议的分布式哈希表的拉取功能
 */
public class DHTPullingRandomTest {

    // 键哈希生成器，用于将键转换为哈希值
    private static KeyHashGenerator<BigInteger, String> keyHashGenerator;
    // 存储测试中使用的所有节点
    private static List<NettyKademliaDHTNode<String, String>> nodes = new ArrayList<>();

    /**
     * 初始化测试环境
     */
    @SneakyThrows
    @BeforeAll
    public static void init() {
        // 设置节点默认参数
        NodeSettings.Default.IDENTIFIER_SIZE = 128;  // 标识符大小为128位
        NodeSettings.Default.BUCKET_SIZE = 10;       // K桶大小为10
        NodeSettings.Default.PING_SCHEDULE_TIME_VALUE = 4;  // PING调度时间为4
        NodeSettings.Default.PING_SCHEDULE_TIME_UNIT = TimeUnit.SECONDS;  // PING调度时间单位为秒

        // 创建一个简单的哈希生成器，所有键都生成相同的哈希值1（仅用于测试）
        keyHashGenerator = (k) -> BigInteger.valueOf(1);
    }

    /**
     * 测试DHT存储随机键值对
     */
    @Test
    void testDHTStoreRandomKeys() throws IOException, ExecutionException, InterruptedException, TimeoutException {

        // 前一个节点引用，用于引导新节点加入网络
        NettyKademliaDHTNode<String, String> previousNode = null;

        // 创建并启动7个节点
        for (int i = 1; i < 8; i++){
            // 构建一个新的Kademlia DHT节点
            NettyKademliaDHTNode<String, String> nettyKademliaDHTNode = new NettyKademliaDHTNodeBuilder<>(
                    BigInteger.valueOf(i),  // 节点ID
                    new NettyConnectionInfo("127.0.0.1", NodeHelper.findRandomPort()),  // 连接信息
                    new SampleRepository(),  // 样本存储库
                    keyHashGenerator,  // 键哈希生成器
                    String.class, String.class)  // 键值类型
                    // 设置DHT存储服务工厂，使用PullingDHTStoreService（拉取式存储服务）
                    .dhtStoreServiceFactory(new DHTStoreServiceFactory<BigInteger, NettyConnectionInfo, String, String>() {
                        @Override
                        public PushingDHTStoreService<BigInteger, NettyConnectionInfo, String, String> getDhtStoreService(DHTKademliaNodeAPI<BigInteger, NettyConnectionInfo, String, String> kademliaNodeAPI) {
                            return new PullingDHTStoreService<>(kademliaNodeAPI, Executors.newFixedThreadPool(8));
                        }
                    })
                    .build();

            // 启动节点：第一个节点直接启动，后续节点通过前一个节点引导启动
            if (previousNode == null){
                nettyKademliaDHTNode.start();
            }else {
                Assertions.assertTrue(nettyKademliaDHTNode.start(previousNode).get(5, TimeUnit.SECONDS));
            }

            nodes.add(nettyKademliaDHTNode);
            System.out.println("Stored data in " + nettyKademliaDHTNode.getId());
            previousNode = nettyKademliaDHTNode;
        }

        System.out.println("Bootstrapped all nodes. Looking up for data");

        // 等待网络稳定
        Thread.sleep(5000);
        long beginTime = System.currentTimeMillis();

        // 每个节点存储自己ID对应的数据
        nodes.forEach(kademliaDHTNode -> {
            try {
                Assertions.assertEquals(StoreAnswer.Result.STORED, kademliaDHTNode.store(kademliaDHTNode.getId().toString(), "data").get(5, TimeUnit.SECONDS).getResult());
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                e.printStackTrace();
            }
        });

        // 每个节点查找其他所有节点存储的数据
        nodes.forEach(kademliaDHTNode -> {
            nodes.forEach(otherNode -> {
                try {
                    LookupAnswer<BigInteger, NettyConnectionInfo, String, String> lookupAnswer = kademliaDHTNode.lookup(otherNode.getId().toString()).get(10, TimeUnit.SECONDS);
                    Assertions.assertEquals(LookupAnswer.Result.FOUND, lookupAnswer.getResult(), kademliaDHTNode.getId() + " couldn't find key " + otherNode.getId());
                    System.out.println("Requester: " + kademliaDHTNode.getId() + " - Key: " + otherNode.getId() + " - Owner: " + lookupAnswer.getNode().getId());
                } catch (InterruptedException | ExecutionException | TimeoutException e) {
                    e.printStackTrace();
                }
            });
        });

        System.out.println("Execution time: " + (System.currentTimeMillis() - beginTime) + "ms");
        System.out.println("Test passed successfully. Shutting down.");
        Thread.sleep(1000);

        // 停止所有节点
        nodes.forEach(NettyKademliaDHTNode::stopNow);
    }
}