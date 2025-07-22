package com.pop.popcoinsystem.data.storage;

import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.util.Set;

@Slf4j
public class ShardMetadata implements Serializable {
    private static final long serialVersionUID = 1L;

    private long minAmount;      // 分片最小金额
    private long maxAmount;      // 分片最大金额
    private long totalAmount;    // 分片总金额
    private int utxoCount;       // 分片UTXO数量

    public ShardMetadata() {
        this.minAmount = Long.MAX_VALUE;
        this.maxAmount = Long.MIN_VALUE;
        this.totalAmount = 0;
        this.utxoCount = 0;
    }

    // Getters and setters
    public long getMinAmount() { return minAmount; }
    public long getMaxAmount() { return maxAmount; }
    public long getTotalAmount() { return totalAmount; }
    public int getUtxoCount() { return utxoCount; }

    // 更新元数据
    public void update(long amount) {
        minAmount = Math.min(minAmount, amount);
        maxAmount = Math.max(maxAmount, amount);
        totalAmount += amount;
        utxoCount++;
    }

    // 移除UTXO后更新元数据（需要重新扫描整个分片）
    public void recalculate(Set<String> utxoKeys) {
        minAmount = Long.MAX_VALUE;
        maxAmount = Long.MIN_VALUE;
        totalAmount = 0;
        utxoCount = utxoKeys.size();

        for (String utxoKey : utxoKeys) {
            try {
                String[] parts = utxoKey.split(":");
                if (parts.length >= 3) {
                    long amount = Long.parseLong(parts[2]); // 假设UTXO键格式包含金额信息
                    minAmount = Math.min(minAmount, amount);
                    maxAmount = Math.max(maxAmount, amount);
                    totalAmount += amount;
                }
            } catch (Exception e) {
                log.error("解析UTXO键失败: {}", utxoKey, e);
            }
        }
    }
}
