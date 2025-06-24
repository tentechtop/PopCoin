package io.ep2p.kademlia.serialization.gson;

import io.ep2p.kademlia.connection.ConnectionInfo;
import io.ep2p.kademlia.node.Node;
import io.ep2p.kademlia.node.external.BigIntegerExternalNode;
import io.ep2p.kademlia.node.external.ExternalNode;
import io.ep2p.kademlia.node.external.IntegerExternalNode;
import io.ep2p.kademlia.node.external.LongExternalNode;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.lang.reflect.Type;
import java.math.BigInteger;
import com.google.gson.*;

@AllArgsConstructor
@Getter
public class ExternalNodeDeserializer<ID extends Number, C extends ConnectionInfo> implements JsonDeserializer<ExternalNode<ID, C>> {

    private final Class<ID> idClass;
    private final Class<C> connectionInfoClass;

    @Override
    public ExternalNode<ID, C> deserialize(JsonElement jsonElement, Type type, JsonDeserializationContext jsonDeserializationContext) throws JsonParseException {
        JsonObject jsonObject = jsonElement.getAsJsonObject();
        ID distance = jsonDeserializationContext.deserialize(jsonObject.get("distance"), getIdClass());
        jsonObject.remove("distance");
        Node<ID, C> node = null;
        if (jsonObject.has("node")){
            node = jsonDeserializationContext.deserialize(
                    jsonObject.get("node"),
                    Node.class
            );
        }else {
            node = jsonDeserializationContext.deserialize(
                    jsonObject,
                    Node.class
            );
        }
        if (getIdClass().equals(BigInteger.class))
            return new BigIntegerExternalNode(node, (BigInteger) distance);
        else if (getIdClass().equals(Integer.class))
            return new IntegerExternalNode(node, (Integer) distance);
        return new LongExternalNode(node, (Long) distance);
    }
}
