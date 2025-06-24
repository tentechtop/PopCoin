package io.ep2p.kademlia.protocol.handler;

import io.ep2p.kademlia.connection.ConnectionInfo;
import io.ep2p.kademlia.exception.FullBucketException;
import io.ep2p.kademlia.model.FindNodeAnswer;
import io.ep2p.kademlia.node.KademliaNodeAPI;
import io.ep2p.kademlia.node.Node;
import io.ep2p.kademlia.node.external.ExternalNode;
import io.ep2p.kademlia.protocol.message.KademliaMessage;
import io.ep2p.kademlia.protocol.message.PingKademliaMessage;
import io.ep2p.kademlia.protocol.message.Transaction;
import io.ep2p.kademlia.protocol.message.TransactionMessage;

import java.io.Serializable;
import java.util.List;

public class TransactionMessageHandler<I extends Number, C extends ConnectionInfo> extends GeneralResponseMessageHandler<I, C> {


    @Override
    @SuppressWarnings("unchecked")
    public <U extends KademliaMessage<I, C, ?>, O extends KademliaMessage<I, C, ?>> O doHandle(KademliaNodeAPI<I, C> kademliaNode, U message) {
        return (O) doHandle(kademliaNode, (TransactionMessage<I, C>) message);
    }

    protected TransactionMessage<I, C> doHandle(KademliaNodeAPI<I, C> kademliaNode, TransactionMessage<I, C> message){
        Transaction data = (Transaction)message.getData();
        System.out.println("交易消息要处理的是:"+ data.getDataValue());

        //判断交易是否被处理过  缓存

        //这个广播是否过期  是否被订阅过  未过期就广播给其他节点  是否被我广播过
        if (kademliaNode.isRunning()){
            try {
                kademliaNode.getRoutingTable().update(message.getNode());
            } catch (FullBucketException e) {
                System.out.println("PingMessageHandler ");
            }
        }
        TransactionMessage<I, C> transactionMessage = new TransactionMessage<>();
        transactionMessage.setAlive(kademliaNode.isRunning());

        // 获取路由表中最近的节点
        FindNodeAnswer<I, C> closest = kademliaNode.getRoutingTable().findClosest(kademliaNode.getId());
        List<ExternalNode<I, C>> nodes = closest.getNodes();
        nodes.removeIf(node -> node.getId().equals(kademliaNode.getId()));
        System.out.println("nodes:" + nodes.size());

/*        for (Node<I, C> node : nodes) {
            try {
                // 发送交易消息给其他节点
                kademliaNode.getMessageSender().sendMessage(kademliaNode, node, message);
            } catch (Exception e) {
                System.out.println("广播交易消息给节点 " + node.getId() + " 失败: " + e.getMessage());
            }
        }*/
        return transactionMessage;
    }
}
