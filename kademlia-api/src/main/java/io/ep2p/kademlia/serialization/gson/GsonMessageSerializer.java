package io.ep2p.kademlia.serialization.gson;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import io.ep2p.kademlia.connection.ConnectionInfo;
import io.ep2p.kademlia.protocol.message.KademliaMessage;
import io.ep2p.kademlia.serialization.api.MessageSerializer;

import java.io.Serializable;
import com.google.gson.*;
public class GsonMessageSerializer<ID extends Number, C extends ConnectionInfo, K extends Serializable, V extends Serializable> implements MessageSerializer<ID, C> {
    private final Gson gson;

    public GsonMessageSerializer(Class<ID> idClass, Class<C> connectionInfoClass, Class<K> keyClass, Class<V> valueClass) {
        this(new GsonFactory.DefaultGsonFactory<ID, C, K, V>(idClass, connectionInfoClass, keyClass, valueClass).gsonBuilder());
    }

    public GsonMessageSerializer(GsonBuilder gsonBuilder) {
        this.gson = gsonBuilder.create();
    }

    @Override
    public <S extends Serializable> String serialize(KademliaMessage<ID, C, S> message) {
        return this.gson.toJson(message);
    }

    @Override
    public <S extends Serializable> KademliaMessage<ID, C, S> deserialize(String message) {
        return this.gson.fromJson(message, new TypeToken<KademliaMessage<ID, C, Serializable>>(){}.getType());
    }

}
