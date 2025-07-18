package com.pop.popcoinsystem.consensus.pos;

import lombok.Getter;

import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class TESTPOSContinuousBlockchainMining {
    // 交易池（按手续费率排序的优先队列）
    private static final PriorityQueue<Transaction> transactionPool = new PriorityQueue<>(
            Comparator.comparingDouble(Transaction::getFeePerByte).reversed()
    );

    // 本地区块链
    private static final List<Block> blockchain = new ArrayList<>();

    // 验证者列表及其权益
    private static final Map<String, Double> validators = new ConcurrentHashMap<>();

    // 区块生成间隔（毫秒）
    private static final long BLOCK_GENERATION_INTERVAL = 10000; // 10秒

    // 创世区块哈希
    private static final String GENESIS_HASH = "000000000000000000000000000000000000000000000000000000000000";

    // 用于停止挖矿的标志
    private static volatile boolean isMining = true;

    // 交易类
    static class Transaction {
        private final String txId;
        private final String sender;
        private final String recipient;
        private final double amount;
        @Getter
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

    // 区块类
    static class Block {
        private final int index;
        private final String previousHash;
        private final long timestamp;
        private final List<Transaction> transactions;
        private final String validator;
        private String signature;
        private String hash;

        // 移除难度参数
        public Block(int index, String previousHash, long timestamp, List<Transaction> transactions, String validator) {
            this.index = index;
            this.previousHash = previousHash;
            this.timestamp = timestamp;
            this.transactions = transactions;
            this.validator = validator;
            this.hash = calculateHash();
        }

        public String calculateHash() {
            // 移除难度相关的哈希计算
            String blockData = index + previousHash + timestamp + getMerkleRoot() + validator + signature;
            return applySha256(blockData);
        }

        private String getMerkleRoot() {
            List<String> hashes = new ArrayList<>();
            for (Transaction tx : transactions) {
                hashes.add(applySha256(tx.toHashString()));
            }

            // 简化的Merkle树实现
            while (hashes.size() > 1) {
                List<String> newHashes = new ArrayList<>();
                for (int i = 0; i < hashes.size(); i += 2) {
                    String left = hashes.get(i);
                    String right = (i + 1 < hashes.size()) ? hashes.get(i + 1) : left;
                    newHashes.add(applySha256(left + right));
                }
                hashes = newHashes;
            }

            return hashes.isEmpty() ? "" : hashes.get(0);
        }

        public int getIndex() {
            return index;
        }

        public String getPreviousHash() {
            return previousHash;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public List<Transaction> getTransactions() {
            return transactions;
        }

        public String getValidator() {
            return validator;
        }

        public String getSignature() {
            return signature;
        }

        public void setSignature(String signature) {
            this.signature = signature;
            this.hash = calculateHash();
        }

        public String getHash() {
            return hash;
        }

        @Override
        public String toString() {
            // 移除难度显示
            return String.format("Block #%d\nHash: %s\nPrevious Hash: %s\nTimestamp: %s\nValidator: %s\nTransactions: %d",
                    index, hash, previousHash,
                    new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(timestamp)),
                    validator, transactions.size());
        }
    }

    public static void main(String[] args) {
        // 初始化验证者
        initializeValidators();

        // 初始化区块链（添加创世区块）
        initializeBlockchain();

        // 启动交易生成器（模拟网络中的新交易）
        startTransactionGenerator();

        // 启动POS共识机制
        startPosConsensus();

        // 运行一段时间后停止（避免无限运行）
        stopAfterDelay(30000 * 10000); // 30秒后停止
    }

    private static void initializeValidators() {
        // 初始化一些验证者及其权益
        validators.put("Validator1", 1000.0);
        validators.put("Validator2", 500.0);
        validators.put("Validator3", 300.0);
        validators.put("Validator4", 200.0);
        validators.put("Validator5", 100.0);
        System.out.println("初始化验证者: " + validators);
    }

    private static void initializeBlockchain() {
        // 移除难度参数
        Block genesisBlock = new Block(
                0,
                GENESIS_HASH,
                System.currentTimeMillis(),
                Collections.emptyList(),
                "Validator1"
        );
        genesisBlock.setSignature("GenesisSignature");
        blockchain.add(genesisBlock);
        System.out.println("创世区块已创建:");
        System.out.println(genesisBlock);
    }

    private static void startTransactionGenerator() {
        // 启动一个线程定期向交易池添加新交易
        new Thread(() -> {
            Random random = new Random();
            int txIdCounter = 0;

            while (isMining) {
                // 生成随机交易
                String sender = "Address" + random.nextInt(1000);
                String recipient = "Address" + random.nextInt(1000);
                double amount = 0.01 + random.nextDouble() * 10;
                double fee = 0.0001 + random.nextDouble() * 0.01;
                int size = 250 + random.nextInt(500); // 250-750字节

                Transaction tx = new Transaction(
                        "TX" + (txIdCounter++),
                        sender,
                        recipient,
                        amount,
                        fee,
                        size
                );

                transactionPool.add(tx);
                System.out.println("新交易已加入交易池: " + tx);

                try {
                    Thread.sleep(100 + random.nextInt(200)); // 100-300毫秒生成一笔交易
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }).start();
    }

    private static void startPosConsensus() {
        // 为每个验证者创建一个线程
        for (String validator : validators.keySet()) {
            new Thread(() -> {
                while (isMining) {
                    // 验证者等待一个随机时间，模拟网络延迟
                    try {
                        long waitTime = (long) (BLOCK_GENERATION_INTERVAL * (0.5 + Math.random() * 0.5));
                        Thread.sleep(waitTime);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }

                    // 检查是否轮到当前验证者生成区块
                    if (shouldGenerateBlock(validator)) {
                        // 创建新区块（移除难度参数）
                        Block latestBlock = blockchain.get(blockchain.size() - 1);
                        List<Transaction> transactions = getTransactionsForBlock();

                        Block newBlock = new Block(
                                latestBlock.getIndex() + 1,
                                latestBlock.getHash(),
                                System.currentTimeMillis(),
                                transactions,
                                validator
                        );

                        // 模拟验证者签名
                        newBlock.setSignature(simulateSignature(validator, newBlock));

                        // 广播区块（在我们的模拟中，这相当于尝试添加到本地链）
                        broadcastBlock(newBlock);
                    }
                }
            }, "Validator-" + validator).start();
        }
    }

    private static boolean shouldGenerateBlock(String validator) {
        // 基于时间戳和验证者权益决定是否生成区块
        long currentTime = System.currentTimeMillis();
        long blockTime = currentTime / BLOCK_GENERATION_INTERVAL;

        // 使用SHA256生成一个随机数
        String seed = validator + blockTime + blockchain.get(blockchain.size() - 1).getHash();
        String hash = applySha256(seed);

        // 将哈希转换为一个0-1之间的小数
        double randomValue = 0;
        for (int i = 0; i < 8; i++) {
            randomValue = randomValue * 256 + (hash.charAt(i) & 0xFF);
        }
        randomValue /= Math.pow(256, 8);

        // 计算验证者的权益比例
        double stakeProportion = validators.get(validator) /
                validators.values().stream().mapToDouble(Double::doubleValue).sum();

        // 如果随机数小于权益比例，则该验证者可以生成区块
        return randomValue < stakeProportion;
    }

    private static void broadcastBlock(Block block) {
        // 在实际系统中，这会通过网络广播给其他节点
        // 在我们的模拟中，我们同步添加到本地链
        synchronized (blockchain) {
            Block latestBlock = blockchain.get(blockchain.size() - 1);

            // 检查是否有更新的区块
            if (block.getIndex() == latestBlock.getIndex() + 1) {
                // 验证区块
                if (isValidBlock(block, latestBlock)) {
                    // 检查是否已经有相同索引的区块
                    if (blockchain.size() <= block.getIndex()) {
                        // 添加区块
                        block.getTransactions().forEach(transactionPool::remove);
                        blockchain.add(block);
                        System.out.println("\n验证者 " + block.getValidator() + " 生成了新区块:");
                        System.out.println(block);
                        System.out.println("当前区块链长度: " + blockchain.size());

                        // 奖励验证者
                        rewardValidator(block.getValidator(), block.getTransactions());
                    } else {
                        System.out.println("验证者 " + block.getValidator() + " 生成的区块已过时，已存在更新的区块");
                    }
                } else {
                    System.out.println("验证者 " + block.getValidator() + " 生成的区块无效");
                }
            } else {
                System.out.println("验证者 " + block.getValidator() + " 生成的区块索引不正确");
            }
        }
    }

    private static String selectValidator() {
        // 根据权益随机选择验证者（币龄和随机性结合）
        double totalStake = validators.values().stream().mapToDouble(Double::doubleValue).sum();
        double randomValue = Math.random() * totalStake;
        double cumulativeStake = 0;

        for (Map.Entry<String, Double> entry : validators.entrySet()) {
            cumulativeStake += entry.getValue();
            if (randomValue <= cumulativeStake) {
                return entry.getKey();
            }
        }

        return null; // 理论上不会执行到这里
    }

    private static String simulateSignature(String validator, Block block) {
        // 模拟签名过程
        return applySha256(validator + block.getIndex() + block.getPreviousHash() + System.currentTimeMillis());
    }

    private static boolean isValidBlock(Block newBlock, Block previousBlock) {
        // 验证区块有效性
        if (!newBlock.getPreviousHash().equals(previousBlock.getHash())) {
            System.out.println("无效的前一个区块哈希");
            return false;
        }

        if (newBlock.getIndex() != previousBlock.getIndex() + 1) {
            System.out.println("无效的区块索引");
            return false;
        }

        if (!validators.containsKey(newBlock.getValidator())) {
            System.out.println("无效的验证者");
            return false;
        }

        if (!newBlock.getHash().equals(newBlock.calculateHash())) {
            System.out.println("无效的区块哈希");
            return false;
        }

        // 验证签名（简化）
        String expectedSignature = simulateSignature(newBlock.getValidator(), newBlock);
        if (!newBlock.getSignature().equals(expectedSignature)) {
            System.out.println("无效的签名");
            return false;
        }

        return true;
    }

    private static void rewardValidator(String validator, List<Transaction> transactions) {
        // 计算交易费用总和作为奖励
        double totalFees = transactions.stream().mapToDouble(Transaction::getFee).sum();
        double blockReward = 2.0; // 固定区块奖励

        // 更新验证者权益
        validators.put(validator, validators.get(validator) + blockReward + totalFees);
        System.out.println("验证者 " + validator + " 获得奖励: " + (blockReward + totalFees) + " 代币");
    }

    private static List<Transaction> getTransactionsForBlock() {
        List<Transaction> selectedTransactions = new ArrayList<>();
        int blockSizeLimit = 1024 * 1024; // 模拟区块大小限制（字节）
        int currentSize = 0;

        // 从交易池中选择手续费最高的交易，直到达到区块大小限制
        while (!transactionPool.isEmpty() && currentSize < blockSizeLimit) {
            Transaction tx = transactionPool.peek();
            if (currentSize + tx.size <= blockSizeLimit) {
                selectedTransactions.add(transactionPool.poll());
                currentSize += tx.size;
            } else {
                break; // 剩余交易太大，无法放入当前区块
            }
        }

        return selectedTransactions;
    }

    private static void stopAfterDelay(long delayMillis) {
        new Thread(() -> {
            try {
                Thread.sleep(delayMillis);
                System.out.println("\n挖矿将在当前区块完成后停止...");
                isMining = false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }

    private static String applySha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes("UTF-8"));

            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = String.format("%02x", b);
                hexString.append(hex);
            }

            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}