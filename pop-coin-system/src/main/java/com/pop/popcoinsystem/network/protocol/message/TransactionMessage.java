package com.pop.popcoinsystem.network.protocol.message;

import com.pop.popcoinsystem.data.transaction.Transaction;
import com.pop.popcoinsystem.network.protocol.MessageType;
import com.pop.popcoinsystem.util.SerializeUtils;
import lombok.Builder;
import lombok.ToString;

@Builder
@ToString(callSuper = true)
public class TransactionMessage extends KademliaMessage<Transaction>{

    public TransactionMessage(Transaction transaction) {
        super(MessageType.TRANSACTION.getCode());
        setData(transaction);
    }

    public static byte[] serialize(TransactionMessage message) {
        return SerializeUtils.serialize(message);
    }

    public static KademliaMessage deSerialize(byte[] bytes) {
        return (TransactionMessage)SerializeUtils.deSerialize(bytes);
    }
}
