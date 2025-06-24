package io.ep2p.kademlia.serialization.gson;

import com.google.gson.reflect.TypeToken;
import io.ep2p.kademlia.connection.ConnectionInfo;
import io.ep2p.kademlia.model.FindNodeAnswer;
import io.ep2p.kademlia.node.Node;
import io.ep2p.kademlia.protocol.MessageType;
import io.ep2p.kademlia.protocol.message.*;
import lombok.SneakyThrows;

import java.io.Serializable;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import com.google.gson.*;

public class KademliaMessageDeserializer<ID extends Number, C extends ConnectionInfo, K extends Serializable, V extends Serializable> implements JsonDeserializer<KademliaMessage<ID, C, Serializable>> {
    private final Map<String, Type> typeRegistry = new ConcurrentHashMap<>();
    private final Map<String, Class<?>> messageClassRegistry = new ConcurrentHashMap<>();

    public KademliaMessageDeserializer(Class<ID> idClass) {
        this.registerMessageClass(MessageType.DHT_LOOKUP, DHTLookupKademliaMessage.class);
        this.registerMessageClass(MessageType.DHT_LOOKUP_RESULT, DHTLookupResultKademliaMessage.class);
        this.registerMessageClass(MessageType.DHT_STORE, DHTStoreKademliaMessage.class);
        this.registerMessageClass(MessageType.DHT_STORE_RESULT, DHTStoreResultKademliaMessage.class);
        this.registerMessageClass(MessageType.DHT_STORE_PULL, DHTStorePullKademliaMessage.class);
        this.registerMessageClass(MessageType.FIND_NODE_REQ, FindNodeRequestMessage.class);
        this.registerMessageClass(MessageType.FIND_NODE_RES, FindNodeResponseMessage.class);
        this.registerMessageClass(MessageType.PING, PingKademliaMessage.class);
        this.registerMessageClass(MessageType.PONG, PongKademliaMessage.class);
        this.registerMessageClass(MessageType.SHUTDOWN, ShutdownKademliaMessage.class);
        this.registerMessageClass(MessageType.EMPTY, EmptyKademliaMessage.class);

        this.registerDataType(MessageType.DHT_LOOKUP, new TypeToken<DHTLookupKademliaMessage.DHTLookup<ID, C, K>>(){}.getType());
        this.registerDataType(MessageType.DHT_LOOKUP_RESULT, new TypeToken<DHTLookupResultKademliaMessage.DHTLookupResult<K, V>>(){}.getType());
        this.registerDataType(MessageType.DHT_STORE, new TypeToken<DHTStoreKademliaMessage.DHTData<ID, C, K, V>>(){}.getType());
        this.registerDataType(MessageType.DHT_STORE_RESULT, new TypeToken<DHTStoreResultKademliaMessage.DHTStoreResult<K>>(){}.getType());
        this.registerDataType(MessageType.DHT_STORE_PULL, new TypeToken<DHTStorePullKademliaMessage.DHTStorePullData<ID>>(){}.getType());
        this.registerDataType(MessageType.FIND_NODE_REQ, idClass);
        this.registerDataType(MessageType.FIND_NODE_RES, new TypeToken<FindNodeAnswer<ID, C>>(){}.getType());
        this.registerDataType(MessageType.PING, new TypeToken<String>(){}.getType());
        this.registerDataType(MessageType.PONG, new TypeToken<String>(){}.getType());
        this.registerDataType(MessageType.SHUTDOWN, new TypeToken<String>(){}.getType());
        this.registerDataType(MessageType.EMPTY, new TypeToken<String>(){}.getType());
    }

    @SneakyThrows
    @Override
    public KademliaMessage<ID, C, Serializable> deserialize(JsonElement jsonElement, Type type, JsonDeserializationContext jsonDeserializationContext) throws JsonParseException {
        JsonObject jsonObject = jsonElement.getAsJsonObject();
        String type_ = jsonObject.getAsJsonPrimitive("type").getAsString();
        Node<ID, C> node = jsonDeserializationContext.deserialize(
                jsonObject.getAsJsonObject("node"),
                Node.class
        );
        Class<?> aClass = this.messageClassRegistry.get(type_);
        KademliaMessage<ID, C, Serializable> o = (KademliaMessage<ID, C, Serializable>) aClass.newInstance();
        o.setData(getMessageData(type_, jsonObject, jsonDeserializationContext));
        o.setType(type_);
        o.setNode(node);
        o.setAlive(true);
        return o;
    }

    protected <X extends Serializable> X getMessageData(
            String type,
            JsonObject jsonObject,
            JsonDeserializationContext jsonDeserializationContext
    ){
        if (type.equals(MessageType.EMPTY))
            return null;
        Type dataType = typeRegistry.get(type);
        if (dataType != null){
            return jsonDeserializationContext.deserialize(
                    jsonObject.get("data"),
                    dataType
            );
        }
        return null;
    }

    public void registerDataType(String name, Type type){
        this.typeRegistry.put(name, type);
    }

    public void registerMessageClass(String name, Class<?> clazz){
        this.messageClassRegistry.put(name, clazz);
    }
}
