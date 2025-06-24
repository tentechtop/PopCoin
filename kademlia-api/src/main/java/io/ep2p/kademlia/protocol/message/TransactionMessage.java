package io.ep2p.kademlia.protocol.message;

import io.ep2p.kademlia.connection.ConnectionInfo;
import io.ep2p.kademlia.protocol.MessageType;
import lombok.ToString;

import java.io.Serializable;
import java.math.BigInteger;


@ToString(callSuper = true)
public class TransactionMessage<I extends Number, C extends ConnectionInfo> extends KademliaMessage<I, C, Serializable> {

    //过期时间
    private BigInteger expireTime;
    //hash
    private String hash;

    public TransactionMessage(Transaction transaction) {
        this();
        setData(transaction);
    }

    public TransactionMessage() {
        super(MessageType.TRANSACTION);
    }


}
