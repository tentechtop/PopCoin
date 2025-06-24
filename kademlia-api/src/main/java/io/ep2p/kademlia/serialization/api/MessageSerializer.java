package io.ep2p.kademlia.serialization.api;

import io.ep2p.kademlia.connection.ConnectionInfo;
import io.ep2p.kademlia.protocol.message.KademliaMessage;

import java.io.Serializable;

/**
 * @param <ID> Type of Node ID
 * @param <C> Type of ConnectionInfo
 */
public interface MessageSerializer<ID extends Number, C extends ConnectionInfo> {
    /**
     * @param message Kademlia Message to convert to String
     * @param <S> Kademlia message serializable type
     * @return message as String
     */
    <S extends Serializable> String serialize(KademliaMessage<ID, C, S> message);

    /**
     * @param message String of kademlia message
     * @param <S> Kademlia message serializable type
     * @return KademliaMessage object
     */
    <S extends Serializable> KademliaMessage<ID, C, S> deserialize(String message);
}
