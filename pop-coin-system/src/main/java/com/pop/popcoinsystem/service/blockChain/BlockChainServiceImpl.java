package com.pop.popcoinsystem.service.blockChain;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.pop.popcoinsystem.data.block.Block;
import com.pop.popcoinsystem.data.block.BlockBody;
import com.pop.popcoinsystem.data.block.BlockDTO;
import com.pop.popcoinsystem.data.block.BlockHeader;
import com.pop.popcoinsystem.data.blockChain.BlockChain;
import com.pop.popcoinsystem.data.script.*;
import com.pop.popcoinsystem.exception.UnsupportedAddressException;
import com.pop.popcoinsystem.network.common.ExternalNodeInfo;
import com.pop.popcoinsystem.network.common.NodeInfo;
import com.pop.popcoinsystem.network.protocol.message.HandshakeResponseMessage;
import com.pop.popcoinsystem.network.protocol.messageData.Handshake;
import com.pop.popcoinsystem.network.rpc.RpcProxyFactory;
import com.pop.popcoinsystem.service.blockChain.asyn.SyncRequest;
import com.pop.popcoinsystem.service.blockChain.asyn.SyncResponse;
import com.pop.popcoinsystem.service.blockChain.asyn.SynchronizedBlocksImpl;
import com.pop.popcoinsystem.service.mining.MiningServiceImpl;
import com.pop.popcoinsystem.service.blockChain.strategy.ScriptVerificationStrategy;
import com.pop.popcoinsystem.service.blockChain.strategy.ScriptVerifierFactory;
import com.pop.popcoinsystem.storage.StorageService;
import com.pop.popcoinsystem.data.transaction.UTXOSearch;
import com.pop.popcoinsystem.data.transaction.*;
import com.pop.popcoinsystem.data.transaction.dto.TXInputDTO;
import com.pop.popcoinsystem.data.transaction.dto.TXOutputDTO;
import com.pop.popcoinsystem.data.transaction.dto.TransactionDTO;
import com.pop.popcoinsystem.data.transaction.dto.WitnessDTO;
import com.pop.popcoinsystem.data.vo.result.TPageResult;
import com.pop.popcoinsystem.data.vo.result.Result;
import com.pop.popcoinsystem.data.vo.result.ListPageResult;
import com.pop.popcoinsystem.network.service.KademliaNodeServer;
import com.pop.popcoinsystem.network.protocol.message.BlockMessage;
import com.pop.popcoinsystem.network.protocol.message.TransactionMessage;
import com.pop.popcoinsystem.util.*;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.net.ConnectException;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;

import static com.pop.popcoinsystem.constant.BlockChainConstants.TRANSACTION_VERSION_1;
import static com.pop.popcoinsystem.constant.BlockChainConstants.*;
import static com.pop.popcoinsystem.data.transaction.Transaction.isCoinBaseTransaction;
import static com.pop.popcoinsystem.storage.StorageService.getUTXOKey;
import static com.pop.popcoinsystem.data.transaction.Transaction.calculateBlockReward;
import static com.pop.popcoinsystem.util.CryptoUtil.ECDSASigner.getLockingScriptByAddress;

@Slf4j
@Service
public class BlockChainServiceImpl implements BlockChainService {

    @Autowired
    private StorageService popStorage;
    @Autowired
    private KademliaNodeServer kademliaNodeServer;
    @Autowired
    private MiningServiceImpl mining;

    @Lazy
    @Autowired
    private SynchronizedBlocksImpl blockSynchronizer; // 复用异步同步器
    @PostConstruct
    private void initBlockChain() {
        Block genesisBlock = getMainBlockByHeight(0);
        if (genesisBlock == null) {
            genesisBlock = Block.createGenesisBlock();
            // 寻找符合难度的nonce
            int nonce = 27746;
/*            while (true) {
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
            }*/
            // 9. 设置计算得到的哈希和nonce
            genesisBlock.setNonce(nonce);
            byte[] validHash = genesisBlock.computeHash();
            if (DifficultyUtils.isValidHash(validHash, DifficultyUtils.difficultyToCompact(1L))) {
                log.info("创世区块初始化成功！nonce={}, 哈希={}",
                        nonce, CryptoUtil.bytesToHex(validHash));
                genesisBlock.setHash(validHash);
                //保存区块
                popStorage.addBlock(genesisBlock);
                //保存最新的区块hash
                popStorage.updateMainLatestBlockHash(validHash);
                //最新区块高度
                popStorage.updateMainLatestHeight(genesisBlock.getHeight());
                //保存主链中 高度高度到 hash的索引
                popStorage.addMainHeightToBlockIndex(genesisBlock.getHeight(), validHash);
                applyBlock(genesisBlock);
            }else {
                log.error("创世区块初始化失败！nonce={}, 哈希={}",
                        nonce, CryptoUtil.bytesToHex(validHash));
                popStorage.updateMainLatestHeight(-1);
                popStorage.updateMainLatestBlockHash(GENESIS_PREV_BLOCK_HASH);
            }
        }
    }



    //孤儿区块池  key是父区块hash   value是一个 hashMap
    private final Cache<byte[], ConcurrentHashMap<byte[],Block>> orphanBlocks = CacheBuilder.newBuilder()
            .maximumSize(1000) // 最大缓存1000个区块（防止内存溢出）
            .expireAfterWrite(30, TimeUnit.MINUTES) // 写入后30秒自动过期（无需手动清理）
            .concurrencyLevel(Runtime.getRuntime().availableProcessors()) // 并发级别（默认4，可设为CPU核心数）
            .build();

    /**
     * 验证交易
     */
    @Override
    synchronized public boolean verifyTransaction(Transaction transaction) {
        // 基础验证
        if (!validateTransactionBasics(transaction)) {
            return false;
        }
        // 金额验证
        if (!validateTransactionAmounts(transaction)) {
            return false;
        }
        // 交易ID验证
        if (!validateTransactionId(transaction)) {
            return false;
        }
        // UTXO验证与缓存
        Map<String, UTXO> utxoMap = new HashMap<>();
        if (!validateAndCacheUTXOs(transaction, utxoMap)) {
            return false;
        }
        //验证交易的输入是否合法
        if (!validateTransactionAuthorization(transaction, utxoMap)) {
            return false;
        }
        // 验证交易权重
        if (transaction.getWeight() > MAX_BLOCK_WEIGHT) {
            log.error("SegWit交易重量超过限制");
            return false;
        }
        //是否双花 已经存在于区块中
        if (isDoubleSpend(transaction)) {
            log.error("交易已经存在");
            return false;
        }
        // 验证SegWit交易ID
        byte[] wtxId = transaction.getWtxId();
        byte[] calculatedWtxId = transaction.calculateWtxId();
        if (!Arrays.equals(wtxId, calculatedWtxId)) {
            log.error("SegWit交易wtxid不匹配");
            return false;
        }
        return true;
    }


    /**
     * 验证区块
     * UTXO 并非仅在交易验证成功后产生，而是在交易被成功打包进区块并经过网络确认后，才成为有效的 UTXO。
     */
    @Override
    synchronized  public boolean verifyBlock(Block block, boolean broadcastMessage) {
        byte[] hash = block.getHash();
        BlockHeader blockHeader = block.extractHeader();
        byte[] bytes = blockHeader.computeHash();
        if (!Arrays.equals(bytes, hash)) {
            log.warn("区块头哈希不匹配，计算出的哈希：{}", CryptoUtil.bytesToHex(bytes));
            return false;
        }
        // 验证区块合法性
        if (!validateBlock(block)) {
            log.warn("区块验证失败，哈希：{}", CryptoUtil.bytesToHex(block.getHash()));
            return false;
        }
        if (!block.validatePoW()){
            log.warn("区块 PoW 验证失败，哈希：{}", CryptoUtil.bytesToHex(block.getHash()));
            return false;
        }
        // 检查是否是已知区块
        if (getBlockByHash(block.getHash()) != null) {
            log.info("区块已存在，哈希：{}", CryptoUtil.bytesToHex(block.getHash()));
            return true;
        }
        //验证中位置时间

        // 防止未来时间（允许超前最多2小时）
        // 注意：block.getTime() 是秒级时间戳，需转换为毫秒后再比较
        long maxAllowedTime = (System.currentTimeMillis()/1000) + (2 * 60 * 60);
        long blockTimeInMillis = block.getTime();
        if (blockTimeInMillis > maxAllowedTime) {
            log.warn("区块时间戳超前过多，区块时间（秒）：{}，当前系统时间：{}，允许的最大时间：{}",
                    block.getTime(), System.currentTimeMillis(), maxAllowedTime);
            return false;
        }
        Block parentBlock = getBlockByHash(block.getPreviousHash());
        if (block.getHeight() != 0){
            if (parentBlock == null) {
                // 父区块不存在，加入孤儿区块池
                byte[] parentHash = block.getPreviousHash();
                addOrphanBlock(parentHash, block);
                log.info("父区块不存在，加入孤儿池: 区块哈希={}, 父哈希={}",
                        CryptoUtil.bytesToHex(block.getHash()),
                        CryptoUtil.bytesToHex(parentHash));
                return false;
            }
            if (parentBlock.getHeight() + 1 != block.getHeight()) {
                log.warn("区块高度不连续，父区块高度：{}，当前区块高度：{}", parentBlock.getHeight(), block.getHeight());
                return false;
            }
        }
        if (!validateMedianTime(block)){
            log.warn("中位置时间验证失败，中位置时间：{}", block.getMedianTime());
            return false;
        }
        // 验证区块中的交易
        if (!validateTransactionsInBlock(block)) {
            log.warn("区块中的交易验证失败，哈希：{}", CryptoUtil.bytesToHex(block.getHash()));
            return false;
        }
        // 处理区块（保存、更新UTXO等）  只要区块通过验证就 保存区块 保存区块产生的UTXO
        processValidBlock(block);
        //广播区块
        if (broadcastMessage){
            if (kademliaNodeServer.isRunning()){
                log.info("区块验证成功,广播区块");
                BlockMessage blockMessage = new BlockMessage();
                blockMessage.setSender(kademliaNodeServer.getNodeInfo());
                blockMessage.setData(block);
                kademliaNodeServer.broadcastMessage(blockMessage,kademliaNodeServer.getNodeInfo());
            }
        }
        return true;
    }


    /**
     * 将孤儿区块添加到孤儿池
     * @param parentHash 父区块哈希
     * @param block 孤儿区块
     */
    private void addOrphanBlock(byte[] parentHash, Block block) {
        try {
            // 获取父哈希对应的子区块映射，不存在则创建
            ConcurrentHashMap<byte[], Block> childBlocks = orphanBlocks.get(parentHash,
                    ConcurrentHashMap::new);
            // 存储区块（用自身哈希作为key）
            childBlocks.put(block.getHash(), block);
            log.info("添加孤儿区块: 哈希={}, 父哈希={}, 当前孤儿池大小={}",
                    CryptoUtil.bytesToHex(block.getHash()),
                    CryptoUtil.bytesToHex(parentHash),
                    orphanBlocks.size());
        } catch (ExecutionException e) {
            log.error("添加孤儿区块失败", e);
        }
    }

    /**
     * 从孤儿池获取指定父哈希的所有孤儿区块
     * @param parentHash 父区块哈希
     * @return 孤儿区块列表（可能为空）
     */
    private List<Block> getOrphanBlocksByParentHash(byte[] parentHash) {
        ConcurrentHashMap<byte[], Block> childBlocks = orphanBlocks.getIfPresent(parentHash);
        if (childBlocks == null || childBlocks.isEmpty()) {
            return Collections.emptyList();
        }
        // 返回副本以避免并发修改问题
        return new ArrayList<>(childBlocks.values());
    }

    /**
     * 从孤儿池移除指定父哈希的所有孤儿区块
     * @param parentHash 父区块哈希
     */
    private void removeOrphanBlocksByParentHash(byte[] parentHash) {
        orphanBlocks.invalidate(parentHash);
        log.info("移除父哈希={}的所有孤儿区块", CryptoUtil.bytesToHex(parentHash));
    }



    private boolean isDoubleSpend(Transaction transaction) {
        byte[] blockHashByTxId = popStorage.getBlockHashByTxId(transaction.getTxId());
        return blockHashByTxId != null;
    }

    private boolean validateTransactionAuthorization(Transaction transaction, Map<String, UTXO> utxoMap) {
        List<TXInput> inputs = transaction.getInputs();
        // 逐个验证输入的见证数据
        for (int i = 0; i < inputs.size(); i++) {
            TXInput input = inputs.get(i);
            // 获取当前输入引用的UTXO
            UTXO utxo = utxoMap.get(getUTXOKey(input.getTxId(), input.getVout()));
            if (utxo == null) {
                log.error("SegWit输入引用的UTXO不存在");
                return false;
            }
            // 验证ScriptPubKey与见证数据  验证输入是否合法
            boolean verifyResult = verifyScriptPubKey(transaction,input,i,utxo);
            if (!verifyResult) {
                log.error("交易: {} 输入 {} 的见证验证失败", CryptoUtil.bytesToHex(transaction.getTxId()), i);
                return false;
            }
        }
        return true;
    }

    private boolean verifyScriptPubKey(Transaction tx, TXInput input,int inputIndex,UTXO utxo) {
        ScriptPubKey scriptPubKey = utxo.getScriptPubKey();
        int type = scriptPubKey.getType();//解锁脚本的类型
        // 1. 通过工厂获取对应脚本类型的验证策略
        ScriptVerificationStrategy verifier = ScriptVerifierFactory.getVerifier(ScriptType.valueOf(type));
        boolean verify = verifier.verify(tx, input, inputIndex, utxo);
        if (!verify) {
            log.error("解锁脚本验证失败");
            return false;
        }
        return true;
    }

    /**
     * 验证交易基础格式
     */
    private boolean validateTransactionBasics(Transaction transaction) {
        if (transaction == null || transaction.getInputs() == null || transaction.getOutputs() == null) {
            log.error("交易格式无效");
            return false;
        }
        return true;
    }

    /**
     * 验证交易金额
     */
    private boolean validateTransactionAmounts(Transaction transaction) {
        boolean isCoinBase = isCoinBaseTransaction(transaction);
        long inputSum = calculateInputSum(transaction);
        long outputSum = calculateOutputSum(transaction);
        log.info("交易输入金额:{}", inputSum);
        log.info("交易输出金额:{}", outputSum);
        if (inputSum < outputSum) {
            log.error("交易输出金额大于输入金额");
            return false;
        }
        // 非CoinBase交易检查最低输出金额
        if (!isCoinBase) {
            for (TXOutput output : transaction.getOutputs()) {
                if (output.getValue() < MIN_TRANSACTION_OUTPUT_AMOUNT) {
                    log.error("交易输出金额低于最低限制（{}聪），当前值: {}聪",
                            MIN_TRANSACTION_OUTPUT_AMOUNT, output.getValue());
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 计算交易输入总金额
     */
    private long calculateInputSum(Transaction transaction) {
        return transaction.getInputs().stream()
                .map(input -> {
                    UTXO utxo = getUTXO(input.getTxId(), input.getVout());
                    return utxo != null ? utxo.getValue() : 0;
                })
                .mapToLong(Long::longValue)
                .sum();
    }

    /**
     * 计算交易输出总金额
     */
    private long calculateOutputSum(Transaction transaction) {
        return transaction.getOutputs().stream()
                .mapToLong(TXOutput::getValue)
                .sum();
    }

    /**
     * 验证交易ID
     */
    private boolean validateTransactionId(Transaction transaction) {
        Transaction copy = transaction.copy();
        byte[] originalTxId = copy.getTxId();
        byte[] calculatedTxId = copy.calculateTxId();
        log.info("原始交易ID:{}", CryptoUtil.bytesToHex(originalTxId));
        log.info("验证时交易ID:{}", CryptoUtil.bytesToHex(calculatedTxId));
        if (!Arrays.equals(calculatedTxId, originalTxId)) {
            log.error("交易ID不匹配");
            return false;
        }
        return true;
    }

    /**
     * 验证并缓存UTXOs
     */
    private boolean validateAndCacheUTXOs(Transaction transaction, Map<String, UTXO> utxoMap) {
        for (TXInput input : transaction.getInputs()) {
            String utxoKey = getUTXOKey(input.getTxId(), input.getVout());
            UTXO utxo = getUTXO(input.getTxId(), input.getVout());
            utxoMap.put(utxoKey, utxo);
            if (utxo == null) {
                log.error("输入的UTXO不存在或已花费");
                return false;
            }
            // 验证成熟度要求
            if (!utxoIsMature(utxo)) {
                log.error("输入的UTXO未成熟");
                return false;
            }
        }
        return true;
    }






    /**
     * 验证交易并提交到交易池
     * 交易验证成功后 广播交易 如果本节点是矿工节点 则再添加到交易池 由矿工打包
     */
    @Override
    synchronized public boolean verifyAndAddTradingPool(Transaction transaction, boolean broadcastMessage) {
        log.info("您的交易已提交,正在验证交易...");
        boolean b = verifyTransaction(transaction);
        if ( !b){
            return false;
        }
        byte[] blockHashByTxId = popStorage.getBlockHashByTxId(transaction.getTxId());
        if (blockHashByTxId == null){
            //防止双花
            mining.addTransaction(transaction);
        }
        if (kademliaNodeServer.isRunning()){
            log.info("交易验证成功,广播交易");
            TransactionMessage transactionKademliaMessage = new TransactionMessage();
            transactionKademliaMessage.setSender(kademliaNodeServer.getNodeInfo());
            transactionKademliaMessage.setData(transaction);
            kademliaNodeServer.broadcastMessage(transactionKademliaMessage,kademliaNodeServer.getNodeInfo());
        }
        return true;
    }

    /**
     * 验证区块的中位时间是否符合协议要求
     */
    private boolean validateMedianTime(Block block) {
        long blockHeight = block.getHeight();
        byte[] currentBlockHash = block.getHash();
        // 1. 确定实际窗口大小：最多11个，不足则取现有全部祖先
        int actualWindowSize = (int) Math.min(TIME_WINDOW_SIZE, blockHeight);
        if (actualWindowSize == 0) {
            // 创世区块（高度0）无祖先，中位时间等于自身时间
            return block.getMedianTime() == block.getTime();
        }
        // 2. 收集前N个祖先区块的时间戳（从父区块开始，主链追溯）
        List<Long> ancestorTimestamps = new ArrayList<>(actualWindowSize);
        Block currentAncestor = getBlockByHash(block.getPreviousHash()); // 父区块（主链）
        for (int i = 0; i < actualWindowSize && currentAncestor != null; i++) {
            long blockTime = currentAncestor.getTime();
            if (blockTime > 0) { // 过滤无效时间戳
                ancestorTimestamps.add(blockTime);
            }
            // 继续追溯上一个主链祖先（通过父哈希确保主链）
            currentAncestor = getBlockByHash(currentAncestor.getPreviousHash());
        }
        // 3. 处理收集结果（不足11个时基于现有数据计算）
        if (ancestorTimestamps.isEmpty()) {
            log.error("未收集到任何有效祖先时间戳，区块高度：{}", blockHeight);
            return false;
        }
        log.debug("实际收集到{}个有效祖先时间戳（目标：{}）", ancestorTimestamps.size(), actualWindowSize);
        // 4. 计算中位数（与calculateMedianTime逻辑一致）
        Collections.sort(ancestorTimestamps);
        long calculatedMedian = ancestorTimestamps.get(ancestorTimestamps.size() / 2);
        // 5. 验证区块记录的中位时间是否匹配
        if (block.getMedianTime() != calculatedMedian) {
            log.error("中位时间不匹配，区块记录：{}，计算结果：{}，区块哈希：{}",
                    block.getMedianTime(), calculatedMedian, CryptoUtil.bytesToHex(currentBlockHash));
            return false;
        }
        // 6. 验证区块自身时间不早于中位时间（协议要求）
        if (block.getTime() < calculatedMedian) {
            log.error("区块时间戳早于中位时间，区块时间：{}，中位时间：{}，哈希：{}",
                    block.getTime(), calculatedMedian, CryptoUtil.bytesToHex(currentBlockHash));
            return false;
        }
        return true;
    }

    @Override
    public boolean verifyBlockHeader(BlockHeader blockHeader) {
        byte[] bytes = blockHeader.computeHash();

        return false;
    }

    private boolean utxoIsMature(UTXO utxo) {
        byte[] txId = utxo.getTxId();
        Block blockByTxId = popStorage.getBlockByTxId(txId);
        if (blockByTxId == null){
            log.info("未找到交易对应的区块");
            return false;
        }
        long utxoBlockHeight = blockByTxId.getHeight();
        long currentHeight = getMainLatestHeight();
       Transaction transaction =  getBlockTransactionByTxId(txId);
        if (isCoinBaseTransaction(transaction)) {
            // CoinBase交易产生的UTXO需100个确认
            return (currentHeight - utxoBlockHeight) >= COINBASE_MATURITY;
        } else {
            // 普通交易产生的UTXO需6个确认
            return (currentHeight - utxoBlockHeight) >= CONFIRMATIONS;
        }
    }

    private Transaction getBlockTransactionByTxId(byte[] txId) {
        Block block = popStorage.getBlockByTxId(txId);
        if (block == null) {
            log.info("未找到交易对应的区块");
            return null;
        }
        List<Transaction> transactions = block.getTransactions();
        for (Transaction transaction : transactions) {
            if (Arrays.equals(transaction.getTxId(), txId)) {
                return transaction;
            }
        }
        return null;
    }


    /**
     * 验证CoinBase交易 奖励等于 区块奖励+手续费
     * @param tx CoinBase交易
     * @param blockHeight 区块高度
     * @param list 区块中除CoinBase的其他交易
     */
    private boolean isValidCoinBaseTransaction(Transaction tx, long blockHeight,List<Transaction> list) {
        boolean coinBaseTransaction = isCoinBaseTransaction(tx);
        if (!coinBaseTransaction) {
            log.info("交易不是CoinBase交易");
            return false;
        }
        long baseReward = calculateBlockReward(blockHeight);
        log.info("区块高度: {}, 基础奖励: {} 聪", blockHeight, baseReward);
        //手续费由其他交易决定
        long feeAmount = calculateTotalFeesInBlock(list);
        log.info("手续费{}", feeAmount);
        long totalReward = baseReward + feeAmount;
        long totalOutput = tx.getOutputs().stream()
                .mapToLong(TXOutput::getValue)
                .sum();
        if (totalOutput > totalReward) {
            log.info("交易金额错误,正确的区块奖励应该是: " + totalReward + " 聪 (基础奖励: " + baseReward + " 聪 + 手续费: " + feeAmount + " 聪)");
            return false;
        }
        return true;
    }


    // 新增：计算区块内所有非CoinBase交易的手续费总和
    private long calculateTotalFeesInBlock(List<Transaction> transactions) {
        long totalFees = 0;
        for (Transaction tx : transactions) {
            if (!isCoinBaseTransaction(tx)) { // 跳过CoinBase交易
                totalFees += getFee(tx);
            }
        }
        return totalFees;
    }

    //输入 = 输出 + 手续费
    @Override
    public long getFee(Transaction transaction) {
        // 特殊处理：CoinBase交易没有输入，手续费由区块中其他交易决定
        if (isCoinBaseTransaction(transaction)) {
            return 0;
        }
        List<TXInput> inputs = transaction.getInputs();
        // 计算所有输入引用的UTXO总值
        long totalInput = 0;
        for (TXInput input : inputs) {
            byte[] txId = input.getTxId();
            Transaction transactionByTxId = getBlockTransactionByTxId(txId);
            TXOutput output = transactionByTxId.getOutputs().get(input.getVout());
            totalInput += output.getValue();
        }
        // 计算所有输出总值
        long totalOutput = transaction.getOutputs().stream()
                .mapToLong(TXOutput::getValue)
                .sum();
        // 计算手续费（输入总额减输出总额）
        // 如果输入总额小于输出总额，返回0（这种情况在有效交易中不应该发生）
        return Math.max(0, totalInput - totalOutput);
    }



    /**
     * 获取每字节手续费
     * @param tx
     * @return
     */
    public double getFeePerByte(Transaction tx) {
        return getFee(tx) / (double) tx.getSize();
    }


    /**
     * 处理区块
     */
    private void processValidBlock(Block block) {
        // 保存区块到数据库
        popStorage.addBlock(block);
        processDependentOrphanBlocks(block.getHash());
        // 获取主链最新信息
        long currentHeight = getMainLatestHeight();
        byte[] currentMainHash = getMainLatestBlockHash();
        //防止算力浪费
        List<Transaction> transactions = block.getTransactions();
        for (Transaction transaction : transactions) {
            if (!isCoinBaseTransaction(transaction)){
                mining.removeTransaction(transaction.getTxId());
                log.info("移除交易池中的交易: {}", transaction.getTxId());
            }
        }
        // 检查是否出现分叉（父区块是否为主链最新区块）
        boolean isFork = !Arrays.equals(block.getPreviousHash(), currentMainHash);
        // 1. 处理主链延伸（非分叉情况）
        if (!isFork) {
            // 验证高度连续性
            if (block.getHeight() != currentHeight + 1) {
                log.error("区块高度不连续，拒绝添加。当前主链高度: {}, 新区块高度: {}",
                        currentHeight, block.getHeight());
                return;
            }
            // 正常扩展主链
            updateMainChainHeight(block.getHeight());
            updateMainLatestBlockHash(block.getHash());
            updateMainHeightToBlockIndex(block.getHeight(), block.getHash());
            log.info("主链扩展到高度: {}, 哈希: {}", block.getHeight(), CryptoUtil.bytesToHex(block.getHash()));
            //打印工作总量
            log.info("当前区块工作总量: {}", DifficultyUtils.bytesToLong(block.getChainWork()));
            //应用区块
            applyBlock(block);
        }
        // 2. 处理分叉情况
        else {
            log.info("检测到分叉，高度: {}, 主链: {}, 新链: {}",
                    block.getHeight(), CryptoUtil.bytesToHex(currentMainHash), CryptoUtil.bytesToHex(block.getHash()));
            int compare = DifficultyUtils.compare(block.getChainWork(), getMainLatestBlock().getChainWork()); //新链小就是-1 相等就是零 大就是1
            if (compare==1) {
                log.info("检测到更难的链，准备切换。新链难度: {}, 当前链难度: {}", DifficultyUtils.bytesToLong(block.getChainWork()), DifficultyUtils.bytesToLong(getMainLatestBlock().getChainWork()));
                switchToNewChain(block);
            } else {
                log.info("分叉链难度较小，丢弃，高度: {}, 哈希: {}", block.getHeight(), CryptoUtil.bytesToHex(block.getHash()));
            }
        }
    }

    /**
     * 处理依赖指定区块的所有孤儿区块（递归）
     * @param parentHash 已验证区块的哈希（作为父哈希）
     */
    private void processDependentOrphanBlocks(byte[] parentHash) {
        // 获取所有以当前区块为父区块的孤儿区块
        List<Block> orphanBlocks = getOrphanBlocksByParentHash(parentHash);
        if (orphanBlocks.isEmpty()) {
            log.debug("没有依赖哈希={}的孤儿区块", CryptoUtil.bytesToHex(parentHash));
            return;
        }
        log.info("开始处理依赖哈希={}的{}个孤儿区块",
                CryptoUtil.bytesToHex(parentHash),
                orphanBlocks.size());
        // 移除孤儿池中的这些区块（避免重复处理）
        removeOrphanBlocksByParentHash(parentHash);
        // 逐个验证并处理孤儿区块
        for (Block orphanBlock : orphanBlocks) {
            // 重新验证区块（此时父区块已存在）
            boolean isVerified = verifyBlock(orphanBlock, true); // 验证成功后广播
            if (isVerified) {
                log.info("孤儿区块验证成功并加入主链: 哈希={}, 高度={}",
                        CryptoUtil.bytesToHex(orphanBlock.getHash()),
                        orphanBlock.getHeight());
                // 递归处理该区块的子孤儿区块
                processDependentOrphanBlocks(orphanBlock.getHash());
            } else {
                log.warn("孤儿区块验证失败，丢弃: 哈希={}",
                        CryptoUtil.bytesToHex(orphanBlock.getHash()));
            }
        }
    }

    /**
     * 验证区块中的所有交易
     */
    private boolean validateTransactionsInBlock(Block block) {
        // 1. 验证CoinBase交易  区块中第一笔交易一定是CoinBase交易
        List<Transaction> transactions = block.getTransactions();
        List<Transaction> list = block.getTransactions().stream().skip(1).toList();
        Transaction coinbaseTx = transactions.get(0);
        if (!isValidCoinBaseTransaction(coinbaseTx, block.getHeight(),list)) {
            log.error("区块中 CoinBase交易无效");
            return false;
        }
        boolean b1 = verifyTransactionInBlockUsingHeader(block.extractHeader(), coinbaseTx.getTxId(), block.generateMerklePath(coinbaseTx.getTxId()));
        if (!b1){
            log.error("coinBase交易不在区块中");
            return false;
        }
        log.debug("coinBase交易在区块中：{}", CryptoUtil.bytesToHex(coinbaseTx.getTxId()));
        // 3按顺序验证所有交易（不包括CoinBase）
        for (int i = 1; i < transactions.size(); i++) {
            Transaction tx = block.getTransactions().get(i);
            //验证是否在区块中
            boolean b = verifyTransactionInBlockUsingHeader(block.extractHeader(), tx.getTxId(), block.generateMerklePath(tx.getTxId()));
            if (!b){
                log.error("交易不在区块中");
                return false;
            }
            log.info("交易在区块中：{}", tx.getTxId());
            if (!verifyTransaction(tx)) {
                log.error("区块中打包的交易无效");
                return false;
            }
        }
        log.info("区块中的所有交易验证成功");
        return true;
    }

    /**
     * 仅使用区块头验证交易是否存在于区块中
     *
     * @param blockHeader 区块头
     * @param transactionId 交易ID（哈希值）
     * @param merklePath 默克尔路径，即从交易哈希到默克尔根所需的哈希列表
     * @return 如果交易存在于区块中返回true，否则返回false
     */
    public boolean verifyTransactionInBlockUsingHeader(BlockHeader blockHeader, byte[] transactionId, MerklePath merklePath) {
        return Block.verifyTransactionInBlock(blockHeader, transactionId, merklePath);
    }


    @Override
    public Block getMainLatestBlock() {
        byte[] mainLatestBlockHash = getMainLatestBlockHash();
        if (mainLatestBlockHash == null){
            return null;
        }
        return getBlockByHash(mainLatestBlockHash);
    }

    private void updateMainHeightToBlockIndex(long blockHeight, byte[] blockHash) {
        popStorage.addMainHeightToBlockIndex(blockHeight, blockHash);
    }

    private void updateMainLatestBlockHash(byte[] blockHash) {
        popStorage.updateMainLatestBlockHash(blockHash);
    }


    private void updateMainChainHeight(long blockHeight) {
        popStorage.updateMainLatestHeight(blockHeight);
    }

    /**
     * 主链 通过高度获取区块hash
     */
    @Override
    public byte[] getMainBlockHashByHeight(long height) {
        // 从数据库中获取区块
        return popStorage.getMainBlockHashByHeight(height);
    }

    /**
     * 切换到新链，处理分叉回滚
     */
    private void switchToNewChain(Block newTipBlock) {
        // 1. 找到两个链的共同祖先
        Block commonAncestor = findCommonAncestor(newTipBlock);
        long ancestorHeight = commonAncestor.getHeight();
        log.info("共同祖先区块: {} (高度: {})", CryptoUtil.bytesToHex(commonAncestor.getHash()), ancestorHeight);
        //从备选链中获取某高度的所有区块，筛选出属于新链的区块
        // 2. 回滚当前主链到共同祖先
        List<Block> blocksToUndo = new ArrayList<>();
        for (long i = getMainLatestHeight(); i > ancestorHeight; i--) {
            byte[] blockHash = getMainBlockHashByHeight(i);
            if (blockHash != null) {
                Block block = getBlockByHash(blockHash);
                if (block != null) {
                    blocksToUndo.add(block);
                }
            }
        }
        // 3. 撤销这些区块对UTXO的修改（反向操作）
        for (int i = blocksToUndo.size() - 1; i >= 0; i--) {
            Block block = blocksToUndo.get(i);
            //撤销
            rollbackBlock(block);
        }
        // 4. 应用新链的区块
        List<Block> blocksToApply = new ArrayList<>();
        Block current = newTipBlock;
        while (current.getHeight() > ancestorHeight) {
            blocksToApply.add(0, current);
            current = getBlockByHash(current.getPreviousHash());
        }
        for (Block block : blocksToApply) {
            //应用这些区块中的UTXO
            applyBlock(block);
            //将新链每个区块的高度与哈希写入主链索引
            popStorage.addMainHeightToBlockIndex(block.getHeight(), block.getHash());
        }
        // 6. 更新主链和当前高度
        updateMainChainHeight(newTipBlock.getHeight());
        updateMainLatestBlockHash(newTipBlock.getHash());

        log.info("成功切换到新链，新高度: {}, 新哈希: {}", getMainLatestHeight(), CryptoUtil.bytesToHex(newTipBlock.getHash()));
    }


    //应用区块中的交易 添加交易产生的UTXO 销毁交易引用的UTXO “先恢复输入 UTXO，再删除输出 UTXO”
    public void applyBlock(Block block) {
        List<Transaction> transactions = block.getTransactions();
        List<Transaction> list = block.getTransactions().stream().skip(1).toList();
        // 验证区块中的交易顺序（CoinBase 必须是第一笔交易）
        if (transactions.isEmpty() || !isValidCoinBaseTransaction(transactions.get(0), block.getHeight(),list)) {
            log.error("区块中的 CoinBase 交易无效或顺序错误");
            return;
        }
        // 处理所有交易
        for (int i = 0; i < transactions.size(); i++) {
            Transaction tx = transactions.get(i);
            byte[] txId = tx.getTxId();
            try {
                if (i>0){
                    // 删除引用的 UTXO（交易输入）
                    for (TXInput input : tx.getInputs()) {
                        deleteUTXO(input.getTxId(), input.getVout());
                    }
                }
                // 添加新的 UTXO（交易输出）
                for (int j = 0; j < tx.getOutputs().size(); j++) {
                    TXOutput txOutput = tx.getOutputs().get(j);
                    UTXO utxo = new UTXO();
                    utxo.setTxId(txId);
                    utxo.setVout(j);
                    utxo.setValue(txOutput.getValue());
                    utxo.setScriptPubKey(txOutput.getScriptPubKey());
                    popStorage.putUTXO(utxo);
                }
            } catch (Exception e) {
                log.error("应用交易时发生异常: txId={}", CryptoUtil.bytesToHex(txId), e);
                // 可选择回滚已应用的部分，但需谨慎处理以避免状态不一致
            }
        }
    }

    //回滚区块中交易 销毁交易产生的UTXO 重新添加交易引用的UTXO “先恢复输入 UTXO，再删除输出 UTXO”
    public void rollbackBlock(Block block) {
        // 1. 恢复区块花费的UTXO（交易输入引用的UTXO）
        for (int i = 1; i < block.getTransactions().size(); i++) {
            Transaction tx = block.getTransactions().get(i);
            for (TXInput input : tx.getInputs()) {
                // 获取输入引用的原始交易ID和输出索引
                byte[] referencedTxId = input.getTxId();
                int referencedVout = input.getVout();
                // 根据交易ID查询所在的区块
                Block referencedBlock =  popStorage.getBlockByTxId(referencedTxId);
                if (referencedBlock != null) {
                    // 查询引用的交易，找到这笔输入的引用，重新添加UTXO
                    Transaction referencedTx = findTransactionInBlock(referencedBlock, referencedTxId);
                    if (referencedTx != null && referencedVout < referencedTx.getOutputs().size()) {
                        TXOutput referencedOutput = referencedTx.getOutputs().get(referencedVout);
                        // 重建UTXO并添加到集合
                        UTXO utxo = new UTXO();
                        utxo.setTxId(referencedTxId);
                        utxo.setVout(referencedVout);
                        utxo.setValue(referencedOutput.getValue());
                        utxo.setScriptPubKey(referencedOutput.getScriptPubKey());
                        popStorage.putUTXO(utxo);
                        log.info("回滚UTXO: txId={}, vout={}",
                                CryptoUtil.bytesToHex(referencedTxId), referencedVout);
                    } else {
                        log.error("引用的交易或输出不存在: txId={}, vout={}",
                                CryptoUtil.bytesToHex(referencedTxId), referencedVout);
                    }
                } else {
                    log.error("引用的交易所在区块不存在: txId={}",
                            CryptoUtil.bytesToHex(referencedTxId));
                }
            }
        }
        // 2. 撤销区块产生的UTXO（交易输出创建的UTXO）
        for (Transaction tx : block.getTransactions()) {
            for (int j = 0; j < tx.getOutputs().size(); j++) {
                deleteUTXO(tx.getTxId(), j);
            }
            log.info("撤销UTXO: txId={}", CryptoUtil.bytesToHex(tx.getTxId()));
            //将回滚的交易 验证后重新提交到交易池
            // 3. 仅处理非CoinBase交易，验证后重新提交并广播
            if (!isCoinBaseTransaction(tx)) {  // 关键：跳过CoinBase交易
                log.info("重新提交交易: txId={}", CryptoUtil.bytesToHex(tx.getTxId()));
                verifyAndAddTradingPool(tx, true);  // 广播有效交易
            }
        }
    }

    private Transaction findTransactionInBlock(Block referencedBlock, byte[] referencedTxId) {
        List<Transaction> transactions = referencedBlock.getTransactions();
         for (Transaction transaction : transactions) {
             if (Arrays.equals(transaction.getTxId(), referencedTxId)){
                 return transaction;
             }
         }
        return null;
    }

    /**
     * 二分法查找两条链的共同祖先区块
     * 原理：通过比较两条链在不同高度的区块哈希，定位最大的共同高度，进而找到共同祖先
     * @param newChainTip 新链的末端区块（分叉链的最新区块）
     * @return 共同祖先区块
     */
    private Block findCommonAncestor(Block newChainTip) {
        // 1. 确定查找范围：两条链的最大可能共同高度（取两者中的较小值）
        long mainChainHeight = getMainLatestHeight();
        long newChainHeight = newChainTip.getHeight();
        long maxPossibleHeight = Math.min(mainChainHeight, newChainHeight);

        long low = 0;
        long high = maxPossibleHeight;
        long commonHeight = -1; // 记录找到的最大共同高度

        // 2. 二分查找最大共同高度
        while (low <= high) {
            long mid = (low + high) >>> 1; // 避免溢出的中间值计算

            // 2.1 获取主链在mid高度的区块哈希
            byte[] mainChainHash = getMainBlockHashByHeight(mid);
            if (mainChainHash == null) {
                // 主链在该高度无区块，缩小上界
                high = mid - 1;
                continue;
            }

            // 2.2 获取新链在mid高度的区块哈希（从新链末端向上追溯）
            byte[] newChainHash = getHashByHeightFromChain(newChainTip, mid);
            if (newChainHash == null) {
                // 新链在该高度无区块，缩小上界
                high = mid - 1;
                continue;
            }

            // 2.3 比较哈希，确定共同高度范围
            if (Arrays.equals(mainChainHash, newChainHash)) {
                // 哈希相同，记录当前高度并尝试查找更高的共同高度
                commonHeight = mid;
                low = mid + 1;
            } else {
                // 哈希不同，缩小到更低范围查找
                high = mid - 1;
            }
        }

        // 3. 处理查找结果
        if (commonHeight == -1) {
            // 未找到共同高度（理论上创世区块必为共同祖先）
            log.warn("未找到共同祖先，返回创世区块");
            return getGenesisBlock();
        }

        // 4. 根据共同高度获取祖先区块（主链和新链在该高度哈希相同，取主链即可）
        byte[] ancestorHash = getMainBlockHashByHeight(commonHeight);
        Block ancestorBlock = getBlockByHash(ancestorHash);
        if (ancestorBlock == null) {
            log.error("共同高度{}对应的区块不存在，返回创世区块", commonHeight);
            return getGenesisBlock();
        }

        log.info("找到共同祖先，高度: {}, 哈希: {}",
                commonHeight, CryptoUtil.bytesToHex(ancestorBlock.getHash()));
        return ancestorBlock;
    }

    /**
     * 从指定链的末端区块向上追溯，获取目标高度的区块哈希
     * @param chainTip 链的末端区块
     * @param targetHeight 目标高度
     * @return 目标高度的区块哈希，若不存在则返回null
     */
    private byte[] getHashByHeightFromChain(Block chainTip, long targetHeight) {
        // 目标高度超过链末端高度，直接返回null
        if (targetHeight > chainTip.getHeight()) {
            return null;
        }
        Block current = chainTip;
        // 向上追溯直到找到目标高度或到达链的起点
        while (current != null) {
            if (current.getHeight() == targetHeight) {
                return current.getHash();
            }
            if (current.getHeight() < targetHeight) {
                // 已越过目标高度仍未找到（通常是链不连续导致）
                return null;
            }
            // 继续向上追溯父区块
            current = getBlockByHash(current.getPreviousHash());
        }
        return null;
    }

    /**
     * 获取创世区块hash
     * @return
     */
    @Override
    public byte[] getGenesisBlockHash() {
        return GENESIS_BLOCK_HASH();
    }

    /**
     * 获取创世区块
     * @return
     */
    @Override
    public Block getGenesisBlock() {
        return popStorage.getBlockByHash(GENESIS_BLOCK_HASH());
    }



    /**
     * 验证区块合法性
     */
    private boolean validateBlock(Block block) {
        // 验证区块格式
        if (block == null || block.getTransactions().isEmpty()) {
            log.error("区块格式无效");
            return false;
        }
        // 验证难度目标
        if (!DifficultyUtils.isValidHash(block.getHash(), block.getDifficultyTarget())) {
            log.error("区块哈希不满足难度目标");
            return false;
        }
        return true;
    }

    /**
     * 检查哈希是否满足难度目标
     */
    private boolean isValidHash(byte[] hash, byte[] difficultyTarget) {
        // 1. 验证输入参数
        if (hash == null || hash.length != 32) {
            throw new IllegalArgumentException("哈希值必须为32字节");
        }
        if (difficultyTarget == null || difficultyTarget.length != 4) {
            throw new IllegalArgumentException("难度目标必须为4字节");
        }

        // 2. 将难度目标（4字节压缩格式）转换为完整的256位目标值
        BigInteger target = DifficultyUtils.compactToTarget(difficultyTarget);

        // 3. 将哈希字节数组转换为BigInteger（大端序，正数）
        BigInteger hashValue = new BigInteger(1, hash);

        // 4. 比较哈希值是否小于等于目标值
        return hashValue.compareTo(target) <= 0;
    }


    //根据hash获取区块
    @Override
    public Block getBlockByHash(byte[] hash) {
        // 从数据库中获取区块
        return popStorage.getBlockByHash(hash);
    }

    @Override
    public BlockBody getBlockByHashList(List<byte[]> hashList) {
        return null;
    }

    /**
     * 获取当前区块信息
     * @return
     */
    @Override
    public Result<BlockChain> getBlockChainInfo() {
        BlockChain blockChain = new BlockChain();
        Block mainLatestBlock = getMainLatestBlock();
        long mainLatestHeight = getMainLatestHeight();
        blockChain.setChain(NET_VERSION);
        blockChain.setChainLength(mainLatestHeight);
        blockChain.setKnownHeaderCount(mainLatestHeight);
        blockChain.setLatestBlockHash(CryptoUtil.bytesToHex(mainLatestBlock.getHash()));
        blockChain.setDifficulty(mainLatestBlock.getDifficulty());
        blockChain.setDifficultyTarget(CryptoUtil.bytesToHex(mainLatestBlock.getDifficultyTarget()));
        blockChain.setTime(mainLatestBlock.getTime());
        blockChain.setMedianTime(mainLatestBlock.getMedianTime());
        blockChain.setSyncProgress((double) blockChain.getKnownHeaderCount() / (double) mainLatestHeight);
        blockChain.setInitialBlockDownload(blockChain.getSyncProgress() < 1);
        blockChain.setChainWork(blockChain.getChainWork());
        blockChain.setSizeOnDisk(0);
        return Result.ok(blockChain);
    }

    /**
     * 获取区块信息
     * @param blockHashHex
     * @return
     */
    @Override
    public Result<BlockDTO> getBlock(String blockHashHex) {
        Block block = popStorage.getBlockByHash(CryptoUtil.hexToBytes(blockHashHex));
        if (block == null){
            return Result.error("区块不存在");
        }
        BlockDTO blockDTO = getBlockDto(block);
        return Result.ok(blockDTO);
    }

    @NotNull
    @Override
    public BlockDTO getBlockDto(Block block) {
        BlockDTO blockDTO = BeanCopyUtils.copyObject(block, BlockDTO.class);
        List<Transaction> transactions = block.getTransactions();
        if (transactions != null){
            ArrayList<TransactionDTO> transactionDTOS = new ArrayList<>();
            for (Transaction transaction : transactions) {
                TransactionDTO transactionDTO = BeanCopyUtils.copyObject(transaction, TransactionDTO.class);
                //交易输入
                List<TXInput> inputs = transaction.getInputs();
                if (inputs != null){
                    ArrayList<TXInputDTO> transactionInputDTOS = new ArrayList<>();
                    for (TXInput input : inputs) {
                        TXInputDTO transactionInputDTO = new TXInputDTO();
                        transactionInputDTO.setTxId(input.getTxId());
                        transactionInputDTO.setVout(input.getVout());
                        transactionInputDTO.setScriptSig(input.getScriptSig());
                        transactionInputDTO.setSequence(input.getSequence());
                        transactionInputDTOS.add(transactionInputDTO);
                    }
                    transactionDTO.setInputs(transactionInputDTOS);
                }
                //交易输出
                List<TXOutput> outputs = transaction.getOutputs();
                if (outputs != null){
                    ArrayList<TXOutputDTO> transactionOutputDTOS = new ArrayList<>();
                    for (TXOutput output : outputs) {
                        TXOutputDTO transactionOutputDTO = BeanCopyUtils.copyObject(output, TXOutputDTO.class);
                        transactionOutputDTOS.add(transactionOutputDTO);
                    }
                    transactionDTO.setOutputs(transactionOutputDTOS);
                }
                //见证数据
                List<Witness> witnesses = transaction.getWitnesses();
                if (witnesses != null){
                    ArrayList<WitnessDTO> witnessDTOS = new ArrayList<>();
                    for (Witness witness : witnesses) {
                        WitnessDTO witnessDTO = BeanCopyUtils.copyObject(witness, WitnessDTO.class);
                        witnessDTOS.add(witnessDTO);
                    }
                    transactionDTO.setWitnesses(witnessDTOS);
                }
                transactionDTOS.add(transactionDTO);
            }
            blockDTO.setTransactions(transactionDTOS);
        }
        //获取主链最新高度
        long mainLatestHeight = getMainLatestHeight();
        long height1 = blockDTO.getHeight();
        //计算出确认数量
        blockDTO.setConfirmations(mainLatestHeight - height1 + 1);
        return blockDTO;
    }

    @Override
    public Result<BlockDTO> getBlock(long height) {
        Block block = popStorage.getMainBlockByHeight(height);
        if (block == null){
            return Result.error("区块不存在");
        }
        BlockDTO blockDTO = getBlockDto(block);
        return Result.ok(blockDTO);
    }

    /**
     * 获取UTXO
     */
    @Override
    public UTXO getUTXO(byte[] txId, int vout) {
        return popStorage.getUTXO(txId, vout);
    }

    public UTXO getUTXO(String utxoKey) {
        return popStorage.getUTXO(utxoKey);
    }

    /**
     * 主链最新高度
     */
    @Override
    public long getMainLatestHeight() {
        return popStorage.getMainLatestHeight();
    }

    /**
     * 删除UTXO
     */
    public void deleteUTXO(byte[] txId, int vout) {
        // 从数据库中删除 UTXO
        popStorage.deleteUTXO(txId, vout);
    }

    public byte[] getMainLatestBlockHash() {
        return popStorage.getMainLatestBlockHash();
    }

    public Block getMainBlockByHeight(long height) {
        return popStorage.getMainBlockByHeight(height);
    }

    public Result<TransactionDTO> getTransaction(String txId) {
        //主链中查询  和 交易池中查询

        Transaction transaction = popStorage.getTransaction(CryptoUtil.hexToBytes(txId));
        if (transaction == null){
            return Result.error("交易不存在");
        }
        TransactionDTO transactionDTO = BeanCopyUtils.copyObject(transaction, TransactionDTO.class);
        //交易输入
        List<TXInput> inputs = transaction.getInputs();
        if (inputs != null){
            ArrayList<TXInputDTO> transactionInputDTOS = new ArrayList<>();
            for (TXInput input : inputs) {
                TXInputDTO transactionInputDTO = new TXInputDTO();
                transactionInputDTO.setTxId(input.getTxId());
                transactionInputDTO.setVout(input.getVout());
                transactionInputDTO.setScriptSig(input.getScriptSig());
                transactionInputDTO.setSequence(input.getSequence());
                transactionInputDTOS.add(transactionInputDTO);
            }
            transactionDTO.setInputs(transactionInputDTOS);
        }
        //交易输出
        List<TXOutput> outputs = transaction.getOutputs();
        if (outputs != null){
            ArrayList<TXOutputDTO> transactionOutputDTOS = new ArrayList<>();
            for (TXOutput output : outputs) {
                TXOutputDTO transactionOutputDTO = BeanCopyUtils.copyObject(output, TXOutputDTO.class);
                transactionOutputDTOS.add(transactionOutputDTO);
            }
            transactionDTO.setOutputs(transactionOutputDTOS);
        }
        //见证数据
        List<Witness> witnesses = transaction.getWitnesses();
        if (witnesses != null){
            ArrayList<WitnessDTO> witnessDTOS = new ArrayList<>();
            for (Witness witness : witnesses) {
                WitnessDTO witnessDTO = BeanCopyUtils.copyObject(witness, WitnessDTO.class);
                witnessDTOS.add(witnessDTO);
            }
            transactionDTO.setWitnesses(witnessDTOS);
        }
        return Result.ok(transactionDTO);
    }

    public ListPageResult<UTXO> queryUTXOPage(int i, String cursor) {
        return popStorage.queryUTXOPage(i, cursor);
    }

    public TPageResult<UTXOSearch> selectUtxoAmountsByScriptHash(byte[] scriptHash, int pageSize, String lastUtxoKey) {
        return popStorage.selectUtxoAmountsByScriptHash(scriptHash, pageSize, lastUtxoKey);
    }

    public static TransactionDTO convertTransactionDTO(Transaction transaction) {
        if (transaction == null){
            return null;
        }
        TransactionDTO transactionDTO = BeanCopyUtils.copyObject(transaction, TransactionDTO.class);
        //交易输入
        List<TXInput> inputs = transaction.getInputs();
        if (inputs != null){
            ArrayList<TXInputDTO> transactionInputDTOS = new ArrayList<>();
            for (TXInput input : inputs) {
                TXInputDTO transactionInputDTO = new TXInputDTO();
                transactionInputDTO.setTxId(input.getTxId());
                transactionInputDTO.setVout(input.getVout());
                transactionInputDTO.setScriptSig(input.getScriptSig());
                transactionInputDTO.setSequence(input.getSequence());
                transactionInputDTOS.add(transactionInputDTO);
            }
            transactionDTO.setInputs(transactionInputDTOS);
        }
        //交易输出
        List<TXOutput> outputs = transaction.getOutputs();
        if (outputs != null){
            ArrayList<TXOutputDTO> transactionOutputDTOS = new ArrayList<>();
            for (TXOutput output : outputs) {
                TXOutputDTO transactionOutputDTO = BeanCopyUtils.copyObject(output, TXOutputDTO.class);
                transactionOutputDTOS.add(transactionOutputDTO);
            }
            transactionDTO.setOutputs(transactionOutputDTOS);
        }
        //见证数据
        List<Witness> witnesses = transaction.getWitnesses();
        if (witnesses != null){
            ArrayList<WitnessDTO> witnessDTOS = new ArrayList<>();
            for (Witness witness : witnesses) {
                WitnessDTO witnessDTO = BeanCopyUtils.copyObject(witness, WitnessDTO.class);
                witnessDTOS.add(witnessDTO);
            }
            transactionDTO.setWitnesses(witnessDTOS);
        }
        return transactionDTO;
    }

    //创建一笔CoinBase交易
    public static  Transaction createCoinBaseTransaction(String to, long height,long totalFee) {
        //地址到公钥哈希
        AddressType addressType = CryptoUtil.ECDSASigner.getAddressType(to);
        byte[] bytes = CryptoUtil.ECDSASigner.getAddressHash(to);//地址哈希
        ScriptPubKey scriptPubKey = createScriptPubKey(addressType, bytes);
        Transaction coinbaseTx = new Transaction();
        byte[] zeroTxId = new byte[32]; // 32字节 = 256位
        Arrays.fill(zeroTxId, (byte) 0);
        // 获取当前时间毫秒数
        long currentTimeMillis = TimeGenerator.generateUniqueTransactionTime();
        // 将时间毫秒数转换为字节数组
        byte[] timeBytes = ByteUtils.toBytes(currentTimeMillis);
        // 对时间字节数组进行第一次SHA256哈希
        byte[] firstHash = CryptoUtil.applySHA256(timeBytes);
        // 生成随机数（使用安全随机数生成器）
        SecureRandom secureRandom = new SecureRandom();
        long randomNumber = secureRandom.nextLong();
        byte[] randomBytes = ByteUtils.toBytes(randomNumber);
        // 拼接第一次哈希结果和随机数字节数组
        byte[] combined = new byte[firstHash.length + randomBytes.length];
        System.arraycopy(firstHash, 0, combined, 0, firstHash.length);
        System.arraycopy(randomBytes, 0, combined, firstHash.length, randomBytes.length);
        // 对拼接后的数组进行第二次SHA256哈希
        byte[] secondHash = CryptoUtil.applyRIPEMD160(combined);
        byte[] extraNonce = Arrays.copyOfRange(secondHash, 0, 8);
        ScriptSig scriptSig = new ScriptSig(extraNonce);//解锁脚本必须有随机字符 同一个矿工的coinBase会一模一样
        TXInput input = new TXInput(zeroTxId, 0, scriptSig);
        // 创建输出，将奖励发送到指定地址
        TXOutput output = new TXOutput(calculateBlockReward(height), scriptPubKey);
        coinbaseTx.setVersion(TRANSACTION_VERSION_1);
        coinbaseTx.getInputs().add(input);
        coinbaseTx.getOutputs().add(output);
        if (totalFee > 0){
            TXOutput outputFee = new TXOutput(totalFee, scriptPubKey);
            coinbaseTx.getOutputs().add(outputFee);
        }
        // 计算并设置交易ID
        coinbaseTx.setTxId(coinbaseTx.calculateTxId());
        coinbaseTx.setSize(coinbaseTx.calculateBaseSize());
        coinbaseTx.calculateWeight();
        return coinbaseTx;
    }

    public static ScriptPubKey createScriptPubKey(AddressType type, byte[] addressHash) {
        switch (type) {
            case P2PKH:
                return new ScriptPubKey(addressHash);
            case P2SH:
                return ScriptPubKey.createP2SH(addressHash);
            case P2WPKH:
                return ScriptPubKey.createP2WPKH(addressHash);
            case P2WSH:
                return ScriptPubKey.createP2WSH(addressHash);
            default:
                throw new UnsupportedAddressException("不支持的输出地址类型: " + type);
        }
    }

    public Result getBlockByRange(long start, long end) {
        List<Block> blocks = popStorage.getBlockByRange(start, end);
        List<BlockDTO> blockDTOS = BeanCopyUtils.copyList(blocks, BlockDTO.class);
        for (int i = 0; i < blockDTOS.size(); i++) {
            Block block = blocks.get(i);
            BlockDTO blockDTO = blockDTOS.get(i);
            ArrayList<TransactionDTO> transactionDTOS = new ArrayList<>();
            List<Transaction> transactions = block.getTransactions();
            for (Transaction transaction : transactions){
                TransactionDTO transactionDTO = convertTransactionDTO(transaction);
                transactionDTOS.add(transactionDTO);
            }
            blockDTO.setTransactions(transactionDTOS);
        }
        return Result.OK(blockDTOS);
    }

    @Override
    public List<Block> getBlockListByRange(long start, long end) {
        return popStorage.getBlockByRange(start, end);
    }


    public void addBlockToMainChain(Block validBlock) {
        verifyBlock(validBlock,false);
    }

    /**
     * 比较本地与远程节点的区块差异，并发起同步请求
     */
    public void compareAndSync(NodeInfo remoteNode,
                               long localHeight, byte[] localHash, byte[] localWork,
                               long remoteHeight, byte[] remoteHash, byte[] remoteWork
    ) throws ConnectException, InterruptedException {
        blockSynchronizer.compareAndSync(remoteNode,localHeight, localHash, localWork, remoteHeight, remoteHash, remoteWork);
    }

    @Override
    public List<Block> getBlockByStartHashAndEndHashWithLimit(byte[] startHash, byte[] endHash, int batchSize) {
        return popStorage.getBlockByStartHashAndEndHashWithLimit(startHash, endHash, batchSize);
    }

    /**
     * 计算当前主链的中位数时间
     * @return 中位数时间（单位与区块时间戳一致，如秒）
     */
    public long calculateMedianTime() {
        int windowSize = TIME_WINDOW_SIZE;
        // 获取主链最新区块高度
        long latestHeight = getMainLatestHeight();
        // 实际窗口大小：最多windowSize个，不足则取现有全部（与第二个方法逻辑对齐）
        int actualWindowSize = (int) Math.min(windowSize, latestHeight + 1); // +1是因为包含当前高度

        // 提取最近actualWindowSize个主链区块的时间戳
        List<Long> timestamps = new ArrayList<>(actualWindowSize);
        for (int i = 0; i < actualWindowSize; i++) {
            long height = latestHeight - i;
            Block block = getMainBlockByHeight(height);
            // 过滤无效区块
            if (block != null && block.getTime() > 0) {
                timestamps.add(block.getTime());
            }
        }

        // 处理收集结果（无有效数据时抛异常，与第二个方法对齐）
        if (timestamps.isEmpty()) {
            throw new RuntimeException("未收集到任何有效时间戳，无法计算中位数");
        }
        // 排序并计算中位数（使用实际数据量的中间索引，与第二个方法完全对齐）
        Collections.sort(timestamps);
        int medianIndex = timestamps.size() / 2;
        return timestamps.get(medianIndex);
    }


    /**
     * 获取地址余额
     * @param address
     * @return
     */
    @Override
    public Result getBalance(String address) {
        final int PAGE_SIZE = 5000; // 分页大小，5000更符合常见分页逻辑
        ScriptPubKey lockingScriptByAddress = getLockingScriptByAddress(address);
        byte[] scriptHash = CryptoUtil.applyRIPEMD160(CryptoUtil.applySHA256(lockingScriptByAddress.serialize()));
        String cursor = null;
        boolean hasMore = true;
        long typeBalance = 0;
        int totalFetched = 0;
        while (hasMore) {
            TPageResult<UTXOSearch> pageResult = null;
            // 带重试的分页查询
            pageResult = selectUtxoAmountsByScriptHash(scriptHash, PAGE_SIZE, cursor);
            if (pageResult == null) {
                log.error("{} UTXO查询返回空结果 -> cursor: {}", address, cursor);
                break;
            }
            UTXOSearch searchResult = pageResult.getData();
            if (searchResult == null) {
                log.warn("{} UTXO分页数据为空 -> cursor: {}", address, cursor);
                break;
            }
            Set<String> currentPageUTXOs = searchResult.getUtxos();
            if (currentPageUTXOs != null && !currentPageUTXOs.isEmpty()) {
                typeBalance += searchResult.getTotal();
                totalFetched += currentPageUTXOs.size();
            }
            // 更新分页状态
            hasMore = !pageResult.isLastPage();
            cursor = pageResult.getLastKey();
        }
        return Result.ok(typeBalance);
    }

    @Override
    public Result startSync() {
        return null;
    }

    @Override
    public Result getSyncProgress() {
        return null;
    }

    @Override
    public Result stopSync() {
        return null;
    }

    @Override
    public BlockHeader getBlockHeader(long height) {
        return popStorage.getBlockHeaderByHeight(height);
    }


    @Override
    public List<BlockHeader> getBlockHeaders(long startHeight, int count) {
        return popStorage.getBlockHeaders(startHeight, count);
    }

    @Override
    public Map<Long, byte[]> getBlockHashes(List<Long> heightsToCheck) {
        Map<Long, byte[]> blockHashes = popStorage.getBlockHashes(heightsToCheck);
        if (blockHashes == null) {
            blockHashes = new HashMap<>();
        }
        return blockHashes;
    }

    @Override
    public byte[] getBlockHash(long mid) {
        return getMainBlockHashByHeight(mid);
    }



    @Override
    public Result<BlockDTO> getTransactionBlock(String txId) {
        Block blockByTxId = popStorage.getBlockByTxId(CryptoUtil.hexToBytes(txId));
        return Result.ok(getBlockDto(blockByTxId));
    }

    @Override
    public Result getAllUTXO() {
        return Result.ok(popStorage.getAllUTXO());
    }

    private long getBlockHeaderHeight(BlockHeader parentHeader) {
        //获取区块头高度
        byte[] hash = parentHeader.computeHash();
        return popStorage.getBlockHeightByHash(hash);
    }

    private BlockHeader getBlockHeaderByHash(byte[] previousHash) {
        return popStorage.getBlockHeaderByHash(previousHash);
    }

    public String GENESIS_BLOCK_HASH_HEX() {
        return CryptoUtil.bytesToHex(popStorage.getMainBlockHashByHeight(0));
    }

    public byte[] GENESIS_BLOCK_HASH() {
        return popStorage.getMainBlockHashByHeight(0);
    }

    /**
     * 请求同步
     * @param syncRequest
     * @return
     */
    @Override
    public SyncResponse RequestSynchronization(SyncRequest syncRequest) {
        SyncResponse syncResponse = new SyncResponse();
        byte[] remoteGenesisBlockHash = syncRequest.getGenesisBlockHash();
        byte[] localGenesisBlockHash = GENESIS_BLOCK_HASH();
        if (!Arrays.equals(remoteGenesisBlockHash, localGenesisBlockHash)){
            log.error("创世区块hash不一致,拒绝同步");
            syncResponse.setAllowSync(false);
            syncResponse.setRejectReason("创世区块hash不一致,拒绝同步");
            return syncResponse;
        }
        byte[] remoteLatestHash  = syncRequest.getLatestBlockHash();
        long remoteLatestHeight  = syncRequest.getLatestBlockHeight();
        byte[] remoteChainWork = syncRequest.getChainWork();
        Block localLatestBock = getMainLatestBlock();
        byte[] localLatestHash  = localLatestBock==null? GENESIS_PREV_BLOCK_HASH: localLatestBock.getHash();
        long localLatestHeight  = localLatestBock==null? -1L:localLatestBock.getHeight();
        byte[] localChainWork = localLatestBock==null? new byte[0]:localLatestBock.getChainWork();
        syncResponse.setAllowSync(true);
        syncResponse.setLatestBlockHeight(localLatestHeight);
        syncResponse.setLatestBlockHash(localLatestHash);
        syncResponse.setChainWork(localChainWork);
        syncResponse.setRejectReason("");
        return syncResponse;
    }


    public boolean isTransactionConfirmed(byte[] txId) {
        Block blockByTxId = popStorage.getBlockByTxId(txId);
        return blockByTxId != null;
    }
}
