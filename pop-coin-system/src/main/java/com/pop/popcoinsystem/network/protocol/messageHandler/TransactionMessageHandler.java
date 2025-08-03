package com.pop.popcoinsystem.network.protocol.messageHandler;

import com.pop.popcoinsystem.data.transaction.Transaction;
import com.pop.popcoinsystem.network.service.KademliaNodeServer;
import com.pop.popcoinsystem.network.protocol.message.KademliaMessage;
import com.pop.popcoinsystem.network.protocol.message.TransactionMessage;
import com.pop.popcoinsystem.util.ByteUtils;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;

import java.io.Serializable;

@Slf4j
@Service
public class TransactionMessageHandler implements MessageHandler {

    @Override
    public KademliaMessage<? extends Serializable> handleMesage(KademliaNodeServer kademliaNodeServer, KademliaMessage<?> message) throws InterruptedException {
        return doHandle(kademliaNodeServer, (TransactionMessage) message);
    }


    protected TransactionMessage doHandle(KademliaNodeServer kademliaNodeServer, @NotNull TransactionMessage message) throws InterruptedException {
        Transaction data = message.getData();
        log.info("收到交易消息{}",data);
        byte[] txId = data.getTxId();
        long txMessageId = ByteUtils.bytesToLong(txId);

        if (kademliaNodeServer.getBroadcastMessages().getIfPresent(txMessageId) != null) {
            log.info("接收已处理的交易消息 {}，丢弃", txMessageId);
        }else {
            // 记录：标记为已处理
            kademliaNodeServer.getBroadcastMessages().put(txMessageId, Boolean.TRUE);
            kademliaNodeServer.getBlockChainService().verifyAndAddTradingPool(data,false);
            message.setSender(kademliaNodeServer.getNodeInfo());
            kademliaNodeServer.broadcastMessage(message);
        }
        return null;
    }

}
