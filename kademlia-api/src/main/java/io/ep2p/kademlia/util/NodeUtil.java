package io.ep2p.kademlia.util;

import io.ep2p.kademlia.connection.ConnectionInfo;
import io.ep2p.kademlia.exception.HandlerNotFoundException;
import io.ep2p.kademlia.node.KademliaNodeAPI;
import io.ep2p.kademlia.node.external.ExternalNode;
import io.ep2p.kademlia.protocol.message.KademliaMessage;
import io.ep2p.kademlia.protocol.message.PingKademliaMessage;

import java.util.Date;


public class NodeUtil {

    /**
     * Check if a node is seen recently or is alive
     * @param kademliaNodeAPI Node to send message from
     * @param externalNode Node to check
     * @param date Date to use for comparing
     * @param <I> ID type of the node
     * @param <C> ConnectionInfo type of the node
     * @return boolean True if node is alive
     */
    public static <I extends Number, C extends ConnectionInfo> boolean recentlySeenOrAlive(KademliaNodeAPI<I, C> kademliaNodeAPI, ExternalNode<I, C> externalNode, Date date) {
        if (externalNode.getLastSeen().after(date))
            return true;
        KademliaMessage<I, C, ?> pingAnswer = kademliaNodeAPI.getMessageSender().sendMessage(kademliaNodeAPI, externalNode, new PingKademliaMessage<>());
        if (!pingAnswer.isAlive()){
            try {
                kademliaNodeAPI.onMessage(pingAnswer);
            } catch (HandlerNotFoundException e) {
                System.out.println("HandlerNotFoundException:"+e.getMessage());
            }
        }
        return pingAnswer.isAlive();
    }
}
