package com.pop.popcoinsystem.consensus.pow;

import java.util.*;

public class BitcoinMempool {
    private static final long MAX_SIZE_BYTES = 300 * 1024 * 1024; // 300MB

    /**
     * LinkedHashMap 会维护元素的插入顺序，这意味着当你迭代交易池时，交易将按照它们被添加的顺序返回。
     * 这在某些场景下很有用，比如当你需要按时间顺序处理最早的交易时。
     *
     * 支持 LRU 缓存策略（潜在用途）：LinkedHashMap 可以通过构造函数设置为访问顺序（access-order），
     * 这样最近访问的元素会被移到链表尾部，从而支持 LRU（最近最少使用）缓存策略。虽然当前实现没有使用这一特性，但这种结构为未来的优化提供了可能。
     *
     *高效的访问和插入：与 HashMap 一样，LinkedHashMap 提供了 O (1) 时间复杂度的基本操作（get、put、remove），
     * 同时额外维护了一个双向链表来保持顺序，开销相对较小。
     *
     * 线程安全包装：在当前实现中，通过 synchronized 关键字确保了线程安全。
     * LinkedHashMap 本身不是线程安全的，但通过外部同步可以安全地在多线程环境中使用。
     */
    private final Map<String, Transaction> transactions = new LinkedHashMap<>();
    private long currentSize = 0;

    // 添加交易到交易池
    public synchronized boolean addTransaction(Transaction tx) {
        // 检查交易是否已存在
        if (transactions.containsKey(tx.txId)) {
            return false;
        }

        // 检查交易大小是否超过总容量
        if (tx.size > MAX_SIZE_BYTES) {
            return false;
        }

        // 移除低优先级交易直到有足够空间
        while (currentSize + tx.size > MAX_SIZE_BYTES && !transactions.isEmpty()) {
            removeLowestPriorityTransaction();
        }

        // 添加交易
        transactions.put(tx.txId, tx);
        currentSize += tx.size;
        return true;
    }

    // 移除低优先级交易
    private void removeLowestPriorityTransaction() {
        String lowestPriorityTxId = null;
        double lowestFeePerByte = Double.MAX_VALUE;

        // 找到每字节手续费最低的交易
        for (Map.Entry<String, Transaction> entry : transactions.entrySet()) {
            if (entry.getValue().getFeePerByte() < lowestFeePerByte) {
                lowestFeePerByte = entry.getValue().getFeePerByte();
                lowestPriorityTxId = entry.getKey();
            }
        }

        // 移除找到的交易
        if (lowestPriorityTxId != null) {
            currentSize -= transactions.get(lowestPriorityTxId).size;
            transactions.remove(lowestPriorityTxId);
        }
    }

    // 按每字节手续费从高到低获取交易列表
    public synchronized List<Transaction> getTransactionsByPriority() {
        List<Transaction> txList = new ArrayList<>(transactions.values());
        txList.sort((tx1, tx2) -> Double.compare(tx2.getFeePerByte(), tx1.getFeePerByte()));
        return txList;
    }

    // 获取交易池中的交易数量
    public synchronized int getTransactionCount() {
        return transactions.size();
    }

    // 获取交易池当前大小（字节）
    public synchronized long getCurrentSize() {
        return currentSize;
    }

    // 获取交易池最大容量（字节）
    public long getMaxSize() {
        return MAX_SIZE_BYTES;
    }

    // 根据txId获取交易
    public synchronized Transaction getTransaction(String txId) {
        return transactions.get(txId);
    }

    // 移除指定txId的交易
    public synchronized boolean removeTransaction(String txId) {
        Transaction tx = transactions.remove(txId);
        if (tx != null) {
            currentSize -= tx.size;
            return true;
        }
        return false;
    }

    // 清空交易池
    public synchronized void clear() {
        transactions.clear();
        currentSize = 0;
    }

    // 提供的Transaction类（包含了缺失的@Getter依赖处理）
    public static class Transaction {
        private final String txId;
        private final String sender;
        private final String recipient;
        private final double amount;
        private final double fee;
        private final int size; // 交易大小（字节）

        public Transaction(String txId, String sender, String recipient, double amount, double fee, int size) {
            this.txId = txId;
            this.sender = sender;
            this.recipient = recipient;
            this.amount = amount;
            this.fee = fee;
            this.size = size;
        }

        // 手动实现Getter方法，避免对Lombok的依赖
        public double getFee() {
            return fee;
        }

        public double getFeePerByte() {
            return fee / size;
        }

        @Override
        public String toString() {
            return String.format("TxID: %s, From: %s, To: %s, Amount: %.8f BTC, Fee: %.8f BTC",
                    txId, sender, recipient, amount, fee);
        }

        public String toHashString() {
            return txId + sender + recipient + amount + fee;
        }
    }

    // 示例用法
    public static void main(String[] args) {
        BitcoinMempool mempool = new BitcoinMempool();

        // 添加一些交易
        mempool.addTransaction(new Transaction("tx1", "Alice", "Bob", 1.0, 0.001, 250));
        mempool.addTransaction(new Transaction("tx2", "Bob", "Charlie", 2.0, 0.002, 300));
        mempool.addTransaction(new Transaction("tx3", "Charlie", "Alice", 3.0, 0.0005, 200));

        // 打印交易池信息
        System.out.println("交易池大小: " + mempool.getCurrentSize() + " 字节 / " +
                mempool.getMaxSize() + " 字节");
        System.out.println("交易数量: " + mempool.getTransactionCount());

        // 按优先级打印交易
        System.out.println("\n按优先级排序的交易:");
        for (Transaction tx : mempool.getTransactionsByPriority()) {
            System.out.println(tx + ", 每字节手续费: " + tx.getFeePerByte());
        }
    }
}