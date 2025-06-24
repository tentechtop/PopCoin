package io.ep2p.kademlia.serialization.gson;

import io.ep2p.kademlia.connection.ConnectionInfo;
import io.ep2p.kademlia.node.Node;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.lang.reflect.Type;
import com.google.gson.*;

@AllArgsConstructor
@Getter
public class NodeDeserializer<ID extends Number, C extends ConnectionInfo> implements JsonDeserializer<Node<ID, C>> {

    private final Class<ID> idClass;
    private final Class<C> connectionInfoClass;

    @Override
    public Node<ID, C> deserialize(JsonElement jsonElement, Type type, JsonDeserializationContext jsonDeserializationContext) throws JsonParseException {
        JsonObject jsonObject = jsonElement.getAsJsonObject();
        return new GenericExternalNode<ID, C>(
                jsonDeserializationContext.deserialize(jsonObject.get("id"), getIdClass()),
                jsonDeserializationContext.deserialize(jsonObject.get("connectionInfo"), getConnectionInfoClass())
        );
    }
}
