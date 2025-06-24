package io.ep2p.kademlia.serialization.gson;

import com.google.gson.InstanceCreator;
import io.ep2p.kademlia.connection.ConnectionInfo;
import io.ep2p.kademlia.node.Node;

import java.lang.reflect.Type;
import com.google.gson.*;

public class NodeInstanceCreator<ID extends Number, C extends ConnectionInfo> implements InstanceCreator<Node<ID, C>> {
    @Override
    public Node<ID, C> createInstance(Type type) {
        return new GenericExternalNode<ID, C>();
    }
}
