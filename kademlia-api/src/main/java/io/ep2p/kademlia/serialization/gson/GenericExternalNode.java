package io.ep2p.kademlia.serialization.gson;

import io.ep2p.kademlia.connection.ConnectionInfo;
import io.ep2p.kademlia.node.Node;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import com.google.gson.*;
@AllArgsConstructor
@NoArgsConstructor
@Data
@ToString
public class GenericExternalNode<ID extends Number, C extends ConnectionInfo> implements Node<ID, C> {
    private ID id;
    private C connectionInfo;
}
