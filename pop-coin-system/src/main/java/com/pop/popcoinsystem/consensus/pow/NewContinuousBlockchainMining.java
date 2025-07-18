package com.pop.popcoinsystem.consensus.pow;

import lombok.Getter;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

public class NewContinuousBlockchainMining {
    // 交易池（按手续费率排序的优先队列）
    private static final PriorityQueue<Transaction> transactionPool = new PriorityQueue<>(
            Comparator.comparingDouble(Transaction::getFeePerByte).reversed()
    );

    // 本地区块链
    private static final List<Block> blockchain = new ArrayList<>();

    // 当前难度目标（前导零的数量）
    private static int currentDifficulty = 1;

    // 创世区块哈希
    private static final String GENESIS_HASH = "000000000000000000000000000000000000000000000000000000000000";

    // 用于停止挖矿的标志
    private static volatile boolean isMining = true;

    // 基础区块奖励（无交易时的最小奖励）
    private static final double BASE_REWARD = 5.0;

    // 最大区块奖励（延迟10分钟时的奖励）
    private static final double MAX_REWARD = 50.0;

    // 基础出块时间（秒）
    private static final long BASE_BLOCK_TIME = 10;

    // 最大延迟时间（秒）
    private static final long MAX_DELAY_TIME = 600;

    // 上次出块时间
    private static long lastBlockTime = System.currentTimeMillis();

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
        private final int difficulty;
        private int nonce;
        private String hash;
        private final double blockReward; // 区块奖励

        public Block(int index, String previousHash, long timestamp, List<Transaction> transactions,
                     int difficulty, double blockReward) {
            this.index = index;
            this.previousHash = previousHash;
            this.timestamp = timestamp;
            this.transactions = transactions;
            this.difficulty = difficulty;
            this.nonce = 0;
            this.hash = calculateHash();
            this.blockReward = blockReward;
        }

        public String calculateHash() {
            String blockData = index + previousHash + timestamp + getMerkleRoot() + difficulty + nonce + blockReward;
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

        public int getDifficulty() {
            return difficulty;
        }

        public int getNonce() {
            return nonce;
        }

        public void setNonce(int nonce) {
            this.nonce = nonce;
            this.hash = calculateHash();
        }

        public String getHash() {
            return hash;
        }

        public void setHash(String hash) {
            this.hash = hash;
        }

        public double getBlockReward() {
            return blockReward;
        }

        @Override
        public String toString() {
            return String.format("Block #%d\nHash: %s\nPrevious Hash: %s\nTimestamp: %s\nDifficulty: %d\nNonce: %d\nTransactions: %d\nBlock Reward: %.8f BTC",
                    index, hash, previousHash,
                    new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(timestamp)),
                    difficulty, nonce, transactions.size(), blockReward);
        }
    }

    // 挖矿结果类
    static class MiningResult {
        String hash;
        int nonce;
        boolean found = false;
    }

    public static void main(String[] args) {
        // 设置线程数（默认使用CPU核心数）
        int threadCount = (args.length > 0) ? Integer.parseInt(args[0]) : Runtime.getRuntime().availableProcessors();

        // 初始化区块链（添加创世区块）
        initializeBlockchain();

        // 启动交易生成器（模拟网络中的新交易）
        startTransactionGenerator();

        // 启动持续挖矿
        startMining(threadCount);

        // 运行一段时间后停止（避免无限运行）
        stopAfterDelay(30000  * 10000); // 30秒后停止
    }

    private static void initializeBlockchain() {
        Block genesisBlock = new Block(
                0,
                GENESIS_HASH,
                System.currentTimeMillis(),
                Collections.emptyList(),
                currentDifficulty,
                BASE_REWARD // 创世区块使用基础奖励
        );
        genesisBlock.setNonce(0);
        blockchain.add(genesisBlock);
        System.out.println("创世区块已创建:");
        System.out.println(genesisBlock);
        lastBlockTime = genesisBlock.getTimestamp();
    }

/*    private static void startTransactionGenerator() {
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
                    Thread.sleep(100 + random.nextInt(500)); // 1-6秒生成一笔交易
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }).start();
    }*/



    private static void startTransactionGenerator() {
        // 启动一个线程按照指定模式生成交易
        new Thread(() -> {
            Random random = new Random();
            int txIdCounter = 0;

            // 时间控制参数（毫秒）
            final long INITIAL_ACTIVE_PERIOD = 10 * 60 * 1000;  // 初始活跃10分钟
            final long FIRST_INACTIVE_PERIOD = 10 * 60 * 1000; // 第一次不活跃10分钟
            final long SECOND_ACTIVE_PERIOD = 10 * 60 * 1000; // 第二次活跃10分钟
            final long SECOND_INACTIVE_PERIOD = 20 * 60 * 1000; // 第二次不活跃20分钟

            long startTime = System.currentTimeMillis();

            while (isMining) {
                long currentTime = System.currentTimeMillis();
                long elapsedTime = currentTime - startTime;

                // 检查当前时间处于哪个时间段
                boolean shouldGenerateTx = false;

                if (elapsedTime < INITIAL_ACTIVE_PERIOD) {
                    shouldGenerateTx = true;
                } else if (elapsedTime < INITIAL_ACTIVE_PERIOD + FIRST_INACTIVE_PERIOD) {
                    shouldGenerateTx = false;
                    // 第一次不活跃期结束时打印信息
                    if (elapsedTime >= INITIAL_ACTIVE_PERIOD && elapsedTime < INITIAL_ACTIVE_PERIOD + 1000) {
                        System.out.println("\n交易生成器进入第一次10分钟中止期...");
                    }
                } else if (elapsedTime < INITIAL_ACTIVE_PERIOD + FIRST_INACTIVE_PERIOD + SECOND_ACTIVE_PERIOD) {
                    shouldGenerateTx = true;
                    // 第二次活跃期开始时打印信息
                    if (elapsedTime >= INITIAL_ACTIVE_PERIOD + FIRST_INACTIVE_PERIOD &&
                            elapsedTime < INITIAL_ACTIVE_PERIOD + FIRST_INACTIVE_PERIOD + 1000) {
                        System.out.println("\n交易生成器恢复运行，进入第二次10分钟活跃期...");
                    }
                } else if (elapsedTime < INITIAL_ACTIVE_PERIOD + FIRST_INACTIVE_PERIOD + SECOND_ACTIVE_PERIOD + SECOND_INACTIVE_PERIOD) {
                    shouldGenerateTx = false;
                    // 第二次不活跃期开始时打印信息
                    if (elapsedTime >= INITIAL_ACTIVE_PERIOD + FIRST_INACTIVE_PERIOD + SECOND_ACTIVE_PERIOD &&
                            elapsedTime < INITIAL_ACTIVE_PERIOD + FIRST_INACTIVE_PERIOD + SECOND_ACTIVE_PERIOD + 1000) {
                        System.out.println("\n交易生成器进入第二次20分钟中止期...");
                    }
                } else {
                    // 所有周期结束后，恢复初始状态
                    startTime = currentTime;
                    shouldGenerateTx = true;
                    System.out.println("\n交易生成器重置，进入新一轮周期...");
                }

                // 根据当前时间段决定是否生成交易
                if (shouldGenerateTx) {
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
                }

                try {
                    // 每1秒检查一次状态
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }).start();
    }



    private static void startMining(int threadCount) {
        new Thread(() -> {
            while (isMining) {
                // 计算等待时间
                long currentTime = System.currentTimeMillis();
                long timeSinceLastBlock = (currentTime - lastBlockTime) / 1000; // 秒

                // 获取交易
                List<Transaction> transactions = getTransactionsForBlock();
                System.out.println("提取的交易数量: " + transactions.size());

                // 计算基础等待时间
                long baseWaitTime = (transactions.isEmpty()) ? BASE_BLOCK_TIME * 2 : BASE_BLOCK_TIME;

                // 计算实际需要等待的时间（考虑上次出块后的时间）
                long waitTime = Math.max(0, baseWaitTime - timeSinceLastBlock);

                // 计算区块奖励
                double blockReward = calculateBlockReward(transactions, waitTime);

                // 如果没有交易，增加等待时间和奖励
                if (transactions.isEmpty()) {
                    // 计算最大可延长的等待时间
                    long maxAdditionalWait = MAX_DELAY_TIME - baseWaitTime;
                    if (maxAdditionalWait > 0) {
                        // 延长等待时间，最多到MAX_DELAY_TIME
                        long additionalWait = Math.min(maxAdditionalWait, (long)(waitTime * 2));
                        waitTime += additionalWait;
                        blockReward = calculateBlockReward(transactions, waitTime);

                        System.out.println("无交易，延长出块时间至: " + waitTime + "秒，区块奖励: " + blockReward + " BTC");
                    }
                }

                // 等待指定时间
                if (waitTime > 0) {
                    System.out.println("等待 " + waitTime + " 秒后开始挖矿...");
                    try {
                        Thread.sleep(waitTime * 1000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        continue;
                    }
                }

                Block latestBlock = blockchain.get(blockchain.size() - 1);
                currentTime = System.currentTimeMillis();

                Block newBlock = new Block(
                        latestBlock.getIndex() + 1,
                        latestBlock.getHash(),
                        currentTime,
                        transactions,
                        currentDifficulty,
                        blockReward
                );

                System.out.println("\n开始挖矿新区块 #" + newBlock.getIndex() +
                        " (难度: " + newBlock.getDifficulty() + ", 交易数: " + transactions.size() +
                        ", 奖励: " + newBlock.getBlockReward() + " BTC)");

                long startTime = System.currentTimeMillis();
                MiningResult result = mineBlock(newBlock, threadCount);
                long endTime = System.currentTimeMillis();

                // 关键校验：只有找到有效哈希时才添加区块
                if (result != null && result.found) {
                    newBlock.setNonce(result.nonce);
                    newBlock.setHash(result.hash);

                    transactions.forEach(transactionPool::remove);
                    System.out.println("区块 #" + newBlock.getIndex() + " 已确认!");
                    System.out.println("耗时: " + (endTime - startTime) + "ms");
                    System.out.println("Hash: " + newBlock.getHash());
                    System.out.println("Nonce: " + newBlock.getNonce());
                    System.out.println("当前区块链长度: " + blockchain.size());

                    blockchain.add(newBlock);
                    lastBlockTime = currentTime;

                    if (blockchain.size() % 10 == 0) {
                        adjustDifficulty();
                    }
                } else {
                    // 未找到有效哈希，将交易放回交易池（避免丢失）
                    transactionPool.addAll(transactions);
                    System.out.println("区块 #" + newBlock.getIndex() + " 挖矿失败，重新尝试...");
                }
            }
        }).start();
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

    private static MiningResult mineBlock(Block block, int threadCount) {
        MiningResult result = new MiningResult();
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        Future<?>[] futures = new Future[threadCount];

        int nonceRange = Integer.MAX_VALUE / threadCount;

        for (int i = 0; i < threadCount; i++) {
            final int startNonce = i * nonceRange;
            final int endNonce = (i == threadCount - 1) ? Integer.MAX_VALUE : (i + 1) * nonceRange;

            futures[i] = executor.submit(() -> {
                String targetPrefix = new String(new char[block.getDifficulty()]).replace('\0', '0');

                for (int nonce = startNonce; nonce < endNonce && !result.found; nonce++) {
                    block.setNonce(nonce);
                    String hash = block.getHash();

                    if (hash.startsWith(targetPrefix)) {
                        synchronized (result) {
                            if (!result.found) {
                                result.hash = hash;
                                result.nonce = nonce;
                                result.found = true;
                                System.out.println("线程 " + Thread.currentThread().getName() + " 找到有效哈希!");
                            }
                        }
                        return;
                    }
                }
            });
        }

        // 等待结果，若未找到则返回 null
        while (!result.found) {
            boolean allCompleted = true;
            for (Future<?> future : futures) {
                if (future == null || !future.isDone()) {
                    allCompleted = false;
                    break;
                }
            }

            if (allCompleted) {
                System.out.println("\nNonce范围已用尽，未找到有效哈希，重新开始挖矿...");
                executor.shutdownNow();
                return null;
            }

            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
                return null;
            }
        }
        executor.shutdownNow();
        return result;
    }

    private static int findValidNonce(Block block, int threadCount) {
        // 简化版挖矿（单线程用于创世区块）
        String targetPrefix = new String(new char[block.getDifficulty()]).replace('\0', '0');

        for (int nonce = 0; nonce < Integer.MAX_VALUE; nonce++) {
            block.setNonce(nonce);
            String hash = block.getHash();

            if (hash.startsWith(targetPrefix)) {
                return nonce;
            }
        }

        return -1; // 理论上不会执行到这里
    }

    private static void adjustDifficulty() {
        // 模拟比特币难度调整机制
        if (blockchain.size() < 10) return;

        // 计算最近10个区块的平均生成时间
        Block tenBlocksAgo = blockchain.get(blockchain.size() - 10);
        Block latestBlock = blockchain.get(blockchain.size() - 1);

        long actualTimeTaken = (latestBlock.getTimestamp() - tenBlocksAgo.getTimestamp()) / 1000; // 秒
        long targetTime = 10; // 目标：每个区块10秒

        System.out.println("\n难度调整:");
        System.out.println("最近10个区块平均生成时间: " + actualTimeTaken + "秒");
        System.out.println("目标生成时间: " + targetTime + "秒");

        // 根据实际时间与目标时间的比例调整难度
        if (actualTimeTaken < targetTime / 2) {
            currentDifficulty++;
            System.out.println("难度增加: " + (currentDifficulty - 1) + " → " + currentDifficulty);
        } else if (actualTimeTaken > targetTime * 2) {
            currentDifficulty = Math.max(1, currentDifficulty - 1); // 难度最小为1
            System.out.println("难度降低: " + (currentDifficulty + 1) + " → " + currentDifficulty);
        } else {
            System.out.println("难度保持不变: " + currentDifficulty);
        }
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

    // 计算区块奖励
    private static double calculateBlockReward(List<Transaction> transactions, long waitTime) {
        // 计算交易手续费总和
        double totalFees = transactions.stream().mapToDouble(Transaction::getFee).sum();

        // 如果有交易，使用基础奖励+手续费
        if (!transactions.isEmpty()) {
            return BASE_REWARD + totalFees;
        }

        // 没有交易时，根据等待时间计算额外奖励
        double rewardMultiplier = 1.0 + (double)waitTime / MAX_DELAY_TIME * (MAX_REWARD / BASE_REWARD - 1);
        return Math.min(BASE_REWARD * rewardMultiplier, MAX_REWARD) + totalFees;
    }
}