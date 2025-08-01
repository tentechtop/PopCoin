package com.pop.popcoinsystem.event.transaction;

import com.lmax.disruptor.EventFactory;
import com.pop.popcoinsystem.data.transaction.Transaction;

// 交易事件类，用于封装需要处理的交易数据
public class TransactionEvent {
    private Transaction transaction;

    public Transaction getTransaction() {
        return transaction;
    }

    public void setTransaction(Transaction transaction) {
        this.transaction = transaction;
    }
}

