package com.pop.popcoinsystem.consensus.pow;


import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

public class ContinuousBlockchainMining {
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

    // 交易类
    static class Transaction {
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

        public Block(int index, String previousHash, long timestamp, List<Transaction> transactions, int difficulty) {
            this.index = index;
            this.previousHash = previousHash;
            this.timestamp = timestamp;
            this.transactions = transactions;
            this.difficulty = difficulty;
            this.nonce = 0;
            this.hash = calculateHash();
        }

        public String calculateHash() {
            String blockData = index + previousHash + timestamp + getMerkleRoot() + difficulty + nonce;
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

        @Override
        public String toString() {
            return String.format("Block #%d\nHash: %s\nPrevious Hash: %s\nTimestamp: %s\nDifficulty: %d\nNonce: %d\nTransactions: %d",
                    index, hash, previousHash,
                    new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(timestamp)),
                    difficulty, nonce, transactions.size());
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
        stopAfterDelay(30000 * 100); // 30秒后停止
    }

    // 3. 补充：创世区块挖矿失败时的处理（避免程序卡死）
    private static void initializeBlockchain() {
        Block genesisBlock = new Block(
                0,
                GENESIS_HASH,
                System.currentTimeMillis(),
                Collections.emptyList(),
                currentDifficulty
        );
        genesisBlock.setNonce(0);
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
                    Thread.sleep(10 + random.nextInt(50)); // 100-200 毫秒 秒生成一笔交易
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }).start();
    }

    // 2. 修改 startMining 方法：严格校验挖矿结果有效性
    private static void startMining(int threadCount) {
        new Thread(() -> {
            while (isMining) {
                Block latestBlock = blockchain.get(blockchain.size() - 1);
                List<Transaction> transactions = getTransactionsForBlock();
                System.out.println("提取的交易"+ transactions.size());

                if (transactions.isEmpty()) {
                    System.out.println("交易池为空，继续挖矿...");
                }

                Block newBlock = new Block(
                        latestBlock.getIndex() + 1,
                        latestBlock.getHash(),
                        System.currentTimeMillis(),
                        transactions,
                        currentDifficulty
                );

                System.out.println("\n开始挖矿新区块 #" + newBlock.getIndex() +
                        " (难度: " + newBlock.getDifficulty() + ", 交易数: " + transactions.size() + ")");

                long startTime = System.currentTimeMillis();
                MiningResult result = mineBlock(newBlock, threadCount); // 可能返回 null
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

    // 1. 修改 mineBlock 方法：当未找到有效哈希时，返回 null 或明确标记无效
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

                    // 提高进度输出频率（方便观察）
           /*         if (nonce % 100000 == 0) {
                        System.out.print(".");
                    }*/


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
                return null; // 明确返回 null 表示未找到
            }

/*            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
                return null;
            }*/

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
        long targetTime = 10 ; // 目标：每个区块1分钟，10个区块就是10分钟

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
}