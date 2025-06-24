package io.ep2p.kademlia.serialization.gson;

import io.ep2p.kademlia.model.LookupAnswer;
import io.ep2p.kademlia.protocol.message.DHTLookupResultKademliaMessage;
import lombok.AllArgsConstructor;
import lombok.Getter;
import java.io.Serializable;
import java.lang.reflect.Type;
import com.google.gson.*;
@AllArgsConstructor
@Getter
public class DHTLookUpResultDeserializer<K extends Serializable, V extends Serializable> implements JsonDeserializer<DHTLookupResultKademliaMessage.DHTLookupResult<K, V>> {

    private final Class<K> keyClass;
    private final Class<V> valueClass;


    @Override
    public DHTLookupResultKademliaMessage.DHTLookupResult<K, V> deserialize(JsonElement jsonElement, Type type, JsonDeserializationContext jsonDeserializationContext) throws JsonParseException {
        DHTLookupResultKademliaMessage.DHTLookupResult<K, V> dhtLookupResult = new DHTLookupResultKademliaMessage.DHTLookupResult<>();
        JsonObject jsonObject = jsonElement.getAsJsonObject();
        dhtLookupResult.setKey(jsonDeserializationContext.deserialize(jsonObject.get("key"), getKeyClass()));
        dhtLookupResult.setValue(jsonDeserializationContext.deserialize(jsonObject.get("value"), getValueClass()));
        dhtLookupResult.setResult(LookupAnswer.Result.valueOf(jsonObject.get("result").getAsString()));
        return dhtLookupResult;
    }
}
