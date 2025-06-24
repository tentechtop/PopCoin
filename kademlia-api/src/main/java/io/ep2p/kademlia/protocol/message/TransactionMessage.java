package io.ep2p.kademlia.protocol.message;

import io.ep2p.kademlia.connection.ConnectionInfo;
import io.ep2p.kademlia.protocol.MessageType;
import lombok.ToString;

import java.io.Serializable;
import java.math.BigInteger;


@ToString(callSuper = true)
public class TransactionMessage<I extends Number, C extends ConnectionInfo, D extends Serializable> extends KademliaMessage<I, C, Transaction> {
    public TransactionMessage(Transaction data) {
        this();
        setData(data);
    }
    public TransactionMessage() {
        super(MessageType.TRANSACTION);
    }


}
