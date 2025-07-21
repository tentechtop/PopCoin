package com.pop.popcoinsystem.application.service;


import com.pop.popcoinsystem.data.transaction.UTXO;
import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

@Data
public class UTXOBucket {

    private  BigDecimal minAmount;  // 桶的最小金额（包含）

    private  BigDecimal maxAmount;  // 桶的最大金额（不包含）

    private volatile BigDecimal totalAmount;  // 桶内所有 UTXO 的总金额

    private final List<UTXO> utxos;  // 桶内的 UTXO 列表

    private final ReentrantLock bucketLock = new ReentrantLock();  // 桶级锁


    public UTXOBucket(BigDecimal minAmount, BigDecimal maxAmount) {
        this.minAmount = minAmount;
        this.maxAmount = maxAmount;
        this.totalAmount = BigDecimal.ZERO;
        this.utxos = new ArrayList<>();
    }





    // 获取桶内所有可用的 UTXO
    public List<UTXO> getAvailableUTXOs() {
        bucketLock.lock();
        try {
            return utxos;
        } finally {
            bucketLock.unlock();
        }
    }









}
