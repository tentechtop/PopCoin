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

    // 添加 UTXO 到桶中
    public void addUTXO(UTXO utxo) {
        bucketLock.lock();
        try {
            if (utxo.getValue().compareTo(minAmount) < 0 ||
                    utxo.getValue().compareTo(maxAmount) >= 0) {
                throw new IllegalArgumentException("UTXO 金额超出桶的范围");
            }
            utxos.add(utxo);
            totalAmount = totalAmount.add(utxo.getValue());
        } finally {
            bucketLock.unlock();
        }
    }

    // 从桶中移除 UTXO
    public void removeUTXO(UTXO utxo) {
        bucketLock.lock();
        try {
            if (utxos.remove(utxo)) {
                totalAmount = totalAmount.subtract(utxo.getValue());
            }
        } finally {
            bucketLock.unlock();
        }
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

    // 检查桶内可用 UTXO 是否足够支付指定金额
    public boolean hasEnoughAmount(BigDecimal amount) {
        return getAvailableTotalAmount().compareTo(amount) >= 0;
    }

    // 获取桶内可用 UTXO 的总金额
    public BigDecimal getAvailableTotalAmount() {
        bucketLock.lock();
        try {
            return utxos.stream()
                    .map(UTXO::getValue)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        } finally {
            bucketLock.unlock();
        }
    }










}
