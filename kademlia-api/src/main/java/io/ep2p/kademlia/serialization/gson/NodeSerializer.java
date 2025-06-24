package io.ep2p.kademlia.serialization.gson;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.google.gson.reflect.TypeToken;
import io.ep2p.kademlia.connection.ConnectionInfo;
import io.ep2p.kademlia.node.Node;

import java.lang.reflect.Type;
import com.google.gson.*;
public class NodeSerializer<ID extends Number, C extends ConnectionInfo> implements JsonSerializer<Node<ID, C>> {
    @Override
    public JsonElement serialize(Node<ID, C> src, Type type, JsonSerializationContext context) {
        JsonObject jsonNode = new JsonObject();
        jsonNode.addProperty("id", src.getId());
        jsonNode.add("connectionInfo", context.serialize(src.getConnectionInfo(), new TypeToken<C>(){}.getType()));
        return jsonNode;
    }
}
