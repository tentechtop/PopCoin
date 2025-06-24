package io.ep2p.kademlia.serialization.gson;

import io.ep2p.kademlia.connection.ConnectionInfo;
import io.ep2p.kademlia.node.Node;
import io.ep2p.kademlia.protocol.message.DHTLookupKademliaMessage;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.io.Serializable;
import java.lang.reflect.Type;

import com.google.gson.*;
@AllArgsConstructor
@Getter
public class DHTLookUpDataDeserializer<ID extends Number, C extends ConnectionInfo, K extends Serializable> implements JsonDeserializer<DHTLookupKademliaMessage.DHTLookup<ID, C, K>> {

    private final Class<K> keyClass;

    @Override
    public DHTLookupKademliaMessage.DHTLookup<ID, C, K> deserialize(JsonElement jsonElement, Type type, JsonDeserializationContext jsonDeserializationContext) throws JsonParseException {
        DHTLookupKademliaMessage.DHTLookup<ID, C, K> dhtLookup = new DHTLookupKademliaMessage.DHTLookup<>();
        JsonObject jsonObject = jsonElement.getAsJsonObject();
        JsonObject requesterJsonObject = jsonObject.getAsJsonObject("requester");
        dhtLookup.setRequester(jsonDeserializationContext.deserialize(requesterJsonObject, Node.class));
        dhtLookup.setCurrentTry(jsonObject.get("current_try").getAsInt());
        dhtLookup.setKey(jsonDeserializationContext.deserialize(jsonObject.get("key"), getKeyClass()));
        return dhtLookup;
    }
}
