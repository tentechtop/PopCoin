package com.pop.popcoinsystem.event.transaction;

import com.lmax.disruptor.EventFactory;

// 事件工厂，用于Disruptor初始化事件对象
public class TransactionEventFactory implements EventFactory<TransactionEvent> {
    @Override
    public TransactionEvent newInstance() {
        return new TransactionEvent();
    }
}