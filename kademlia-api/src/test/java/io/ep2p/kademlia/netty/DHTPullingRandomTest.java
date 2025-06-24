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


public class DHTPullingRandomTest {


    private static KeyHashGenerator<BigInteger, String> keyHashGenerator;

    private static List<NettyKademliaDHTNode<String, String>> nodes = new ArrayList<>();

    /**
     * ��ʼ�����Ի���
     */
    @SneakyThrows
    @BeforeAll
    public static void init() {
        NodeSettings.Default.IDENTIFIER_SIZE = 128;  // ��ʶ����СΪ128λ
        NodeSettings.Default.BUCKET_SIZE = 10;       // KͰ��СΪ10
        NodeSettings.Default.PING_SCHEDULE_TIME_VALUE = 4;  // PING����ʱ��Ϊ4
        NodeSettings.Default.PING_SCHEDULE_TIME_UNIT = TimeUnit.SECONDS;  // PING����ʱ�䵥λΪ��
        keyHashGenerator = (k) -> BigInteger.valueOf(1);
    }

    /**
     * ����DHT�洢�����ֵ��
     */
    @Test
    void testDHTStoreRandomKeys() throws IOException, ExecutionException, InterruptedException, TimeoutException {

        // ǰһ���ڵ����ã����������½ڵ��������
        NettyKademliaDHTNode<String, String> previousNode = null;

        // ����������7���ڵ�
        for (int i = 1; i < 8; i++){
            // ����һ���µ�Kademlia DHT�ڵ�
            NettyKademliaDHTNode<String, String> nettyKademliaDHTNode = new NettyKademliaDHTNodeBuilder<>(
                    BigInteger.valueOf(i),  // �ڵ�ID
                    new NettyConnectionInfo("127.0.0.1", NodeHelper.findRandomPort()),  // ������Ϣ
                    new SampleRepository(),  // �����洢��
                    keyHashGenerator,  // ����ϣ������
                    String.class, String.class)  // ��ֵ����
                    // ����DHT�洢���񹤳���ʹ��PullingDHTStoreService����ȡʽ�洢����
                    .dhtStoreServiceFactory(new DHTStoreServiceFactory<BigInteger, NettyConnectionInfo, String, String>() {
                        @Override
                        public PushingDHTStoreService<BigInteger, NettyConnectionInfo, String, String> getDhtStoreService(DHTKademliaNodeAPI<BigInteger, NettyConnectionInfo, String, String> kademliaNodeAPI) {
                            return new PullingDHTStoreService<>(kademliaNodeAPI, Executors.newFixedThreadPool(8));
                        }
                    })
                    .build();

            // �����ڵ㣺��һ���ڵ�ֱ�������������ڵ�ͨ��ǰһ���ڵ���������
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

        // �ȴ������ȶ�
        Thread.sleep(5000);
        long beginTime = System.currentTimeMillis();

        // ÿ���ڵ�洢�Լ�ID��Ӧ������
        nodes.forEach(kademliaDHTNode -> {
            try {
                Assertions.assertEquals(StoreAnswer.Result.STORED, kademliaDHTNode.store(kademliaDHTNode.getId().toString(), "data").get(5, TimeUnit.SECONDS).getResult());
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                e.printStackTrace();
            }
        });

        // ÿ���ڵ�����������нڵ�洢������
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

        // ֹͣ���нڵ�
        nodes.forEach(NettyKademliaDHTNode::stopNow);
    }
}