package com.pop.popcoinsystem.service;

import com.pop.popcoinsystem.consensus.pow.NewContinuousBlockchainMining;
import com.pop.popcoinsystem.data.block.Block;
import com.pop.popcoinsystem.data.miner.Miner;
import com.pop.popcoinsystem.data.storage.POPStorage;
import com.pop.popcoinsystem.data.transaction.Transaction;
import com.pop.popcoinsystem.data.vo.result.Result;
import com.pop.popcoinsystem.util.CryptoUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 打包交易服务
 * 有交易时 每分钟出一个区块 无交易时则提示难度也提升挖矿奖励 最高提高到60分钟出一个区块
 *
 *
 */
@Slf4j
@Service
public class MiningService {

    @Resource
    private BlockChainService blockChainService;

    //矿工信息
    public static Miner miner;
    // 当前难度目标（前导零的数量）
    private static int currentDifficulty = 1;
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
    //是否启动挖矿服务 用于停止挖矿的标志
    public static boolean isMining = false;

    /**
     * 交易池  最大300M
     */
    private static final long MAX_SIZE_BYTES = 300 * 1024 * 1024; // 300MB
    private final Map<byte[], Transaction> transactions = new LinkedHashMap<>();
    private long currentSize = 0;

    private static int threadCount =  Runtime.getRuntime().availableProcessors();

    private static  ExecutorService executor = Executors.newFixedThreadPool(threadCount);


    /**
     * 添加交易到交易池 该交易已经验证
     */
    public synchronized boolean addTransaction(Transaction tx) {
        // 检查交易是否已存在
        if (transactions.containsKey(tx.getTxId())) {
            //丢弃 防止双花 只认第一笔
            log.warn("Duplicate transaction detected.");
            return false;
        }
        // 检查交易大小是否超过总容量
        if (tx.getSize() > MAX_SIZE_BYTES) {
            log.warn("Transaction size exceeds maximum allowed size.");
            return false;
        }
        //空间不够直接拒绝
        if (currentSize + tx.getSize() > MAX_SIZE_BYTES) {
            log.warn("Transaction size exceeds maximum allowed size.");
            return false;
        }
        // 移除低优先级交易直到有足够空间 移除手续费大小比率 比这个小的
        while (currentSize + tx.getSize() > MAX_SIZE_BYTES && !transactions.isEmpty()) {
            removeLowestPriorityTransaction(tx);
        }
        // 添加交易
        transactions.put(tx.getTxId(), tx);
        currentSize += tx.getSize();
        return true;
    }

    // 移除低优先级交易
    private void removeLowestPriorityTransaction(Transaction tx) {
        // 1. 获取传入交易的手续费率作为基准（避免重复计算）
        double targetFeePerByte = tx.getFeePerByte();

        byte[] lowestPriorityTxId = null;
        double lowestFeePerByte = Double.MAX_VALUE;

        // 2. 遍历交易池，筛选出手续费率低于基准的交易
        for (Map.Entry<byte[], Transaction> entry : transactions.entrySet()) {
            Transaction existingTx = entry.getValue();
            double existingFeePerByte = existingTx.getFeePerByte();
            // 只关注手续费率低于基准的交易，且记录其中最低的
            if (existingFeePerByte < targetFeePerByte) {
                if (existingFeePerByte < lowestFeePerByte) {
                    lowestFeePerByte = existingFeePerByte;
                    lowestPriorityTxId = entry.getKey();
                }
            }
        }
        // 3. 移除找到的最低优先级交易（若存在）
        if (lowestPriorityTxId != null) {
            Transaction removedTx = transactions.get(lowestPriorityTxId);
            currentSize -= removedTx.getSize();
            transactions.remove(lowestPriorityTxId);
            // 可选：添加日志便于调试
            log.info("Removed low-priority transaction (feePerByte: {}) to make space", lowestFeePerByte);
        } else {
            // 4. 若没有找到符合条件的交易（所有交易手续费率都高于等于基准），则不做处理
            log.info("No transaction with lower feePerByte than the new transaction");
        }
    }

    /**
     * 获取总大小小于1MB的高优先级交易列表
     * @return 符合条件的交易列表
     */
    public synchronized List<Transaction> getTransactionsUpTo1MB() {
        final long MAX_RETURN_SIZE = 1024 * 1024; // 1MB
        List<Transaction> selectedTxs = new ArrayList<>();
        long totalSize = 0;
        // 按优先级从高到低遍历交易
        for (Transaction tx : getTransactionsByPriority()) {
            // 如果添加当前交易后总大小超过1MB，则停止
            if (totalSize + tx.getSize() > MAX_RETURN_SIZE) {
                break;
            }
            selectedTxs.add(tx);
            totalSize += tx.getSize();
        }
        return selectedTxs;
    }


    // 按每字节手续费从高到低获取交易列表
    public synchronized List<Transaction> getTransactionsByPriority() {
        final long MAX_RETURN_SIZE = 1024 * 1024; // 1MB
        List<Transaction> txList = new ArrayList<>(transactions.values());
        // 1. 先按手续费率从高到低排序
        txList.sort((tx1, tx2) -> Double.compare(tx2.getFeePerByte(), tx1.getFeePerByte()));
        // 2. 再筛选总大小不超过1MB的交易
        List<Transaction> selectedTxs = new ArrayList<>();
        long totalSize = 0;
        for (Transaction tx : txList) {
            if (totalSize + tx.getSize() > MAX_RETURN_SIZE) {
                break; // 超过1MB则停止
            }
            selectedTxs.add(tx);
            totalSize += tx.getSize();
        }
        return selectedTxs;
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
            currentSize -= tx.getSize();
            return true;
        }
        return false;
    }

    // 清空交易池
    public synchronized void clear() {
        transactions.clear();
        currentSize = 0;
    }



    /**
     * 是否矿工节点
     */
    public boolean isMining(){
        return isMining;
    }


    /**
     * 打包交易，进行挖矿
     */
    public MiningResult mineBlock(Block block){
        MiningResult result = new MiningResult();
        Future<?>[] futures = new Future[threadCount];
        int nonceRange = Integer.MAX_VALUE / threadCount;

        for (int i = 0; i < threadCount; i++) {
            final int startNonce = i * nonceRange;
            final int endNonce = (i == threadCount - 1) ? Integer.MAX_VALUE : (i + 1) * nonceRange;
            futures[i] = executor.submit(() -> {
                String targetPrefix = new String(new char[block.getDifficulty()]).replace('\0', '0');
                for (int nonce = startNonce; nonce < endNonce && !result.found; nonce++) {
                    block.setNonce(nonce);
                    byte[] hash = block.getHash();
                    String hashString = CryptoUtil.bytesToHex(hash);
                    if (hashString.startsWith(targetPrefix)) {
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

        // 等待所有任务完成
        try {
            for (Future<?> future : futures) {
                if (future != null) {
                    future.get(); // 阻塞直到任务完成
                }
            }
        } catch (InterruptedException | ExecutionException e) {
            Thread.currentThread().interrupt();
        } finally {
            // 无论是否找到结果，都优雅关闭线程池
            if (!executor.isShutdown()) {
                executor.shutdown();
            }
        }
        // 根据结果返回
        if (result.found) {
            return result;
        } else {
            System.out.println("\nNonce范围已用尽，未找到有效哈希，重新开始挖矿...");
            return null;
        }
    }


    /**
     * 启动挖矿
     */
    public Result<String> startMining() throws Exception {
        if (isMining) {
            return Result.error("ERROR: The node is already mining ! ");
        }
        //获取矿工信息
        miner = POPStorage.getInstance().getMiner();
        if (miner == null) {
            //挖矿前请设置本节点的矿工信息
            return Result.error("ERROR: Please set the miner information first ! ");
        }
        isMining = true;
        new Thread(() -> {
            while (isMining) {
                // 获取交易
                List<Transaction> transactions = getTransactionsUpTo1MB();
                if (transactions.isEmpty()) {
                    log.info("No transactions available for mining.");
                }
                //区块链顶端hash
                byte[] latestBlockHash = blockChainService.getLatestBlockHash();
                long blockHeight = blockChainService.getBlockHeight(latestBlockHash);

                Block newBlock = new Block();
                newBlock.setPreviousHash(latestBlockHash);
                newBlock.setHeight(blockHeight+1);
                newBlock.setTime(System.currentTimeMillis());


                ArrayList<Transaction> blockTransactions = new ArrayList<>();
                //创建CoinBase交易 放在第一位


                Transaction coinBaseTransaction = Transaction.createCoinBaseTransaction(miner.getAddress());
                blockTransactions.add(coinBaseTransaction);
                blockTransactions.addAll(transactions);
                List<Transaction> transactionsByPriority = getTransactionsByPriority();
                blockTransactions.addAll(transactionsByPriority);
                newBlock.setTransactions(blockTransactions);

                //挖矿奖励：通过 coinbase 交易嵌入区块体
                //每个区块的第一笔交易是coinbase 交易（特殊交易，无输入），其输出部分直接包含矿工的挖矿奖励。例如：
                //比特币区块的 coinbase 交易输出会包含 “基础奖励 + 区块内所有交易的手续费总和”，这笔输出会被记录在区块体的交易列表中。
                //区块只需存储这笔交易，就能通过交易验证逻辑自动计算出矿工获得的总奖励（无需额外字段）。
                //手续费：隐含在普通交易的 “输入 - 输出差额” 中
                //普通交易中，输入金额总和 - 输出金额总和 = 手续费，这部分差额由打包该交易的矿工获得。
                //例如：用户发起一笔交易，输入 10 个代币，输出 9.9 个代币，差额 0.1 个代币即为手续费。这部分无需单独记录，通过遍历区块内所有交易的输入输出即可计算。
                System.out.println("\n开始挖矿新区块 #" + newBlock.getHeight() +
                        " (难度: " + newBlock.getDifficulty() + ", 交易数: " + transactions.size());

                long startTime = System.currentTimeMillis();
                MiningResult result = mineBlock(newBlock);
                long endTime = System.currentTimeMillis();











            }
        });
        return Result.ok();
    }





    private  void adjustDifficulty() {
        byte[] latestBlockHash = blockChainService.getLatestBlockHash();
        long blockHeight = blockChainService.getBlockHeight(latestBlockHash);
        // 模拟比特币难度调整机制
        if (blockHeight < 10) return;



    }





    // 挖矿结果类
    static class MiningResult {
        byte[] hash;
        int nonce;
        boolean found = false;
    }



    /**
     * 停止挖矿
     */
    public Result<String> stopMining() throws Exception {
        if (!isMining) {
            return Result.error("ERROR: The node is not mining ! ");
        }
        isMining = false;



        return Result.ok();
    }



    /**
     * 设置节点矿工信息
     */
    public Result<String> setMiningInfo(Miner miner){
        try {
            POPStorage instance = POPStorage.getInstance();
            instance.addOrUpdateMiner(miner);
            return Result.ok();
        }catch (Exception e){
            return Result.error(e.getMessage());
        }
    }




}
