package io.ep2p.kademlia.serialization.gson;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.ep2p.kademlia.connection.ConnectionInfo;
import io.ep2p.kademlia.model.FindNodeAnswer;
import io.ep2p.kademlia.node.Node;
import io.ep2p.kademlia.node.external.ExternalNode;
import io.ep2p.kademlia.protocol.message.*;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.io.Serializable;
import com.google.gson.*;
public interface GsonFactory {
    Gson gson();
    GsonBuilder gsonBuilder();

    @AllArgsConstructor
    @Getter
    class DefaultGsonFactory<ID extends Number, C extends ConnectionInfo, K extends Serializable, V extends Serializable> implements GsonFactory {

        private final Class<ID> idClass;
        private final Class<C> connectionInfoClass;
        private final Class<K> keyClass;
        private final Class<V> valueClass;

        @Override
        public GsonBuilder gsonBuilder(){
            GsonBuilder gsonBuilder = new GsonBuilder();


            return gsonBuilder
                    .enableComplexMapKeySerialization()
                    .serializeNulls()
                    .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)



                    .registerTypeAdapter(KademliaMessage.class, new KademliaMessageDeserializer<ID, C, K, V>(getIdClass()))
                    .registerTypeAdapter(DHTLookupKademliaMessage.DHTLookup.class, new DHTLookUpDataDeserializer<ID, C, K>(getKeyClass()))
                    .registerTypeAdapter(DHTLookupResultKademliaMessage.DHTLookupResult.class, new DHTLookUpResultDeserializer<K, V>(getKeyClass(), getValueClass()))
                    .registerTypeAdapter(DHTStoreKademliaMessage.DHTData.class, new DHTStoreDataDeserializer<ID, C, K, V>(getKeyClass(), getValueClass()))
                    .registerTypeAdapter(DHTStoreResultKademliaMessage.DHTStoreResult.class, new DHTStoreResultDataDataDeserializer<K>(getKeyClass()))
                    .registerTypeAdapter(ExternalNode.class, new ExternalNodeDeserializer<ID, C>(getIdClass(), getConnectionInfoClass()))
                    .registerTypeAdapter(FindNodeAnswer.class, new FindNodeAnswerDeserializer<ID, C>(getIdClass()))
//                    .registerTypeAdapter(Node.class, new NodeInstanceCreator())
                    .registerTypeAdapter(Node.class, new NodeSerializer<ID, C>())
                    .registerTypeAdapter(Node.class, new NodeDeserializer<ID, C>(getIdClass(), getConnectionInfoClass()))
                    .registerTypeAdapter(ExternalNode.class, new ExternalNodeSerializer<ID, C>())


                    ;
        }

        @Override
        public Gson gson() {
            return gsonBuilder().create();
        }
    }

}
