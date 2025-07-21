package com.pop.popcoinsystem.service;

import com.pop.popcoinsystem.data.block.Block;
import com.pop.popcoinsystem.data.miner.Miner;
import com.pop.popcoinsystem.data.storage.POPStorage;
import com.pop.popcoinsystem.data.transaction.Transaction;
import com.pop.popcoinsystem.data.vo.result.Result;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        // 移除低优先级交易直到有足够空间
/*        while (currentSize + tx.getSize() > MAX_SIZE_BYTES && !transactions.isEmpty()) {
            removeLowestPriorityTransaction();
        }*/
        // 添加交易
        transactions.put(tx.getTxId(), tx);
        currentSize += tx.getSize();
        return true;
    }

    // 移除低优先级交易
    private void removeLowestPriorityTransaction() {
        byte[] lowestPriorityTxId = null;
        double lowestFeePerByte = Double.MAX_VALUE;
        // 找到每字节手续费最低的交易
        for (Map.Entry<byte[], Transaction> entry : transactions.entrySet()) {
            if (entry.getValue().getFeePerByte() < lowestFeePerByte) {
                lowestFeePerByte = entry.getValue().getFeePerByte();
                lowestPriorityTxId = entry.getKey();
            }
        }
        // 移除找到的交易
        if (lowestPriorityTxId != null) {
            currentSize -= transactions.get(lowestPriorityTxId).getSize();
            transactions.remove(lowestPriorityTxId);
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
    public void mineBlock() throws Exception {
        List<Transaction> transactions = getTransactionsUpTo1MB();
        //获取最新的区块哈希
        byte[] latestBlockHash = blockChainService.getLatestBlockHash();
        if (latestBlockHash == null || latestBlockHash.length == 0) {
            throw new Exception("ERROR: Fail to get last block hash ! ");
        }
        Block block = new Block();

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
                //最后一个区块hash


                Block block = new Block();
                ArrayList<Transaction> blockTransactions = new ArrayList<>();
                //创建CoinBase交易 放在第一位
                Transaction coinBaseTransaction = Transaction.createCoinBaseTransaction(miner.getAddress());
                blockTransactions.add(coinBaseTransaction);
                blockTransactions.addAll(transactions);
                block.setTransactions(blockTransactions);







            }
        });
        return Result.ok();
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
