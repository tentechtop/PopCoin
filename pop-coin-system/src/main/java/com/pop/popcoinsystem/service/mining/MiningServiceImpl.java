package com.pop.popcoinsystem.service.mining;

import com.pop.popcoinsystem.application.service.wallet.Wallet;
import com.pop.popcoinsystem.application.service.wallet.WalletStorage;
import com.pop.popcoinsystem.data.block.Block;
import com.pop.popcoinsystem.data.block.BlockHeader;
import com.pop.popcoinsystem.data.miner.Miner;
import com.pop.popcoinsystem.service.blockChain.BlockChainServiceImpl;
import com.pop.popcoinsystem.service.blockChain.asyn.SynchronizedBlocksImpl;
import com.pop.popcoinsystem.storage.MiningStorageService;
import com.pop.popcoinsystem.storage.StorageService;
import com.pop.popcoinsystem.data.transaction.Transaction;
import com.pop.popcoinsystem.data.vo.result.Result;
import com.pop.popcoinsystem.util.CryptoUtil;
import com.pop.popcoinsystem.util.DifficultyUtils;
import jakarta.annotation.PreDestroy;
import jcuda.CudaException;
import jcuda.Pointer;
import jcuda.Sizeof;
import jcuda.driver.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static com.pop.popcoinsystem.constant.BlockChainConstants.*;
import static java.lang.Thread.sleep;
import static jcuda.driver.CUresult.CUDA_SUCCESS;
import static jcuda.driver.JCudaDriver.*;

@Slf4j
@Service
public class MiningServiceImpl {

    @Autowired
    private StorageService storageService;
    @Autowired
    private MiningStorageService miningStorageService;

    @Lazy
    @Autowired
    private BlockChainServiceImpl blockChainService;

    @Value("${system.mining-type:1}")
    private int miningType;

    @Value("${system.mining.miner-address:1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa}")
    private String minerAddress;

    // 跟踪当前正在挖矿的区块
    private volatile Block currentMiningBlock;

    // CPU挖矿性能控制（0-100，默认85%）
    private volatile int miningPerformance = 15;
    // GPU挖矿性能控制（0-100，默认100%）
    private volatile int gpuMiningPerformance = 90;
    //是否启动挖矿服务 用于停止挖矿的标志 保证变量的可见性
    volatile public static boolean isMining = false;
    // 资源状态标记（避免重复释放）
    private boolean isCudaInitialized = false;
    private boolean isExecutorsInitialized = false;
    private volatile boolean isResourcesInitialized = false;
    // 当前难度目标（前导零的数量）
    private static long currentDifficulty = 1;
    //交易池
    private final Map<byte[], Transaction> transactions = new ConcurrentHashMap<>();
    private long currentSize = 0;
    private static int threadCount =  Runtime.getRuntime().availableProcessors();
    private static ExecutorService executor;

    // 静态加载的CUDA模块和函数（整个挖矿过程复用）
    private CUmodule ptxModule;
    private CUfunction kernelFunction;
    // 静态缓存的临时PTX文件（整个挖矿过程保持存在）
    private File tempPtxFile;
    private CUcontext cudaContext; // 类成员变量

    @Autowired
    private SynchronizedBlocksImpl syncService; // 同步服务
    // 记录同步前的挖矿状态，用于同步完成后恢复
    private volatile boolean wasMiningBeforeSync = false;
    // 挖矿超时时间（15分钟，单位：毫秒）
    private static final long MINING_TIMEOUT_MS = 15 * 60 * 1000;
    // 用于GPU挖矿 和 CPU挖矿 超时控制的调度器
    private ThreadPoolExecutor miningExecutor;

    /**
     * 启动挖矿
     */
    public Result<String> startMining() throws Exception {
        if (isMining) {
            return Result.error("ERROR: The node is already mining ! ");
        }
        // 首次启动时初始化所有资源（仅执行一次）
        initAllResources();
        log.info("开始初始化挖矿服务...");
        isMining = true;
        miningExecutor.submit(() -> {
            Thread.currentThread().setPriority(Thread.NORM_PRIORITY);//NORM_PRIORITY  MIN_PRIORITY
            while (isMining) {
                try {
                    mineOneBlock();
                } catch (Exception e) {
                    log.error("单区块挖矿异常，将重试", e);
                    try {
                        Thread.sleep(1000); // 异常后短暂休眠避免CPU空转
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        });
        return Result.ok();
    }

    /**
     * 初始化所有静态资源（线程池、CUDA、超时调度器），仅在首次启动时执行
     */
    private void initAllResources() throws Exception {
        if (isResourcesInitialized) {
            log.debug("资源已初始化，无需重复创建");
            return;
        }
        //初始化地址 用于测试
        Miner miner = miningStorageService.getMiner();
        minerAddress = miner.getCoinBaseAddress().getFirst();

        // 初始化区块链（创世区块检查）
        initBlockChain();
        // 初始化线程池
        initMiningExecutor();
        // 若为GPU挖矿，初始化CUDA资源
        if (miningType == 2) {
            initCuda();
        }
        isResourcesInitialized = true; // 标记资源已初始化
    }

    private void mineOneBlock() throws Exception {
        // 1. 等待同步完成（同步时暂停挖矿）
        waitForSyncCompletion();

        // 2. 获取最新区块信息（确保基于最新主链打包）
        Block latestBlock = blockChainService.getMainLatestBlock();
        long newBlockHeight = latestBlock.getHeight() + 1;

        // 3. 从交易池筛选有效交易（高优先级+不超过区块大小限制）
        List<Transaction> selectedTxs = getTransactionsByPriority();

        // 4. 构建新区块（含CoinBase交易、Merkle根等）
        Block newBlock = buildNewBlock(latestBlock, selectedTxs, newBlockHeight);
        // 5. 执行挖矿（CPU/GPU）
        MiningResult result = executeMining(newBlock);
        // 6. 处理挖矿结果（成功则提交区块，失败则重试）
        BlockHeader blockHeader = newBlock.extractHeader();
        newBlock.setMedianTime(storageService.calculateMedianTime(blockHeader,newBlockHeight,newBlock.getHash()));
        handleMiningResult(newBlock, result, selectedTxs);
    }

    /**
     * 处理挖矿结果（成功则提交区块，失败则直接重试）
     */
    private void handleMiningResult(Block newBlock, MiningResult result, List<Transaction> selectedTxs) {
        if (result != null && result.found) {
            // 挖矿成功：提交区块+清理交易池
            newBlock.setNonce(result.nonce);
            newBlock.setHash(result.hash);
            blockChainService.verifyBlock(newBlock, true);
            adjustDifficulty();
            selectedTxs.forEach(tx -> removeTransaction(tx.getTxId()));
            log.info("区块 #{} 挖矿成功", newBlock.getHeight());
        } else {
            // 挖矿失败/超时：直接重试（无需释放资源）
            log.info("区块 #{} 挖矿失败，准备重新打包...", newBlock.getHeight());
        }
    }

    /**
     * 执行挖矿（复用线程池/CUDA资源）
     */
    private MiningResult executeMining(Block newBlock) {
        BlockHeader header = newBlock.extractHeader();
        currentMiningBlock = newBlock; // 跟踪当前挖矿区块
        try {
            if (miningType == 2 && isCudaInitialized) {
                return gpuMineBlock(header); // 复用CUDA上下文/模块
            } else {
                return cpuMineBlock(header); // 复用CPU线程池
            }
        } finally {
            currentMiningBlock = null; // 重置跟踪
        }
    }

    /**
     * 构建新区块（仅依赖最新区块和交易池，无资源操作）
     */
    private Block buildNewBlock(Block latestBlock, List<Transaction> selectedTxs, long newHeight) {
        Block newBlock = new Block();
        // 基础字段（基于最新区块）
        newBlock.setPreviousHash(latestBlock.getHash());
        newBlock.setHeight(newHeight);
        newBlock.setTime(System.currentTimeMillis() / 1000);
        newBlock.setDifficulty(currentDifficulty);
        newBlock.setDifficultyTarget(DifficultyUtils.difficultyToCompact(currentDifficulty));

        // 交易相关（含CoinBase）
        Transaction coinBase = BlockChainServiceImpl.createCoinBaseTransaction(minerAddress, newHeight, calculateTotalFee(selectedTxs));
        List<Transaction> blockTxs = new ArrayList<>(selectedTxs);
        blockTxs.addFirst(coinBase); // CoinBase放在首位
        newBlock.setTransactions(blockTxs);
        newBlock.setTxCount(blockTxs.size());

        // 计算Merkle根（仅在交易变化时重新计算）
        newBlock.calculateAndSetMerkleRoot();

        // 其他字段（链工作、大小等）
        newBlock.setChainWork(DifficultyUtils.add(latestBlock.getChainWork(), currentDifficulty));
        newBlock.calculateAndSetSize();
        newBlock.calculateAndSetWeight();
        return newBlock;
    }

    private long calculateTotalFee(List<Transaction> selectedTxs) {
        long totalFee = 0;
        for (Transaction transaction : selectedTxs) {
            totalFee += blockChainService.getFee(transaction);
        }
        return totalFee;
    }


    /**
     * 等待同步完成（同步时暂停，不释放资源）
     */
    private void waitForSyncCompletion() throws InterruptedException {
        while (syncService.isSyncing()) {
            log.info("区块链同步中，暂停挖矿...");
            Thread.sleep(3000); // 每3秒检查一次
        }
        // 同步完成后，若之前在挖矿，直接恢复（无需重新初始化资源）
        if (wasMiningBeforeSync) {
            log.info("同步完成，恢复挖矿");
            wasMiningBeforeSync = false;
        }
    }


    // 重写同步暂停/恢复方法（仅操作状态，不碰资源）
    public void pauseMiningForSync() {
        if (isMining) {
            wasMiningBeforeSync = true;
            isMining = false; // 仅暂停循环，不释放资源
            log.info("因同步暂停挖矿（资源保持）");
        }
    }

    public void resumeMiningAfterSync() {
        if (wasMiningBeforeSync && !isMining) {
            isMining = true; // 恢复循环
            log.info("同步完成，恢复挖矿");
        }
    }

    public void initBlockChain(){
        Block genesisBlock = blockChainService.getMainBlockByHeight(0);
        if (genesisBlock == null) {
            genesisBlock = Block.createGenesisBlock();
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
        }else {
            // 非首次启动：从最新区块同步难度
            byte[] latestBlockHash = blockChainService.getMainLatestBlockHash();
            Block latestBlock = blockChainService.getBlockByHash(latestBlockHash);
            currentDifficulty = latestBlock.getDifficulty();
            log.info("从最新区块同步难度：{}", currentDifficulty);
        }
    }
    private void initMiningExecutor() {
        if (isExecutorsInitialized) {
            log.debug("线程池已初始化，无需重复创建");
            return;
        }
        if (miningExecutor == null || miningExecutor.isShutdown() || miningExecutor.isTerminated()) {
            // 线程工厂：增加异常处理，明确线程属性
            ThreadFactory threadFactory = r -> {
                Thread thread = new Thread(r, "mining-main-thread");
                thread.setPriority(Thread.NORM_PRIORITY);
                thread.setDaemon(false);
                thread.setUncaughtExceptionHandler((t, e) ->
                        log.error("挖矿线程[" + t.getName() + "]发生未捕获异常", e)
                );
                return thread;
            };

            // 拒绝策略：自定义策略，更贴合挖矿场景
            RejectedExecutionHandler rejectedHandler = (r, executor) -> {
                if (!executor.isShutdown()) {
                    try {
                        log.warn("挖矿线程池忙碌，提交线程将直接执行任务（当前队列已满）");
                        // 让提交任务的线程自己执行，避免任务丢失
                        r.run();
                    } catch (Exception e) {
                        log.error("提交线程执行挖矿任务时发生异常", e);
                    }
                } else {
                    log.warn("挖矿线程池已关闭，无法提交新任务");
                }
            };

            // 线程池参数
            miningExecutor = new ThreadPoolExecutor(
                    1,       // 核心线程数=1（固定单线程）
                    1,                  // 最大线程数=1（禁止扩容，确保串行）
                    0L,                 // 空闲时间=0（核心线程永不回收）
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(5),
                    threadFactory,
                    rejectedHandler
            );

            // 5. 预启动核心线程：避免首次任务的启动延迟
            miningExecutor.prestartCoreThread();
        }
        if (executor == null || executor.isShutdown() || executor.isTerminated()) {
            int corePoolSize = Runtime.getRuntime().availableProcessors(); // CPU核心数
            int maximumPoolSize = corePoolSize * 2; // 固定线程数
            long keepAliveTime = 0L; // 核心线程不超时（因长期运行）
            TimeUnit unit = TimeUnit.MILLISECONDS;
            BlockingQueue<Runnable> workQueue = new LinkedBlockingQueue<>();
            ThreadFactory threadFactory = r -> {
                Thread t = new Thread(r, "mining-thread-" + UUID.randomUUID().toString().substring(0, 8));
                t.setPriority(Thread.NORM_PRIORITY); // 挖矿线程优先级设为正常（避免抢占系统资源）
                t.setDaemon(false); // 非守护线程（确保挖矿可独立运行，不受主线程影响）
                return t;
            };
            RejectedExecutionHandler handler = new ThreadPoolExecutor.CallerRunsPolicy();
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
        isExecutorsInitialized = true; // 标记初始化完成
    }


    private void initCuda(){
        if (isCudaInitialized) {
            log.debug("CUDA已初始化，无需重复创建");
            return;
        }
        try {
            // 初始化CUDA驱动
            JCudaDriver.setExceptionsEnabled(true);
            cuInit(0);

            // 获取GPU设备
            int[] deviceCount = new int[1];
            cuDeviceGetCount(deviceCount);
            if (deviceCount[0] == 0) {
                log.warn("未检测到GPU设备，将使用CPU挖矿");
                return;
            }

            CUdevice device = new CUdevice();
            cuDeviceGet(device, 0);  // 使用第1块GPU

            // 创建上下文
            cudaContext = new CUcontext();
            cuCtxCreate(cudaContext, 0, device);

            // 获取GPU信息
            byte[] name = new byte[256];
            cuDeviceGetName(name, name.length, device);
            log.info("CUDA初始化成功，使用GPU设备: " + new String(name).trim());

            //加载执行文件
            // 1. 加载resources/cuda目录下的PTX文件
            ClassPathResource ptxResource = new ClassPathResource("cuda/miningKernel.ptx");
            if (!ptxResource.exists()) {
                throw new RuntimeException("PTX文件不存在: resources/cuda/miningKernel.ptx");
            }

            // 2. 复制到临时文件（整个挖矿过程中保持存在）
            tempPtxFile = File.createTempFile("miningKernel-", ".ptx");
            tempPtxFile.deleteOnExit(); // JVM退出时自动删除
            try (InputStream is = ptxResource.getInputStream();
                 OutputStream os = Files.newOutputStream(tempPtxFile.toPath())) {
                byte[] buffer = new byte[1024];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    os.write(buffer, 0, bytesRead);
                }
            }
            log.info("PTX文件静态加载到临时路径: {}", tempPtxFile.getAbsolutePath());

            // 3. 加载CUDA模块和函数（缓存到成员变量）
            ptxModule = new CUmodule();
            int loadResult = cuModuleLoad(ptxModule, tempPtxFile.getAbsolutePath());
            if (loadResult != CUDA_SUCCESS) {
                throw new RuntimeException("CUDA模块加载失败，错误码: " + loadResult);
            }
            kernelFunction = new CUfunction();
            int getFuncResult = cuModuleGetFunction(kernelFunction, ptxModule, "findValidNonceGPU");
            if (getFuncResult != 0) {
                throw new RuntimeException("获取CUDA函数失败，错误码: " + getFuncResult);
            }
            log.info("PTX模块和函数静态加载成功");
            isCudaInitialized = true; // 标记初始化完成
        } catch (Exception e) {
            log.error("CUDA初始化失败（可能无GPU或驱动问题）", e);
            log.warn("将自动降级为CPU挖矿");
            cleanCudaResources();
            isCudaInitialized = false;
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
        //是否已经被确认
        if (blockChainService.isTransactionConfirmed(tx.getTxId())) {
            log.warn("Transaction has already been confirmed.");
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
        log.debug("交易 {} 不在交易池中，不需要移除", CryptoUtil.bytesToHex(txId));
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


    //D:\SoftwareSpace\VisualStudio\Community\VC\Tools\MSVC\14.44.35207\include\yvals_core.h
    //C:\Program Files\NVIDIA GPU Computing Toolkit\CUDA\v12.1\include\crt\host_config.h
    // nvcc --version
    // nvcc -ptx miningKernel.cu -o miningKernel.ptx -arch=compute_89 -code=sm_89,compute_89 -allow-unsupported-compiler
    // where nvcc
    // devenv /version
    //nvcc -ptx miningKernel.cu -o miningKernel.ptx -arch=compute_89 -code=sm_89,compute_89 -allow-unsupported-compiler --disable-warnings --diag-suppress 1002

    /**
     * GPU挖矿核心方法（复用静态加载的PTX资源）
     */
    public MiningResult gpuMineBlock(BlockHeader blockHeader) {
        // 检查静态资源是否有效，无效则降级到CPU
        if (cudaContext == null || ptxModule == null || kernelFunction == null) {
            log.warn("CUDA静态资源未初始化，使用CPU挖矿");
            return cpuMineBlock(blockHeader);
        }
        log.info("正在使用GPU挖矿");
        MiningResult result = new MiningResult();
        CUdeviceptr dHeader = null;
        CUdeviceptr dResult = null;
        try {
            // 切换到已初始化的CUDA上下文
            cuCtxSetCurrent(cudaContext);
            // 1. 准备区块头数据（序列化并复制到GPU内存）
            byte[] headerData = Block.serializeBlockHeader(blockHeader);
            if (headerData.length != 80) {
                log.error("区块头序列化错误，长度应为80字节，实际：" + headerData.length);
                return result;
            }
            dHeader = new CUdeviceptr();
            int allocResult = cuMemAlloc(dHeader, headerData.length);
            if (allocResult != CUDA_SUCCESS) {
                log.error("GPU内存分配失败，错误码：" + allocResult);
                return result;
            }
            cuMemcpyHtoD(dHeader, Pointer.to(headerData), headerData.length);

            // 2. 设置挖矿参数（nonce范围）
            int baseBlockSize = 256;// 从256降低，减少并行线程数
            int startNonce = 0;
            int endNonce = Integer.MAX_VALUE;
            int actualGridSize = (int) (1024  * (gpuMiningPerformance / 100.0));// 从1024降低
            actualGridSize = Math.max(1, actualGridSize);

            // 3. 配置内核参数（使用静态加载的kernelFunction）
            Pointer kernelParams = Pointer.to(
                    Pointer.to(dHeader),
                    Pointer.to(new int[]{startNonce}),
                    Pointer.to(new int[]{endNonce})
            );

            // 4. 启动GPU内核（复用静态函数）
            int launchResult = cuLaunchKernel(
                    kernelFunction,
                    actualGridSize, 1, 1,
                    baseBlockSize, 1, 1,
                    0, null,
                    kernelParams, null
            );
            if (launchResult != CUDA_SUCCESS) {
                log.error("GPU内核启动失败，错误码：" + launchResult);
                return result;
            }
            // 等待内核执行完成
            cuCtxSynchronize();
            log.info("GPU内核执行完成");
            // 5. 读取挖矿结果
            dResult = getGlobalVariable(ptxModule, "devResult");
            byte[] resultBuffer = new byte[40]; // 结构体大小：4+4+32=40字节
            cuMemcpyDtoH(Pointer.to(resultBuffer), dResult, 40);
            // 解析结果
            int found = ByteBuffer.wrap(resultBuffer, 0, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            int nonce = ByteBuffer.wrap(resultBuffer, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            byte[] hash = new byte[32];
            System.arraycopy(resultBuffer, 8, hash, 0, 32);
            result.found = (found == 1);
            result.nonce = nonce;
            result.hash = hash;
            if (result.found) {
                log.info("GPU找到有效哈希！nonce={}, hash={}", nonce, CryptoUtil.bytesToHex(hash));
            } else {
                log.info("GPU未找到有效哈希，最后尝试nonce={}", nonce);
            }
            return result;
        } catch (Exception e) {
            log.error("GPU挖矿异常", e);
            return cpuMineBlock(blockHeader); // 异常时降级到CPU
        } finally {
            // 释放本次挖矿的临时资源（保留静态资源）
            if (dHeader != null) {
                cuMemFree(dHeader);
            }
        }
    }

    /**
     * 打包交易，进行挖矿
     */
    public MiningResult cpuMineBlock(BlockHeader blockHeader) {
        MiningResult result = new MiningResult();
        Future<?>[] futures = new Future[threadCount];
        int nonceRange = Integer.MAX_VALUE / threadCount;
        // 重置结果状态
        result.found = false;
        // 提交所有线程任务
        for (int i = 0; i < threadCount; i++) {
            BlockHeader clone = blockHeader.clone();
            final int startNonce = i * nonceRange;
            final int endNonce = (i == threadCount - 1) ? Integer.MAX_VALUE : (i + 1) * nonceRange;
            futures[i] = executor.submit(() -> {
                byte[] difficultyTarget = clone.getDifficultyTarget();
                try {
                    for (int nonce = startNonce; nonce < endNonce && !result.found; nonce++) {
                        // 每5000次计算执行一次概率休眠
                        if (nonce % 1000 == 0 && miningPerformance < 100) {
                            // 性能控制：计算休眠概率（性能越低，休眠概率越高）
                            double sleepProbability = (100.0 - miningPerformance) / 100.0;
                            // 生成0-1之间的随机数，判断是否需要休眠
                            if (Math.random() < sleepProbability) {
                                try {
                                    // 休眠时长：性能越低，基础休眠时间越长（50ms ~ 200ms）
                                    // 性能为0时休眠200ms，性能100时休眠0ms（实际不会进入此分支）
                                    long sleepMs = (long) (200 - (miningPerformance * 1.5));
                                    sleepMs = Math.max(50, sleepMs); // 确保最小休眠50ms，避免频繁切换
                                    Thread.sleep(sleepMs);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt(); // 保留中断状态
                                    return; // 中断时退出当前线程的挖矿任务
                                }
                            }
                        }
                        clone.setNonce(nonce);
                        byte[] hash = clone.computeHash();
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


    /**
     * 辅助方法：获取CUDA全局变量地址
     */
    private CUdeviceptr getGlobalVariable(CUmodule module, String name) {
        CUdeviceptr ptr = new CUdeviceptr();
        long[] size = new long[1];
        try {
            cuModuleGetGlobal(ptr, size, module, name);
            return ptr;
        }catch (Exception e){
            log.error("获取全局变量失败", e);
        }
        return null;
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


    // 提供setter方法供外部调整
    public void setGpuMiningPerformance(int performance) {
        if (performance < 0 || performance > 100) {
            throw new IllegalArgumentException("GPU性能参数必须在0-100之间");
        }
        this.gpuMiningPerformance = performance;
    }


    /**
     * 退出时释放所有资源
     * @return
     * @throws Exception
     */
    @PreDestroy
    public Result<String> stopMining() throws Exception {
        log.info("开始释放所有挖矿资源...");
        try {
            // 1. 停止所有线程池（先停止任务，避免资源被占用）
            releaseExecutors();

            // 2. 释放CUDA相关资源（计算资源依赖线程池已停止）
            cleanCudaResources();

            // 3. 重置资源状态标记
            resetResourceFlags();

            log.info("所有挖矿资源释放完成");
            return Result.ok("已停止挖矿并释放所有资源");
        } catch (Exception e) {
            log.error("资源释放过程中发生异常", e);
            return Result.error("资源释放异常：" + e.getMessage());
        }
    }

    /**
     * 释放线程池资源（包括executor、miningExecutor）
     */
    private void releaseExecutors() {
        log.info("开始释放线程池资源...");
        // 释放CPU挖矿线程池
        if (executor != null && !executor.isTerminated()) {
            executor.shutdownNow(); // 立即中断所有任务
            try {
                // 等待最多10秒，确保线程退出
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    log.warn("CPU挖矿线程池未能完全终止，可能存在资源泄露");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("CPU挖矿线程池释放被中断");
            }
            executor = null;
        }

        // 释放主挖矿线程池
        if (miningExecutor != null && !miningExecutor.isTerminated()) {
            miningExecutor.shutdownNow();
            try {
                if (!miningExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    log.warn("主挖矿线程池未能完全终止");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("主挖矿线程池释放被中断");
            }
            miningExecutor = null;
        }
        isExecutorsInitialized = false;
        log.info("线程池资源释放完成");
    }

    /**
     * 释放CUDA相关资源（严格按依赖顺序释放）
     * 释放顺序：内核函数 → 模块 → 上下文 → 临时文件
     */
    private void cleanCudaResources() {
        //释放内核函数（无显式释放方法，随模块释放）
        kernelFunction = null;
        // 2. 卸载CUDA模块（必须在上下文销毁前）
        if (ptxModule != null) {
            try {
                cuModuleUnload(ptxModule);
                log.debug("CUDA模块卸载成功");
            } catch (CudaException e) {
                log.error("CUDA模块卸载失败", e);
            } finally {
                ptxModule = null;
            }
        }

        // 3. 销毁CUDA上下文（必须在模块卸载后）
        if (cudaContext != null) {
            try {
                cuCtxDestroy(cudaContext);
                log.debug("CUDA上下文销毁成功");
            } catch (CudaException e) {
                log.error("CUDA上下文销毁失败", e);
            } finally {
                cudaContext = null;
            }
        }

        // 4. 删除PTX临时文件（确保文件存在且未被占用）
        if (tempPtxFile != null && tempPtxFile.exists()) {
            try {
                if (tempPtxFile.delete()) {
                    log.debug("PTX临时文件删除成功：{}", tempPtxFile.getAbsolutePath());
                } else {
                    log.warn("PTX临时文件删除失败，将依赖JVM退出时自动清理：{}", tempPtxFile.getAbsolutePath());
                }
            } catch (Exception e) {
                log.error("删除PTX临时文件时发生异常", e);
            } finally {
                tempPtxFile = null;
            }
        }
        isCudaInitialized = false;
        log.info("CUDA资源释放完成");
    }

    /**
     * 重置资源状态标记（释放后确保状态一致）
     */
    private void resetResourceFlags() {
        isMining = false;
        currentMiningBlock = null;
        isCudaInitialized = false;
        isExecutorsInitialized = false;
    }
}
