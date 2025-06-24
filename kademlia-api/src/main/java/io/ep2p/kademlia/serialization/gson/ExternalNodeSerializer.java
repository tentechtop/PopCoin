package io.ep2p.kademlia.serialization.gson;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.google.gson.reflect.TypeToken;
import io.ep2p.kademlia.connection.ConnectionInfo;
import io.ep2p.kademlia.node.external.ExternalNode;

import java.lang.reflect.Type;
import java.math.BigInteger;
import java.util.Date;
import com.google.gson.*;
public class ExternalNodeSerializer<ID extends Number, C extends ConnectionInfo> implements JsonSerializer<ExternalNode<ID, C>> {
    @Override
    public JsonElement serialize(ExternalNode<ID, C> src, Type type, JsonSerializationContext context) {
        JsonObject jsonNode = new JsonObject();
        jsonNode.add("id", context.serialize(src.getId(), new TypeToken<ID>() {}.getType()));
        jsonNode.add("lastSeen", context.serialize(src.getLastSeen(), Date.class));
        jsonNode.add("connectionInfo", context.serialize(src.getConnectionInfo(), new TypeToken<C>() {}.getType()));
        jsonNode.add("distance", context.serialize(src.getDistance(), BigInteger.class));
        return jsonNode;
    }
}
