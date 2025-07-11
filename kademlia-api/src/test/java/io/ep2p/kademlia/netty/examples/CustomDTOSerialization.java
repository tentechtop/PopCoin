package io.ep2p.kademlia.netty.examples;

import io.ep2p.kademlia.NodeSettings;
import io.ep2p.kademlia.exception.UnsupportedBoundingException;
import io.ep2p.kademlia.model.LookupAnswer;
import io.ep2p.kademlia.model.StoreAnswer;
import io.ep2p.kademlia.netty.NettyKademliaDHTNode;
import io.ep2p.kademlia.netty.builder.NettyKademliaDHTNodeBuilder;
import io.ep2p.kademlia.netty.common.NettyConnectionInfo;
import io.ep2p.kademlia.node.KeyHashGenerator;
import io.ep2p.kademlia.util.BoundedHashUtil;

import java.math.BigInteger;
import java.util.concurrent.ExecutionException;

public class CustomDTOSerialization {

    public static void main(String[] args) throws  ExecutionException, InterruptedException {
        NodeSettings.Default.IDENTIFIER_SIZE = 4;
        NodeSettings.Default.BUCKET_SIZE = 100;
        NodeSettings.Default.PING_SCHEDULE_TIME_VALUE = 5;

        KeyHashGenerator<BigInteger, String> keyHashGenerator = key -> {
            try {
                return new BoundedHashUtil(NodeSettings.Default.IDENTIFIER_SIZE).hash(key.hashCode(), BigInteger.class);
            } catch (UnsupportedBoundingException e) {
                e.printStackTrace();
            }
            return BigInteger.valueOf(key.hashCode());
        };

        NettyKademliaDHTNode<String, PersonDTO> node1 = new NettyKademliaDHTNodeBuilder<>(
                BigInteger.valueOf(1L),
                new NettyConnectionInfo("127.0.0.1", 8000),
                new PersonRepository(),
                keyHashGenerator,
                String.class, PersonDTO.class).build();
        node1.start();

        // node 2
        NettyKademliaDHTNode<String, PersonDTO> node2 = new NettyKademliaDHTNodeBuilder<>(
                BigInteger.valueOf(2L),
                new NettyConnectionInfo("127.0.0.1", 8001),
                new PersonRepository(),
                keyHashGenerator,
                String.class, PersonDTO.class).build();
        node2.start(node1).get();


        StoreAnswer<BigInteger, NettyConnectionInfo, String> storeAnswer = node2.store("K", new PersonDTO("John", "Smith")).get();
        System.out.printf("Store result: %s - Node: %s%n", storeAnswer.getResult(), storeAnswer.getNode().getId());
        System.out.printf("Data in node 2: %s%n", node2.getKademliaRepository().get("K"));

        LookupAnswer<BigInteger, NettyConnectionInfo, String, PersonDTO> lookupAnswer = node1.lookup("K").get();
        System.out.printf("Lookup result: %s - Value: %s%n", lookupAnswer.getResult(), lookupAnswer.getValue());


        node1.stopNow();
        node2.stopNow();

        System.exit(0);
    }

}
