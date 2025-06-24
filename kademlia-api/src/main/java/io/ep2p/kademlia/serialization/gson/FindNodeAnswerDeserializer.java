package io.ep2p.kademlia.serialization.gson;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import io.ep2p.kademlia.connection.ConnectionInfo;
import io.ep2p.kademlia.model.FindNodeAnswer;
import io.ep2p.kademlia.node.external.ExternalNode;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.lang.reflect.Type;
import java.util.List;
import com.google.gson.*;
@AllArgsConstructor
@Getter
public class FindNodeAnswerDeserializer<ID extends Number, C extends ConnectionInfo> implements JsonDeserializer<FindNodeAnswer<ID, C>> {
    private final Class<ID> idClass;

    @Override
    public FindNodeAnswer<ID, C> deserialize(JsonElement jsonElement, Type type, JsonDeserializationContext jsonDeserializationContext) throws JsonParseException {
        FindNodeAnswer<ID, C> findNodeAnswer = new FindNodeAnswer<>();
        findNodeAnswer.setDestinationId(jsonDeserializationContext.deserialize(jsonElement.getAsJsonObject().get("destination_id"), getIdClass()));
        findNodeAnswer.setNodes(jsonDeserializationContext.deserialize(
                jsonElement.getAsJsonObject().get("nodes").getAsJsonArray(),
                new TypeToken<List<ExternalNode<ID, C>>>(){}.getType()
        ));
        return findNodeAnswer;
    }
}
