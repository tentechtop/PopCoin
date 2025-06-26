package example;

import io.ep2p.kademlia.NodeSettings;
import io.ep2p.kademlia.exception.FullBucketException;
import io.ep2p.kademlia.helpers.EmptyConnectionInfo;
import io.ep2p.kademlia.helpers.TestMessageSenderAPI;
import io.ep2p.kademlia.node.KademliaNode;
import io.ep2p.kademlia.node.KademliaNodeAPI;
import io.ep2p.kademlia.table.DefaultRoutingTableFactory;
import io.ep2p.kademlia.table.IntegerBucket;
import io.ep2p.kademlia.table.RoutingTable;
import io.ep2p.kademlia.table.RoutingTableFactory;


public class FilledRoutingTable {
    public static void main(String[] args) throws FullBucketException {
        NodeSettings.Default.IDENTIFIER_SIZE = 3;
        RoutingTableFactory<Integer, EmptyConnectionInfo, IntegerBucket<EmptyConnectionInfo>> routingTableFactory = new DefaultRoutingTableFactory<>();
        TestMessageSenderAPI<Integer, EmptyConnectionInfo> messageSenderAPI = new TestMessageSenderAPI<>();
        int nodeId = 4;

        RoutingTable<Integer, EmptyConnectionInfo, IntegerBucket<EmptyConnectionInfo>> routingTable = routingTableFactory.getRoutingTable(nodeId);

        for (int i = 0; i < Math.pow(NodeSettings.Default.IDENTIFIER_SIZE, 2) - 1; i++){
            if (i == nodeId)
                continue;
            KademliaNodeAPI<Integer, EmptyConnectionInfo> node = new KademliaNode(
                    i, new EmptyConnectionInfo(), routingTableFactory.getRoutingTable(i), messageSenderAPI, NodeSettings.Default.build()
            );
            routingTable.update(node);
        }

        routingTable.getBuckets().forEach(bucket -> {
            System.out.println("Bucket [" + bucket.getId() + "] -> " + bucket.getNodeIds());
        });

    }
}
