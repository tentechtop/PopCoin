package io.ep2p.kademlia.serialization.gson;

import io.ep2p.kademlia.model.StoreAnswer;
import io.ep2p.kademlia.protocol.message.DHTStoreResultKademliaMessage;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.io.Serializable;
import java.lang.reflect.Type;
import com.google.gson.*;
@AllArgsConstructor
@Getter
public class DHTStoreResultDataDataDeserializer<K extends Serializable> implements JsonDeserializer<DHTStoreResultKademliaMessage.DHTStoreResult<K>> {

    private final Class<K> keyClass;

    @Override
    public DHTStoreResultKademliaMessage.DHTStoreResult<K> deserialize(JsonElement jsonElement, Type type, JsonDeserializationContext jsonDeserializationContext) throws JsonParseException {
        DHTStoreResultKademliaMessage.DHTStoreResult<K> dhtStoreResult = new DHTStoreResultKademliaMessage.DHTStoreResult<>();
        JsonObject jsonObject = jsonElement.getAsJsonObject();
        dhtStoreResult.setKey(jsonDeserializationContext.deserialize(jsonObject.get("key"), getKeyClass()));
        dhtStoreResult.setResult(StoreAnswer.Result.valueOf(jsonObject.get("result").getAsString()));
        return dhtStoreResult;
    }
}
