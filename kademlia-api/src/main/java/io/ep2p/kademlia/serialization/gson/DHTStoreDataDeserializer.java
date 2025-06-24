package io.ep2p.kademlia.serialization.gson;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import io.ep2p.kademlia.connection.ConnectionInfo;
import io.ep2p.kademlia.node.Node;
import io.ep2p.kademlia.protocol.message.DHTStoreKademliaMessage;
import lombok.AllArgsConstructor;
import lombok.Getter;
import com.google.gson.*;
import java.io.Serializable;
import java.lang.reflect.Type;
import com.google.gson.*;

@AllArgsConstructor
@Getter
public class DHTStoreDataDeserializer<ID extends Number, C extends ConnectionInfo, K extends Serializable, V extends Serializable> implements JsonDeserializer<DHTStoreKademliaMessage.DHTData<ID, C, K, V>> {

    private final Class<K> keyClass;
    private final Class<V> valueClass;

    @Override
    public DHTStoreKademliaMessage.DHTData<ID, C, K, V> deserialize(JsonElement jsonElement, Type type, JsonDeserializationContext jsonDeserializationContext) throws JsonParseException {
        DHTStoreKademliaMessage.DHTData<ID, C, K, V> dhtData = new DHTStoreKademliaMessage.DHTData<>();
        JsonObject jsonObject = jsonElement.getAsJsonObject();
        dhtData.setKey(jsonDeserializationContext.deserialize(jsonObject.get("key"), getKeyClass()));
        dhtData.setValue(jsonDeserializationContext.deserialize(jsonObject.get("value"), getValueClass()));
        dhtData.setRequester(jsonDeserializationContext.deserialize(jsonObject.getAsJsonObject("requester"), Node.class));
        return dhtData;
    }
}
