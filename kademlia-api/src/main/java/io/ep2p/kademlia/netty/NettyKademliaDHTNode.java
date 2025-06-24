package io.ep2p.kademlia.netty;

import io.ep2p.kademlia.model.FindNodeAnswer;
import io.ep2p.kademlia.netty.common.NettyConnectionInfo;
import io.ep2p.kademlia.protocol.message.Transaction;
import io.ep2p.kademlia.protocol.message.TransactionMessage;
import io.ep2p.kademlia.netty.server.KademliaNodeServer;
import io.ep2p.kademlia.node.DHTKademliaNodeAPI;
import io.ep2p.kademlia.node.DHTKademliaNodeAPIDecorator;
import io.ep2p.kademlia.node.Node;
import io.ep2p.kademlia.node.external.ExternalNode;
import io.ep2p.kademlia.protocol.message.TxOut;
import io.ep2p.kademlia.table.Bucket;
import io.ep2p.kademlia.table.RoutingTable;
import lombok.Getter;
import lombok.SneakyThrows;

import java.io.Serializable;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;

public class NettyKademliaDHTNode<K extends Serializable, V extends Serializable> extends DHTKademliaNodeAPIDecorator<BigInteger, NettyConnectionInfo, K, V> {

    @Getter
    private final transient KademliaNodeServer<K, V> kademliaNodeServer;

    public NettyKademliaDHTNode(DHTKademliaNodeAPI<BigInteger, NettyConnectionInfo, K, V> kademliaNode, KademliaNodeServer<K, V> kademliaNodeServer) {
        super(kademliaNode);
        this.kademliaNodeServer = kademliaNodeServer;
    }




    @Override
    @SneakyThrows
    public void start() {
        kademliaNodeServer.run(this);
        super.start();
    }

    @Override
    @SneakyThrows
    public Future<Boolean> start(Node<BigInteger, NettyConnectionInfo> bootstrapNode) {
        kademliaNodeServer.run(this);
        return super.start(bootstrapNode);
    }

    @Override
    @SneakyThrows
    public void stop(){
        super.stop();
        kademliaNodeServer.stop();
    }

    @Override
    @SneakyThrows
    public void stopNow(){
        super.stopNow();
        kademliaNodeServer.stopNow();
    }


    // 广播交易
    public void broadcastTransaction() {
        RoutingTable<BigInteger, NettyConnectionInfo, Bucket<BigInteger, NettyConnectionInfo>> routingTable = getRoutingTable();
        FindNodeAnswer<BigInteger, NettyConnectionInfo> closest = routingTable.findClosest(getId());
        List<ExternalNode<BigInteger, NettyConnectionInfo>> nodes = closest.getNodes();
        //去除自己
        nodes.removeIf(node -> node.getId().equals(this.getId()));
        System.out.println("nodes:" + nodes.size());
        for (ExternalNode<BigInteger, NettyConnectionInfo> node : nodes) {
            System.out.println( node+"节点信息"+node.getConnectionInfo());
            Transaction transaction1 = new Transaction();
            transaction1.setDataValue("这是一笔叫i有");

            ArrayList<TxOut> txOuts = new ArrayList<>();
            TxOut txOut = new TxOut();
            txOut.setAddress("0x1234567890");
            txOut.setAmount("1000");
            txOuts.add(txOut);
            transaction1.setOTXO(txOuts);
            TransactionMessage transactionMessage = new TransactionMessage(transaction1);
            this.getMessageSender().sendMessage(this, node, transactionMessage);
        }
    }













}
