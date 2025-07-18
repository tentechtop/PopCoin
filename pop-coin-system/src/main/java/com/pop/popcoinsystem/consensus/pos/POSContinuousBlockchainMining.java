package com.pop.popcoinsystem.consensus.pos;

import lombok.Getter;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

public class POSContinuousBlockchainMining {
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

    // 难度调整间隔（区块数）
    private static final int DIFFICULTY_ADJUSTMENT_INTERVAL = 10;

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
        private final double difficulty;
        private String signature;
        private String hash;

        public Block(int index, String previousHash, long timestamp, List<Transaction> transactions, String validator, double difficulty) {
            this.index = index;
            this.previousHash = previousHash;
            this.timestamp = timestamp;
            this.transactions = transactions;
            this.validator = validator;
            this.difficulty = difficulty;
            this.hash = calculateHash();
        }

        public String calculateHash() {
            String blockData = index + previousHash + timestamp + getMerkleRoot() + validator + difficulty + signature;
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

        public double getDifficulty() {
            return difficulty;
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
            return String.format("Block #%d\nHash: %s\nPrevious Hash: %s\nTimestamp: %s\nValidator: %s\nDifficulty: %.2f\nTransactions: %d",
                    index, hash, previousHash,
                    new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(timestamp)),
                    validator, difficulty, transactions.size());
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
        Block genesisBlock = new Block(
                0,
                GENESIS_HASH,
                System.currentTimeMillis(),
                Collections.emptyList(),
                "Validator1",
                1.0
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
        // 启动POS共识机制
        new Thread(() -> {
            while (isMining) {
                // 等待区块生成间隔
                try {
                    Thread.sleep(BLOCK_GENERATION_INTERVAL);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }

                // 选择下一个验证者
                String selectedValidator = selectValidator();
                if (selectedValidator == null) {
                    System.out.println("没有可用的验证者，跳过此轮...");
                    continue;
                }

                // 创建新区块
                Block latestBlock = blockchain.get(blockchain.size() - 1);
                List<Transaction> transactions = getTransactionsForBlock();
                double difficulty = calculateDifficulty();

                Block newBlock = new Block(
                        latestBlock.getIndex() + 1,
                        latestBlock.getHash(),
                        System.currentTimeMillis(),
                        transactions,
                        selectedValidator,
                        difficulty
                );

                // 模拟验证者签名
                newBlock.setSignature(simulateSignature(selectedValidator, newBlock));

                // 验证并添加区块
                if (isValidBlock(newBlock, latestBlock)) {
                    transactions.forEach(transactionPool::remove);
                    blockchain.add(newBlock);
                    System.out.println("\n新区块已生成:");
                    System.out.println(newBlock);
                    System.out.println("当前区块链长度: " + blockchain.size());

                    // 奖励验证者
                    rewardValidator(selectedValidator, transactions);
                } else {
                    System.out.println("无效的区块，丢弃...");
                }
            }
        }).start();
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

    private static double calculateDifficulty() {
        // 简化的难度调整算法
        if (blockchain.size() % DIFFICULTY_ADJUSTMENT_INTERVAL != 0) {
            return blockchain.get(blockchain.size() - 1).getDifficulty();
        }

        // 计算最近区块的平均生成时间
        long totalTime = 0;
        int count = Math.min(DIFFICULTY_ADJUSTMENT_INTERVAL, blockchain.size() - 1);

        for (int i = 0; i < count; i++) {
            Block current = blockchain.get(blockchain.size() - 1 - i);
            Block previous = blockchain.get(blockchain.size() - 2 - i);
            totalTime += current.getTimestamp() - previous.getTimestamp();
        }

        double averageTime = totalTime / count;
        double difficulty = blockchain.get(blockchain.size() - 1).getDifficulty();

        // 根据平均生成时间调整难度
        if (averageTime < BLOCK_GENERATION_INTERVAL * 0.8) {
            difficulty *= 1.1; // 增加难度
        } else if (averageTime > BLOCK_GENERATION_INTERVAL * 1.2) {
            difficulty *= 0.9; // 降低难度
        }

        return Math.max(1.0, difficulty); // 确保难度至少为1
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