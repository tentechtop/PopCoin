package io.ep2p.kademlia.serialization.gson;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import io.ep2p.kademlia.connection.ConnectionInfo;
import io.ep2p.kademlia.node.Node;
import io.ep2p.kademlia.protocol.message.Transaction;

import java.io.Serializable;
import java.lang.reflect.Type;

public class TransactionMessageDeserializer<ID extends Number, C extends ConnectionInfo,D extends Serializable>   {

}
