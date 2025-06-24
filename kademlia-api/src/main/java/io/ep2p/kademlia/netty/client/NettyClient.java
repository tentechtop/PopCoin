package io.ep2p.kademlia.netty.client;

import io.ep2p.kademlia.connection.MessageSender;
import io.ep2p.kademlia.netty.common.NettyConnectionInfo;
import io.ep2p.kademlia.node.KademliaNodeAPI;
import io.ep2p.kademlia.node.Node;
import io.ep2p.kademlia.protocol.message.KademliaMessage;

import java.io.Serializable;
import java.math.BigInteger;

public class NettyClient<K extends Serializable, V extends Serializable> implements MessageSender<BigInteger, NettyConnectionInfo> {
    @Override
    public <U extends Serializable, O extends Serializable> KademliaMessage<BigInteger, NettyConnectionInfo, O> sendMessage(KademliaNodeAPI<BigInteger, NettyConnectionInfo> caller, Node<BigInteger, NettyConnectionInfo> receiver, KademliaMessage<BigInteger, NettyConnectionInfo, U> message) {
        return null;
    }

    @Override
    public <U extends Serializable> void sendAsyncMessage(KademliaNodeAPI<BigInteger, NettyConnectionInfo> caller, Node<BigInteger, NettyConnectionInfo> receiver, KademliaMessage<BigInteger, NettyConnectionInfo, U> message) {

    }




}
