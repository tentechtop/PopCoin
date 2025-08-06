package com.pop.popcoinsystem.service.mining;

import com.pop.popcoinsystem.data.block.Block;
import com.pop.popcoinsystem.data.miner.Miner;
import com.pop.popcoinsystem.service.blockChain.BlockChainServiceImpl;
import com.pop.popcoinsystem.storage.StorageService;
import com.pop.popcoinsystem.data.transaction.Transaction;
import com.pop.popcoinsystem.data.vo.result.Result;
import com.pop.popcoinsystem.util.CryptoUtil;
import com.pop.popcoinsystem.util.DifficultyUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;

import static com.pop.popcoinsystem.constant.BlockChainConstants.*;
import static java.lang.Thread.sleep;

@Slf4j
@Service
public class MiningServiceImpl {

    @Autowired
    private StorageService storageService;

    @Lazy
    @Autowired
    private BlockChainServiceImpl blockChainService;

    // 挖矿性能控制（0-100，默认85%）
    private volatile int miningPerformance = 30;
    // CPU保护机制相关变量
    private volatile double lastCpuLoad = 0;

    //矿工信息
    public static Miner miner;
    // 当前难度目标（前导零的数量）
    private static long currentDifficulty = 1;

    //是否启动挖矿服务 用于停止挖矿的标志
    public static boolean isMining = false;
    //交易池
    private final Map<byte[], Transaction> transactions = new ConcurrentHashMap<>();
    private long currentSize = 0;
    private static int threadCount =  Runtime.getRuntime().availableProcessors();
    private static ExecutorService executor = Executors.newFixedThreadPool(threadCount);

    /**
     * 启动挖矿
     */
    public Result<String> startMining() throws Exception {
        if (isMining) {
            return Result.error("ERROR: The node is already mining ! ");
        }
        log.info("开始初始化挖矿服务...");
        initBlockChain();
        byte[] mainLatestBlockHash = blockChainService.getMainLatestBlockHash();
        Block block = blockChainService.getBlockByHash(mainLatestBlockHash);
        // 初始化成功
        log.info("最新区块: {}", block);
        log.info("最新区块高度: {}", block.getHeight());
        currentDifficulty = block.getDifficulty();
        log.info("当前难度值: {}", block.getDifficulty());
        log.info("最新区块难度目标: {}", CryptoUtil.bytesToHex(block.getDifficultyTarget()));
        log.info("最新区块难度: {}", currentDifficulty);
        initExecutor();
        //获取矿工信息
        miner = storageService.getMiner();
        log.info("本节点矿工信息: {}", miner);
        if (miner == null) {
            //挖矿前请设置本节点的矿工信息
            throw new RuntimeException("请设置本节点的矿工信息");
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
                long blockHeight = blockChainService.getMainLatestHeight();
                log.info("最新区块Hash: {} 最新区块高度: {}",CryptoUtil.bytesToHex(latestBlockHash),blockHeight);
                Block latestBlock = blockChainService.getMainBlockByHeight(blockHeight);
                Block newBlock = new Block();
                newBlock.setPreviousHash(latestBlockHash);
                newBlock.setHeight(blockHeight+1);
                newBlock.setTime(System.currentTimeMillis());
                ArrayList<Transaction> blockTransactions = new ArrayList<>();
                long totalFee = 0;
                for (Transaction transaction : transactions) {
                    totalFee += blockChainService.getFee(transaction);
                }
                Transaction coinBaseTransaction = blockChainService.createCoinBaseTransaction(miner.getAddress(), blockHeight+1, totalFee);
                blockTransactions.add(coinBaseTransaction);
                blockTransactions.addAll(transactions);
                newBlock.setTransactions(blockTransactions);
                newBlock.calculateAndSetMerkleRoot();
                newBlock.setTime(System.currentTimeMillis() /1000);
                newBlock.setDifficulty(currentDifficulty);
                newBlock.setDifficultyTarget(DifficultyUtils.difficultyToCompact(currentDifficulty));
                long medianTime = blockChainService.calculateMedianTime();
                newBlock.setMedianTime(medianTime);
                byte[] chainWork = latestBlock.getChainWork();
                byte[] add = DifficultyUtils.add(chainWork, currentDifficulty);
                newBlock.setChainWork(add);
                newBlock.calculateAndSetSize();
                newBlock.calculateAndSetWeight();
                newBlock.setTxCount(blockTransactions.size());
                newBlock.setWitnessSize(newBlock.calculateWitnessSize());
                log.info("\n开始挖矿新区块 #" + newBlock.getHeight() +
                        " (难度: " + newBlock.getDifficulty() + ", 交易数: " + transactions.size() + ", 手续费: "+ totalFee+  ")");
                MiningResult result = mineBlock(newBlock);
                if (result != null && result.found) {
                    newBlock.setNonce(result.nonce);
                    newBlock.setHash(result.hash);
                    adjustDifficulty();
                    for (Transaction tx : transactions) {
                        // 挖矿成功：移除已打包的交易
                        removeTransaction(tx.getTxId());
                    }
                    //将区块提交到区块链
                    blockChainService.verifyBlock(newBlock,true);
                } else {
                    log.info("区块 #" + newBlock.getHeight() + " 挖矿失败，重新生成区块并打包...");

                    try {
                        sleep(1000); // 1秒后重试
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break; // 中断时退出循环
                    }
                }
            }
        }).start();
        return Result.ok();
    }



    public void initBlockChain(){
        Block genesisBlock = blockChainService.getMainBlockByHeight(0);
        if (genesisBlock == null) {
            genesisBlock = blockChainService.createGenesisBlock();
            // 寻找符合难度的nonce
            log.info("开始挖掘创世区块（难度目标：前4字节为0）...");
            int nonce = 0;
            byte[] validHash = null;
            while (true) {
                genesisBlock.setNonce(nonce);
                // 计算区块哈希
                byte[] blockHash = genesisBlock.computeHash();
                if (DifficultyUtils.isValidHash(blockHash, DifficultyUtils.difficultyToCompact(1L))) {
                    validHash = blockHash;
                    log.info("创世区块挖掘成功！nonce={}, 哈希={}",
                            nonce, CryptoUtil.bytesToHex(blockHash));
                    break;
                }
                // 防止无限循环（实际可根据需求调整最大尝试次数）
                if (nonce % 100000 == 0) {
                    log.debug("已尝试{}次，继续寻找有效nonce...", nonce);
                }
                nonce++;
                // 安全限制：最多尝试1亿次（防止极端情况）
                if (nonce >= 100_000_000_0) {
                    throw new RuntimeException("创世区块挖矿超时，未找到有效nonce");
                }
            }
            // 9. 设置计算得到的哈希和nonce
            genesisBlock.setHash(validHash);
            genesisBlock.setNonce(nonce);
            //保存区块
            storageService.addBlock(genesisBlock);
            //保存最新的区块hash
            storageService.updateMainLatestBlockHash(validHash);
            //最新区块高度
            storageService.updateMainLatestHeight(genesisBlock.getHeight());
            //保存主链中 高度高度到 hash的索引
            storageService.addMainHeightToBlockIndex(genesisBlock.getHeight(), validHash);
            blockChainService.applyBlock(genesisBlock);
        }
    }
    private void initExecutor() {
        if (executor == null || executor.isShutdown() || executor.isTerminated()) {
            int corePoolSize = Runtime.getRuntime().availableProcessors(); // CPU核心数
            int maximumPoolSize = corePoolSize; // 固定线程数
            long keepAliveTime = 0L; // 核心线程不超时（因长期运行）
            TimeUnit unit = TimeUnit.MILLISECONDS;
            BlockingQueue<Runnable> workQueue = new LinkedBlockingQueue<>(); // 无界队列，缓存待执行的挖矿任务
            ThreadFactory threadFactory = r -> {
                Thread t = new Thread(r, "mining-thread-" + UUID.randomUUID().toString().substring(0, 8));
                t.setPriority(Thread.NORM_PRIORITY); // 挖矿线程优先级设为正常（避免抢占系统资源）
                t.setDaemon(false); // 非守护线程（确保挖矿可独立运行，不受主线程影响）
                return t;
            };
            RejectedExecutionHandler handler = new ThreadPoolExecutor.CallerRunsPolicy(); // 任务满时让提交者（主线程）执行，避免任务丢失
            executor = new ThreadPoolExecutor(
                    corePoolSize,
                    maximumPoolSize,
                    keepAliveTime,
                    unit,
                    workQueue,
                    threadFactory,
                    handler
            );
        }
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
        log.warn("交易 {} 不在交易池中，不需要移除", CryptoUtil.bytesToHex(txId));
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
                                sleep(state.sleepMillis);
                            }
                        }

                        block.setNonce(nonce);
                        byte[] hash = block.computeHash();
                        if ( DifficultyUtils.isValidHash(hash, difficultyTarget)) {
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
                        sleep(100);
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



        // 修正方向：目标时间/实际时间
        double factor = (double) targetTime / actualTimeTaken;
        factor = Math.max(0.25, Math.min(4.0, factor));  // 保持限制范围
        long newDifficulty = (long) (currentDifficulty * factor);
        newDifficulty = Math.max(1L, newDifficulty);

        log.info("\n难度调整:" +
                "\n目标总时间: " +  targetTime + "秒" +
                "\n实际" + DIFFICULTY_ADJUSTMENT_INTERVAL + "个区块总生成时间: " + actualTimeTaken + "秒" +
                "\n目标平均生成时间: "+BLOCK_GENERATION_TIME+"秒" +
                "\n实际平均生成时间: " + (double) actualTimeTaken / DIFFICULTY_ADJUSTMENT_INTERVAL + "秒"+
                "\n难度调整因子: " + factor +
                "\n旧难度值: " + currentDifficulty+"" +
                "\n新难度值: " + newDifficulty);
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
}
