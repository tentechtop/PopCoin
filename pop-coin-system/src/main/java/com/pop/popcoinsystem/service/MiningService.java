package com.pop.popcoinsystem.service;

import com.pop.popcoinsystem.data.block.Block;
import com.pop.popcoinsystem.data.miner.Miner;
import com.pop.popcoinsystem.data.storage.POPStorage;
import com.pop.popcoinsystem.data.transaction.TXInput;
import com.pop.popcoinsystem.data.transaction.TXOutput;
import com.pop.popcoinsystem.data.transaction.Transaction;
import com.pop.popcoinsystem.data.transaction.UTXO;
import com.pop.popcoinsystem.data.vo.result.Result;
import com.pop.popcoinsystem.util.BeanCopyUtils;
import com.pop.popcoinsystem.util.CryptoUtil;
import com.pop.popcoinsystem.util.DifficultyUtils;
import com.pop.popcoinsystem.util.SerializeUtils;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;




/**
 * 打包交易服务
 * 有交易时 每分钟出一个区块 无交易时则提示难度也提升挖矿奖励 最高提高到60分钟出一个区块
 *
 *
 */
@Slf4j
@Service
public class MiningService {

    private final BlockChainService blockChainService;

    // 挖矿性能控制（0-100，默认85%）
    private volatile int miningPerformance = 30;
    // CPU保护机制相关变量
    private volatile double lastCpuLoad = 0;
    private static final long CPU_MONITOR_INTERVAL = 5000; // 5秒检测一次
    private long lastCpuCheckTime = 0;

    private final Queue<Double> cpuLoadHistory = new LinkedList<>();
    private static final int CPU_HISTORY_SIZE = 5; // 保存最近5次的CPU负载数据

    // CPU负载阈值配置
    private static final double HIGH_CPU_THRESHOLD = 80.0; // 高负载阈值
    private static final double MEDIUM_CPU_THRESHOLD = 60.0; // 中等负载阈值
    private static final double LOW_CPU_THRESHOLD = 40.0; // 低负载阈值

    // 性能调整步长
    private static final int PERFORMANCE_ADJUST_STEP = 5; // 每次调整5%
    private static final int MAX_PERFORMANCE = 100;
    private static final int MIN_PERFORMANCE = 30; // 最低保留30%性能，避免过度限制
    private static final int BASE_SLEEP = 1; // 基础休眠时间(ms)
    private static final int MAX_SLEEP = 50; // 最大休眠时间(ms)



    //矿工信息
    public static Miner miner;
    // 当前难度目标（前导零的数量）
    private static long currentDifficulty = 1;
    // 最大区块奖励（延迟10分钟时的奖励）
    public static final long INITIAL_REWARD = 50;
    //单位 1e8
    public static final long BLOCK_REWARD_UNIT = 100000000;


    //是否启动挖矿服务 用于停止挖矿的标志
    public static boolean isMining = false;
    // 难度调整周期的区块数量
    private static final int DIFFICULTY_ADJUSTMENT_INTERVAL = 24;//2016大概两周调整一次  144一天调整一次 2小时
    //区块生成时间
    private static final long BLOCK_GENERATION_TIME = 60; //600是600秒 10分钟
    //货币总供应量
    private static final long MONEY_SUPPLY = 2100000000;
    //减半周期
    public static final int HALVING_PERIOD = 21000000;
    //时间窗口大小
    public static final int TIME_WINDOW_SIZE = 11;
    //交易列表大小 1M
    public static final int MAX_TRANSACTION_SIZE = 1024 * 1024;




    /**
     * 交易池  最大300M
     */
    private static final long MAX_SIZE_BYTES = 300 * 1024 * 1024; // 300MB
    //交易池
    private final Map<byte[], Transaction> transactions = new LinkedHashMap<>();
    private long currentSize = 0;
    private static int threadCount =  Runtime.getRuntime().availableProcessors();
    private static ExecutorService executor = Executors.newFixedThreadPool(threadCount);


    public MiningService (BlockChainService blockChainService) {
        this.blockChainService = blockChainService;
    }

    @PostConstruct
    public void init() {
        //初始化难度
        byte[] mainLatestBlockHash = blockChainService.getMainLatestBlockHash();
        Block blockByHash = blockChainService.getBlockByHash(mainLatestBlockHash);
        currentDifficulty = blockByHash.getDifficulty();
        log.info("当前难度: {}", currentDifficulty);
        initExecutor();
    }

    private void initExecutor() {
        if (executor == null || executor.isShutdown() || executor.isTerminated()) {
            executor = Executors.newFixedThreadPool(threadCount);
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
            Thread.currentThread().setPriority(Thread.NORM_PRIORITY);//NORM_PRIORITY  MIN_PRIORITY
            while (isMining) {
                List<Transaction> transactions = getTransactionsByPriority();
                if (transactions.isEmpty()) {
                    log.info("没有可用的交易");
                }
                //获取主链最新的区块hash 和 区块高度
                byte[] latestBlockHash = blockChainService.getMainLatestBlockHash();
                log.info("最新区块Hash:"+CryptoUtil.bytesToHex(latestBlockHash));
                long blockHeight = blockChainService.getMainLatestHeight();
                log.info("最新区块高度: {}", blockHeight);
                Block latestBlock = blockChainService.getMainBlockByHeight(blockHeight);

                Block newBlock = new Block();
                newBlock.setPreviousHash(latestBlockHash);
                newBlock.setHeight(blockHeight+1);
                newBlock.setTime(System.currentTimeMillis());
                ArrayList<Transaction> blockTransactions = new ArrayList<>();

                //计算所有交易手续费 输入 = 输出+手续费
                long totalFee = 0;
                for (Transaction transaction : transactions) {
                    totalFee += blockChainService.getFee(transaction);
                }
                //创建CoinBase交易 放在第一位
                Transaction coinBaseTransaction = Transaction.createCoinBaseTransaction(miner.getAddress(), blockHeight+1, totalFee);

                blockTransactions.add(coinBaseTransaction);
                blockTransactions.addAll(transactions);
                newBlock.setTransactions(blockTransactions);
                newBlock.calculateAndSetMerkleRoot();
                newBlock.setTime(System.currentTimeMillis() /1000);
                newBlock.setDifficulty(currentDifficulty);
                newBlock.setDifficultyTarget(DifficultyUtils.difficultyToCompact(currentDifficulty));
                long medianTime = calculateMedianTime(TIME_WINDOW_SIZE);
                newBlock.setMedianTime(medianTime);

                //  //表示该区块之前的区块链总工作量，以十六进制表示。它反映了整个区块链的挖矿工作量。
                //    private byte[] chainWork;
                //计算工作总量
                byte[] chainWork = latestBlock.getChainWork();
                byte[] add = DifficultyUtils.add(chainWork, currentDifficulty);
                newBlock.setChainWork(add);

                newBlock.calculateAndSetSize();
                newBlock.calculateAndSetWeight();

                //挖矿奖励：通过 coinbase 交易嵌入区块体
                //每个区块的第一笔交易是coinbase 交易（特殊交易，无输入），其输出部分直接包含矿工的挖矿奖励。例如：
                //比特币区块的 coinbase 交易输出会包含 “基础奖励 + 区块内所有交易的手续费总和”，这笔输出会被记录在区块体的交易列表中。
                //区块只需存储这笔交易，就能通过交易验证逻辑自动计算出矿工获得的总奖励（无需额外字段）。
                //手续费：隐含在普通交易的 “输入 - 输出差额” 中
                //普通交易中，输入金额总和 - 输出金额总和 = 手续费，这部分差额由打包该交易的矿工获得。
                //例如：用户发起一笔交易，输入 10 个代币，输出 9.9 个代币，差额 0.1 个代币即为手续费。这部分无需单独记录，通过遍历区块内所有交易的输入输出即可计算。

                log.info("\n开始挖矿新区块 #" + newBlock.getHeight() +
                        " (难度: " + newBlock.getDifficulty() + ", 交易数: " + transactions.size() + ", 手续费: "+ totalFee+  ")");

                MiningResult result = mineBlock(newBlock);
                if (result != null && result.found) {
                    newBlock.setNonce(result.nonce);
                    newBlock.setHash(result.hash);
                    adjustDifficulty();
                    // 挖矿成功：移除已打包的交易
                    // 挖矿成功：移除已打包的交易
                    for (Transaction tx : transactions) {
                        removeTransaction(tx.getTxId()); // 直接传入byte[]类型的txId
                    }
                    //将区块提交到区块链
                    blockChainService.verifyBlock(newBlock);
                } else {
                    // 未找到有效哈希，将交易放回交易池（避免丢失）
                    log.info("区块 #" + newBlock.getHeight() + " 挖矿失败，重新尝试...");
                    // 挖矿失败：延迟重试（例如3秒），减少CPU占用
                    try {
                        Thread.sleep(1000); // 1秒后重试
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break; // 中断时退出循环
                    }
                }
            }
        }).start();
        return Result.ok();
    }




    /**
     * 计算当前主链的中位数时间
     * @param windowSize 时间窗口大小（建议为奇数，如11）
     * @return 中位数时间（单位与区块时间戳一致，如秒）
     */
    public long calculateMedianTime(int windowSize) {
        // 1. 校验窗口大小（必须为正整数，建议奇数）
        if (windowSize <= 0) {
            throw new IllegalArgumentException("窗口大小必须为正整数");
        }

        // 2. 获取主链最新区块高度
        long latestHeight = blockChainService.getMainLatestHeight();
        if (latestHeight < windowSize - 1) {
            // 区块数量不足窗口大小，直接返回最新区块时间
            return blockChainService.getMainLatestBlock().getTime();
        }

        // 3. 提取最近windowSize个主链区块的时间戳
        List<Long> timestamps = new ArrayList<>(windowSize);
        for (int i = 0; i < windowSize; i++) {
            long height = latestHeight - i;
            Block block = blockChainService.getMainBlockByHeight(height);
            if (block != null) {
                timestamps.add(block.getTime());
            }
        }

        // 4. 排序并取中位数
        Collections.sort(timestamps);
        int medianIndex = windowSize / 2; // 对于11个元素，索引为5（0~10）
        return timestamps.get(medianIndex);
    }












    /**
     * CPU保护机制 - 动态监控CPU负载并调整挖矿性能
     */
    private void monitorCpuLoad() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastCpuCheckTime > CPU_MONITOR_INTERVAL) {
            double cpuLoad = getSmoothedCpuLoad();
            lastCpuCheckTime = currentTime;
            // 多级调整策略
            if (cpuLoad > HIGH_CPU_THRESHOLD) {
                // 高负载：快速降低性能
                adjustPerformance(-PERFORMANCE_ADJUST_STEP * 2);
            } else if (cpuLoad > MEDIUM_CPU_THRESHOLD) {
                // 中等负载：缓慢降低性能
                adjustPerformance(-PERFORMANCE_ADJUST_STEP);
                log.info("CPU中等负载({}%)，降低挖矿性能至{}%",
                        String.format("%.1f", cpuLoad), miningPerformance);
            } else if (cpuLoad < LOW_CPU_THRESHOLD) {
                // 低负载：逐步恢复性能
                adjustPerformance(PERFORMANCE_ADJUST_STEP);
            }
        }
    }

    /**
     * 获取平滑后的CPU负载值（使用移动平均）
     */
    private double getSmoothedCpuLoad() {
        double currentLoad = getSystemCpuLoad();
        // 维护历史记录队列
        if (cpuLoadHistory.size() >= CPU_HISTORY_SIZE) {
            cpuLoadHistory.poll();
        }
        cpuLoadHistory.add(currentLoad);
        // 计算平均值
        double sum = 0;
        for (double load : cpuLoadHistory) {
            sum += load;
        }
        return sum / cpuLoadHistory.size();
    }

    /**
     * 调整挖矿性能
     * @param adjustment 调整量（正值增加性能，负值降低性能）
     */
    private synchronized void adjustPerformance(int adjustment) {
        int newPerformance = miningPerformance + adjustment;
        newPerformance = Math.max(MIN_PERFORMANCE, Math.min(MAX_PERFORMANCE, newPerformance));
        if (newPerformance != miningPerformance) {
            miningPerformance = newPerformance;
            log.info("挖矿性能调整为: {}%", miningPerformance);
        }
    }


    /**
     * 获取系统CPU负载
     */
    private double getSystemCpuLoad() {
        try {
            OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
            if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
                return ((com.sun.management.OperatingSystemMXBean) osBean).getCpuLoad() * 100;
            }
            return osBean.getSystemLoadAverage();
        } catch (Exception e) {
            log.error("获取CPU负载失败", e);
            return 0.0;
        }
    }


    /**
     * 设置挖矿性能
     * @param performance 0-100（百分比）
     */
    public synchronized Result<String> setMiningPerformance(int performance) {
        if (performance >= 0 && performance <= 100) {
            this.miningPerformance = performance;
            log.info("挖矿性能设置为: {}%", performance);
            return Result.ok("挖矿性能已设置为: " + performance + "%");
        } else {
            log.error("无效的性能值: {}", performance);
            return Result.error("性能值必须在0-100之间");
        }
    }


    /**
     * 获取挖矿状态
     */
    public Map<String, Object> getMiningStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("isMining", isMining);
        status.put("performance", miningPerformance + "%");
        status.put("threads", threadCount);
        status.put("cpuLoad", String.format("%.1f%%", lastCpuLoad));
        status.put("difficulty", currentDifficulty);
        status.put("transactionPoolSize", transactions.size());
        status.put("transactionPoolUsage", String.format("%.1f%%", (currentSize * 100.0 / MAX_SIZE_BYTES)));
        return status;
    }





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
        double targetFeePerByte = blockChainService.getFeePerByte(tx);

        byte[] lowestPriorityTxId = null;
        double lowestFeePerByte = Double.MAX_VALUE;
        // 2. 遍历交易池，筛选出手续费率低于基准的交易
        for (Map.Entry<byte[], Transaction> entry : transactions.entrySet()) {
            Transaction existingTx = entry.getValue();
            double existingFeePerByte = blockChainService.getFeePerByte(existingTx);
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



    // 按每字节手续费从高到低获取交易列表
    public synchronized List<Transaction> getTransactionsByPriority() {
        List<Transaction> txList = new ArrayList<>(transactions.values());
        // 1. 先按手续费率从高到低排序
        txList.sort((tx1, tx2) -> Double.compare(blockChainService.getFeePerByte(tx2), blockChainService.getFeePerByte(tx1)));
        // 2. 再筛选总大小不超过1MB的交易
        List<Transaction> selectedTxs = new ArrayList<>();
        long totalSize = 0;
        for (Transaction tx : txList) {
            if (totalSize + tx.getSize() > MAX_TRANSACTION_SIZE) {
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
    public synchronized boolean removeTransaction(byte[] txId) {
        Transaction tx = transactions.remove(txId);
        if (tx != null) {
            currentSize -= tx.getSize();
            log.info("交易 {} 已从交易池移除", CryptoUtil.bytesToHex(txId));
            return true;
        }
        log.warn("交易 {} 不在交易池中，移除失败", CryptoUtil.bytesToHex(txId));
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
    public MiningResult mineBlock(Block block) {
        MiningResult result = new MiningResult();
        Future<?>[] futures = new Future[threadCount];
        int nonceRange = Integer.MAX_VALUE / threadCount;

        // 重置结果状态
        result.found = false;

        // 提前计算性能控制参数（避免每次循环都计算）
        final boolean needsThrottling = miningPerformance < 100;
        final double baseSleepProbability = needsThrottling ? (1.0 - miningPerformance / 100.0) : 0.0;
        final double targetLoad = needsThrottling ? (50 + miningPerformance * 0.3) : 0.0;

        // 负载控制状态（每N次计算更新一次）
        class ThrottleState {
            double sleepProbability = 0.2; // 默认中等概率
            int sleepMillis = 2;           // 默认2ms休眠
            long lastUpdateTime = System.currentTimeMillis();
            int updateInterval = 1000;     // 更新频率（每1000次计算）

            // 每N次计算更新一次负载控制参数
            void updateIfNeeded(int nonce) {
                if (nonce % updateInterval != 0) return;

                // 紧急保护：如果CPU负载超过90%，启用最大休眠
                if (lastCpuLoad > 95) {
                    sleepProbability = 0.8;
                    sleepMillis = 5 + (int) ((lastCpuLoad - 90) * 0.5);
                    return;
                }

                // 每5秒动态调整更新频率（负载高时增加采样频率）
                long now = System.currentTimeMillis();
                if (now - lastUpdateTime > 5000) {
                    updateInterval = Math.max(500, Math.min(5000, (int) (2000 * (100 - lastCpuLoad) / 60)));
                    lastUpdateTime = now;
                }

                // 动态调整休眠参数（简化版）
                double loadDiff = lastCpuLoad - targetLoad;
                double adjustmentFactor = 1.0;

                if (loadDiff > 10) {
                    adjustmentFactor = 1.0 + Math.min(0.2, loadDiff / 50); // 最多增加20%休眠
                } else if (loadDiff < -10) {
                    adjustmentFactor = Math.max(0.8, 0.8 - loadDiff / 100); // 最多减少20%休眠
                }

                // 最终休眠概率（限制在合理范围）
                sleepProbability = Math.min(0.6, Math.max(0.1, baseSleepProbability * adjustmentFactor));

                // 动态休眠时间（负载越高，休眠越长）
                sleepMillis = 2 + Math.min(3, Math.max(0, (int) ((lastCpuLoad - 40) / 20)));
            }
        }

        // 每个线程独立的负载控制状态
        ThreadLocal<ThrottleState> throttleState = ThreadLocal.withInitial(ThrottleState::new);

        // 提交所有线程任务
        for (int i = 0; i < threadCount; i++) {
            final int startNonce = i * nonceRange;
            final int endNonce = (i == threadCount - 1) ? Integer.MAX_VALUE : (i + 1) * nonceRange;
            futures[i] = executor.submit(() -> {
                byte[] difficultyTarget = block.getDifficultyTarget();
                try {
                    for (int nonce = startNonce; nonce < endNonce && !result.found; nonce++) {
                        // 每50次计算检查一次负载控制（减少检查频率）
                        if (needsThrottling && nonce % 50 == 0) {
                            ThrottleState state = throttleState.get();
                            state.updateIfNeeded(nonce);

                            // 执行概率性休眠
                            if (Math.random() < state.sleepProbability) {
                                Thread.sleep(state.sleepMillis);
                            }
                        }

                        block.setNonce(nonce);
                        byte[] hash = block.computeBlockHash(block);
                        if (DifficultyUtils.isValidHash(hash, difficultyTarget)) {
                            synchronized (result) {
                                if (!result.found) {
                                    result.hash = hash;
                                    result.nonce = nonce;
                                    result.found = true;
                                    log.info("线程 " + Thread.currentThread().getName() + " 找到有效哈希!");
                                }
                            }
                            return;
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    log.error("线程 " + Thread.currentThread().getName() + " 计算哈希时异常", e);
                    Thread.currentThread().interrupt();
                }
            });
        }

        // 等待所有任务完成或找到结果（保持原有逻辑不变）
        try {
            boolean allCompleted = false;
            while (!allCompleted && !result.found) {
                allCompleted = true;
                for (Future<?> future : futures) {
                    if (future != null && !future.isDone()) {
                        allCompleted = false;
                        Thread.sleep(100);
                        break;
                    }
                }
            }

            for (Future<?> future : futures) {
                if (future != null && !future.isDone()) {
                    future.cancel(true);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            for (Future<?> future : futures) {
                if (future != null) {
                    future.cancel(true);
                }
            }
        }

        return result.found ? result : null;
    }






    private void adjustDifficulty() {

        long blockHeight = blockChainService.getMainLatestHeight();
        if ((blockHeight + 1) % DIFFICULTY_ADJUSTMENT_INTERVAL != 0) return;
        if (blockHeight < DIFFICULTY_ADJUSTMENT_INTERVAL - 1) return;

        Block firstBlock = blockChainService.getMainBlockByHeight(blockHeight - (DIFFICULTY_ADJUSTMENT_INTERVAL - 1));
        Block latestBlock = blockChainService.getMainBlockByHeight(blockHeight);
        if (latestBlock == null) {
            log.error("难度调整失败：未找到高度{}的主链区块", blockHeight);
            return; // 或使用默认难度
        }
        log.info("本轮难度调整起始区块时间: " + firstBlock.getTime());
        log.info("本轮难度调整最新区块时间: " + latestBlock.getTime());

        long actualTimeTaken = latestBlock.getTime() - firstBlock.getTime();
        // 防止除零错误
        if (actualTimeTaken <= 0) actualTimeTaken = 1;

        long targetTime = DIFFICULTY_ADJUSTMENT_INTERVAL * BLOCK_GENERATION_TIME; //600秒 10分钟

        log.info("\n难度调整:");
        log.info("\n目标总时间: " + targetTime + "秒");//预计时间
        log.info("\n实际" + DIFFICULTY_ADJUSTMENT_INTERVAL + "个区块总生成时间: " + actualTimeTaken + "秒");
        log.info("\n目标平均生成时间: "+BLOCK_GENERATION_TIME+"秒");
        log.info("\n实际平均生成时间: " + (double) actualTimeTaken / DIFFICULTY_ADJUSTMENT_INTERVAL + "秒");

        // 修正方向：目标时间/实际时间
        double factor = (double) targetTime / actualTimeTaken;
        factor = Math.max(0.25, Math.min(4.0, factor));  // 保持限制范围

        long newDifficulty = (long) (currentDifficulty * factor);
        newDifficulty = Math.max(1L, newDifficulty);

        log.info("\n难度调整因子: " + factor);
        log.info("\n旧难度值: " + currentDifficulty);
        log.info("\n新难度值: " + newDifficulty);

        currentDifficulty = newDifficulty;
    }

    public Map<byte[], Transaction> getTransactionPool() {
        return transactions;
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





/*    public Result<String> startMiningTest() throws Exception {
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
        log.info("开始挖矿:Starting mining...");
        // 初始化线程池
        executor = Executors.newFixedThreadPool(threadCount);
        new Thread(() -> {
            Thread.currentThread().setPriority(Thread.NORM_PRIORITY);//NORM_PRIORITY  MIN_PRIORITY
            while (isMining) {
                monitorCpuLoad();
                List<Transaction> transactions = getTransactionsByPriority();
                if (transactions.isEmpty()) {
                    log.info("No transactions available for mining.");
                }
                //获取主链最新的区块hash 和 区块高度
                byte[] latestBlockHash = blockChainService.getMainLatestBlockHash();
                log.info("最新区块Hash:"+CryptoUtil.bytesToHex(latestBlockHash));
                long blockHeight = blockChainService.getMainLatestHeight();
                log.info("最新区块Hash: {}", blockHeight);
                Block latestBlock = blockChainService.getMainBlockByHeight(blockHeight);


                Block newBlock = new Block();
                newBlock.setPreviousHash(latestBlockHash);
                newBlock.setHeight(blockHeight+1);
                newBlock.setTime(System.currentTimeMillis());
                ArrayList<Transaction> blockTransactions = new ArrayList<>();
                //创建CoinBase交易 放在第一位
                Transaction coinBaseTransaction = Transaction.createCoinBaseTransaction(miner.getAddress(),blockHeight+1);
                blockTransactions.add(coinBaseTransaction);
                blockTransactions.addAll(transactions);
                newBlock.setTransactions(blockTransactions);
                newBlock.calculateAndSetMerkleRoot();
                newBlock.setTime(System.currentTimeMillis() /1000 );
                newBlock.setDifficulty(currentDifficulty);
                newBlock.setDifficultyTarget(DifficultyUtils.difficultyToCompact(currentDifficulty));

                //  //表示该区块之前的区块链总工作量，以十六进制表示。它反映了整个区块链的挖矿工作量。
                //    private byte[] chainWork;
                //计算工作总量
                byte[] chainWork = latestBlock.getChainWork();
                byte[] add = DifficultyUtils.add(chainWork, currentDifficulty);
                newBlock.setChainWork(add);

                //挖矿奖励：通过 coinbase 交易嵌入区块体
                //每个区块的第一笔交易是coinbase 交易（特殊交易，无输入），其输出部分直接包含矿工的挖矿奖励。例如：
                //比特币区块的 coinbase 交易输出会包含 “基础奖励 + 区块内所有交易的手续费总和”，这笔输出会被记录在区块体的交易列表中。
                //区块只需存储这笔交易，就能通过交易验证逻辑自动计算出矿工获得的总奖励（无需额外字段）。
                //手续费：隐含在普通交易的 “输入 - 输出差额” 中
                //普通交易中，输入金额总和 - 输出金额总和 = 手续费，这部分差额由打包该交易的矿工获得。
                //例如：用户发起一笔交易，输入 10 个代币，输出 9.9 个代币，差额 0.1 个代币即为手续费。这部分无需单独记录，通过遍历区块内所有交易的输入输出即可计算。
                System.out.println("\n开始挖矿新区块 #" + newBlock.getHeight() +
                        " (难度: " + newBlock.getDifficulty() + ", 交易数: " + transactions.size());
                MiningResult result = mineBlock(newBlock);
                MiningResult result2 = mineBlock(newBlock);
                MiningResult result3 = mineBlock(newBlock);

                if (result != null && result.found) {
                    newBlock.setNonce(result.nonce);
                    newBlock.setHash(result.hash);

                    Block block = BeanCopyUtils.copyObject(newBlock, Block.class);
                    block.setNonce(result2.nonce);
                    block.setHash(result2.hash);


                    byte[] mainBlockHashByHeight = blockChainService.getMainBlockHashByHeight(newBlock.getHeight() - 10);
                    if (mainBlockHashByHeight != null){
                        //制造非延续冲突
                        log.info("非延续冲突:{}",CryptoUtil.bytesToHex(mainBlockHashByHeight));
                        Block blockByHash = blockChainService.getBlockByHash(mainBlockHashByHeight);
                        byte[] chainWork1 = blockByHash.getChainWork();
                        byte[] add1 = DifficultyUtils.add(chainWork1, currentDifficulty);
                        Block block3 = BeanCopyUtils.copyObject(newBlock, Block.class);
                        block3.setHeight(newBlock.getHeight()-9);
                        block3.setPreviousHash(mainBlockHashByHeight);
                        block3.setNonce(result3.nonce);
                        block3.setHash(result3.hash);
                        block3.setChainWork(add1);
                        blockChainService.verifyBlock(block3);
                    }

                    adjustDifficulty();
                    // 挖矿成功：移除已打包的交易
                    for (Transaction tx : transactions) {
                        removeTransaction(CryptoUtil.bytesToHex(tx.getTxId()));
                    }
                    //将区块提交到区块链
                    blockChainService.verifyBlock(newBlock);

                    blockChainService.verifyBlock(block);
                } else {
                    // 未找到有效哈希，将交易放回交易池（避免丢失）
                    log.info("区块 #" + newBlock.getHeight() + " 挖矿失败，重新尝试...");
                    // 挖矿失败：延迟重试（例如3秒），减少CPU占用
                    try {
                        Thread.sleep(1000); // 1秒后重试
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break; // 中断时退出循环
                    }
                }
            }
        }).start();
        return Result.ok();
    }*/
}
