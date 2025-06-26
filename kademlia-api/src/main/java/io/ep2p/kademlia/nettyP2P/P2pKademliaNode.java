package io.ep2p.kademlia.nettyP2P;


import io.ep2p.kademlia.node.KademliaNodeAPI;
import io.ep2p.kademlia.node.KademliaNodeAPIDecorator;
import io.ep2p.kademlia.protocol.MessageType;
import io.ep2p.kademlia.protocol.handler.*;
import lombok.Getter;

import java.math.BigInteger;

public class P2pKademliaNode extends KademliaNodeAPIDecorator<BigInteger, P2pConnectionInfo> {


    @Getter
    private final transient P2pKademliaNodeServer p2pKademliaNodeServer;


    protected P2pKademliaNode(KademliaNodeAPI<BigInteger, P2pConnectionInfo> kademliaNode,P2pKademliaNodeServer p2pKademliaNodeServer) {
        super(kademliaNode);
        this.p2pKademliaNodeServer = p2pKademliaNodeServer;
    }




}
