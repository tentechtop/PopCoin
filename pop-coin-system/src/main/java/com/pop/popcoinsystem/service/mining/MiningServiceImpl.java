package com.pop.popcoinsystem.service.mining;

import com.pop.popcoinsystem.data.block.Block;
import com.pop.popcoinsystem.data.block.BlockHeader;
import com.pop.popcoinsystem.data.miner.Miner;
import com.pop.popcoinsystem.service.blockChain.BlockChainServiceImpl;
import com.pop.popcoinsystem.service.blockChain.asyn.SynchronizedBlocksImpl;
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

import static com.pop.popcoinsystem.constant.BlockChainConstants.*;
import static java.lang.Thread.sleep;
import static jcuda.driver.CUresult.CUDA_SUCCESS;
import static jcuda.driver.JCudaDriver.*;

@Slf4j
@Service
public class MiningServiceImpl {

    @Autowired
    private StorageService storageService;

    @Lazy
    @Autowired
    private BlockChainServiceImpl blockChainService;

    // CPU挖矿性能控制（0-100，默认85%）
    private volatile int miningPerformance = 15;
    // GPU挖矿性能控制（0-100，默认100%）
    private volatile int gpuMiningPerformance = 80;

    //矿工信息
    volatile public Miner miner;
    // 当前难度目标（前导零的数量）
    private static long currentDifficulty = 1;
    //是否启动挖矿服务 用于停止挖矿的标志
    public static boolean isMining = false;
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
    private SynchronizedBlocksImpl syncService; // 同步服务，需自行实现
    // 记录同步前的挖矿状态，用于同步完成后恢复
    private volatile boolean wasMiningBeforeSync = false;



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
        initCuda();

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
                // 关键：每次循环都检查是否正在同步，若同步则等待
                while (syncService.isSyncing()) {
                    log.info("检测到区块链正在同步，暂停挖矿等待同步完成...");
                    try {
                        Thread.sleep(3000); // 每3秒检查一次同步状态
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
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
                Transaction coinBaseTransaction = BlockChainServiceImpl.createCoinBaseTransaction(miner.getAddress(), blockHeight+1, totalFee);
                log.info("创建CoinBase交易 矿工地址 : {}", miner.getAddress());
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
                BlockHeader blockHeader = newBlock.extractHeader();
                MiningResult result = gpuMineBlock(blockHeader);
                if (result != null && result.found) {
                    newBlock.setNonce(result.nonce);
                    newBlock.setHash(result.hash);
                    adjustDifficulty();
                    for (Transaction tx : transactions) {
                        // 挖矿成功：移除已打包的交易
                        removeTransaction(tx.getTxId());
                    }
                    //将区块提交到区块链
                    log.info("提交区块到主链...{}", CryptoUtil.bytesToHex(newBlock.getHash()));
                    blockChainService.verifyBlock(newBlock,true);
                } else {
                    log.info("区块 #" + newBlock.getHeight() + " 挖矿失败，重新生成区块并打包...");
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
            int maximumPoolSize = corePoolSize * 1; // 固定线程数
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

    private void initCuda(){
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
        } catch (Exception e) {
            log.error("CUDA初始化失败（可能无GPU或驱动问题）", e);
            log.warn("将自动降级为CPU挖矿");
            cleanCudaResources();
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
            int baseBlockSize = 256;
            int baseBatchNonce = 1_000_000;
            int batchNonce = (int) (baseBatchNonce * (gpuMiningPerformance / 100.0));
            batchNonce = Math.max(1000, batchNonce);
            int startNonce = 0;
            int endNonce = Integer.MAX_VALUE;
            int actualGridSize = (int) (1024 * (gpuMiningPerformance / 100.0));
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
     * 清理CUDA资源（用于初始化失败时）
     */
    private void cleanCudaResources() {
        if (ptxModule != null) {
            try {
                cuModuleUnload(ptxModule);
            } catch (Exception e) {
                log.error("卸载CUDA模块失败", e);
            }
            ptxModule = null;
        }
        if (cudaContext != null) {
            try {
                cuCtxDestroy(cudaContext);
            } catch (Exception e) {
                log.error("销毁CUDA上下文失败", e);
            }
            cudaContext = null;
        }
        if (tempPtxFile != null && tempPtxFile.exists()) {
            try {
                Files.delete(tempPtxFile.toPath());
            } catch (Exception e) {
                log.error("删除PTX临时文件失败", e);
            }
            tempPtxFile = null;
        }
        kernelFunction = null;
    }


    /**
     * 辅助方法：获取CUDA全局变量地址
     */
    private CUdeviceptr getGlobalVariable(CUmodule module, String name) {
        CUdeviceptr ptr = new CUdeviceptr();
        long[] size = new long[1];
        cuModuleGetGlobal(ptr, size, module, name);
        return ptr;
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


    /**
     * 因同步暂停挖矿，记录原状态
     */
    public void pauseMiningForSync() {
        if (isMining) {
            wasMiningBeforeSync = true;
            try {
                stopMining(); // 调用已有的停止挖矿方法
                log.info("因区块链同步，已暂停挖矿");
            } catch (Exception e) {
                log.error("同步时停止挖矿失败", e);
            }
        }
    }

    /**
     * 同步完成后恢复挖矿（若同步前在挖矿）
     */
    public void resumeMiningAfterSync() {
        if (wasMiningBeforeSync && !isMining) {
            try {
                startMining(); // 调用已有的启动挖矿方法
                log.info("区块链同步完成，已恢复挖矿");
                wasMiningBeforeSync = false; // 重置状态
            } catch (Exception e) {
                log.error("同步后恢复挖矿失败", e);
            }
        }
    }

    // 提供setter方法供外部调整
    public void setGpuMiningPerformance(int performance) {
        if (performance < 0 || performance > 100) {
            throw new IllegalArgumentException("GPU性能参数必须在0-100之间");
        }
        this.gpuMiningPerformance = performance;
    }

    /**
     * 程序退出时释放所有静态资源
     */
    @PreDestroy
    public void destroyCuda() {
        // 停止挖矿
        if (isMining) {
            try {
                stopMining();
            } catch (Exception e) {
                log.error("停止挖矿失败", e);
            }
        }

        // 关闭线程池
        if (executor != null) {
            executor.shutdownNow();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.warn("线程池未能及时终止");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            executor = null;
        }

        // 释放CUDA静态资源
        cleanCudaResources();
        log.info("CUDA静态资源已全部释放");
    }
}
