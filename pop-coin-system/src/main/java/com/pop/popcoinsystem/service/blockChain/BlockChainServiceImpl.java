package com.pop.popcoinsystem.service.blockChain;

import com.pop.popcoinsystem.data.block.Block;
import com.pop.popcoinsystem.data.block.BlockDTO;
import com.pop.popcoinsystem.data.blockChain.BlockChain;
import com.pop.popcoinsystem.data.enums.SigHashType;
import com.pop.popcoinsystem.data.script.*;
import com.pop.popcoinsystem.exception.UnsupportedAddressException;
import com.pop.popcoinsystem.network.common.ExternalNodeInfo;
import com.pop.popcoinsystem.network.common.NodeInfo;
import com.pop.popcoinsystem.network.rpc.RpcProxyFactory;
import com.pop.popcoinsystem.service.blockChain.asyn.AsyncBlockSynchronizerImpl;
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
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.net.ConnectException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.pop.popcoinsystem.constant.BlockChainConstants.TRANSACTION_VERSION_1;
import static com.pop.popcoinsystem.constant.BlockChainConstants.*;
import static com.pop.popcoinsystem.service.blockChain.asyn.AsyncBlockSynchronizerImpl.RPC_TIMEOUT;
import static com.pop.popcoinsystem.storage.StorageService.getUTXOKey;
import static com.pop.popcoinsystem.data.transaction.Transaction.calculateBlockReward;


@Slf4j
@Service
public class BlockChainServiceImpl implements BlockChainService {
    // 新增：父区块同步超时时间（毫秒）
    private static final int PARENT_BLOCK_SYNC_TIMEOUT = 30000; // 30秒

    @Autowired
    private StorageService popStorage;
    @Autowired
    private KademliaNodeServer kademliaNodeServer;
    @Autowired
    private MiningServiceImpl mining;

    @Autowired
    private AsyncBlockSynchronizerImpl blockSynchronizer; // 复用异步同步器

    @PostConstruct
    private void initBlockChain() throws Exception {
        Block genesisBlock = getBlockByHash(GENESIS_BLOCK_HASH);
        if (getBlockByHash(GENESIS_BLOCK_HASH) == null) {
            genesisBlock = createGenesisBlock();
            //保存区块
            popStorage.addBlock(genesisBlock);
            //保存最新的区块hash
            popStorage.updateMainLatestBlockHash(GENESIS_BLOCK_HASH);
            //最新区块高度
            popStorage.updateMainLatestHeight(genesisBlock.getHeight());
            //保存主链中 高度高度到 hash的索引
            popStorage.addMainHeightToBlockIndex(genesisBlock.getHeight(), GENESIS_BLOCK_HASH);
        }
    }


    /**
     * 验证交易
     */
    @Override
    public boolean verifyTransaction(Transaction transaction) {
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
        // 验证SegWit交易ID
        byte[] wtxId = transaction.getWtxId();
        byte[] calculatedWtxId = Transaction.calculateWtxId(transaction);
        if (!Arrays.equals(wtxId, calculatedWtxId)) {
            log.error("SegWit交易wtxid不匹配");
            return false;
        }
        return true;
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
        byte[] originalTxId = transaction.getTxId();
        byte[] calculatedTxId = Transaction.calculateTxId(transaction);

        log.info("原始交易ID:{}", CryptoUtil.bytesToHex(originalTxId));
        log.info("验证时交易ID:{}", CryptoUtil.bytesToHex(calculatedTxId));

        if (!Arrays.equals(calculatedTxId, originalTxId)) {
            log.error("交易ID不匹配");
            return false;
        }

        transaction.setTxId(calculatedTxId);
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
     * 创建创世区块（区块链的第一个区块）
     * 创世区块特殊性：
     * 1. 没有前序区块（previousHash为全零）
     * 2. 高度为0
     * 3. 仅包含一笔CoinBase交易（挖矿奖励）
     * 4. 时间戳通常设置为项目启动时间
     */
    @Override
    public Block createGenesisBlock() {
        // 1. 初始化区块基本信息
        Block genesisBlock = new Block();
        genesisBlock.setHeight(0); // 创世区块高度为0
        genesisBlock.setPreviousHash(new byte[32]); // 前序哈希为全零
        genesisBlock.setVersion(1); // 版本号

        // 2. 设置时间戳（使用比特币创世时间类似的格式，这里使用系统启动时间）
        long genesisTime = 1753695705;
        genesisBlock.setTime(genesisTime);
        genesisBlock.setMedianTime(genesisTime);

        // 3. 设置难度相关参数（创世区块难度通常较低）
        genesisBlock.setDifficulty(1);
        // 比特币创世区块难度目标：0x1d00ffff（这里使用相同值）
        genesisBlock.setDifficultyTarget(DifficultyUtils.difficultyToCompact(1L));
        genesisBlock.setChainWork(ByteUtils.toBytes(1L));

        // 4. 创建创世区块的CoinBase交易（唯一交易）
        Transaction coinbaseTx = createGenesisCoinbaseTransaction();
        List<Transaction> transactions = new ArrayList<>();
        transactions.add(coinbaseTx);
        genesisBlock.setTransactions(transactions);
        genesisBlock.setTxCount(1);

        // 5. 计算默克尔根（仅一个交易，默克尔根就是该交易的哈希）
        byte[] merkleRoot = Block.calculateMerkleRoot(transactions);
        genesisBlock.setMerkleRoot(merkleRoot);

        // 6. 设置区块大小信息
        genesisBlock.setWitnessSize(285); // 示例值

        genesisBlock.setSize(285);
        genesisBlock.setWeight(1140); // 4倍size（隔离见证权重计算）

        // 7. 计算区块哈希（需要找到符合难度的nonce）
        // 创世区块的nonce是固定值，通过暴力计算得到
        genesisBlock.setNonce(1); // 示例nonce值（类似比特币创世块）
        genesisBlock.setHash(GENESIS_BLOCK_HASH);

        genesisBlock.calculateAndSetSize();
        genesisBlock.calculateAndSetWeight();
        log.info("创世区块创建成功，哈希: {}", GENESIS_BLOCK_HASH_HEX);
        return genesisBlock;
    }

    /**
     * 验证交易并提交到交易池
     * 交易验证成功后 广播交易 如果本节点是矿工节点 则再添加到交易池 由矿工打包
     */
    @Override
    public boolean verifyAndAddTradingPool(Transaction transaction, boolean broadcastMessage) {
        log.info("您的交易已提交,正在验证交易...");
        if (verifyTransaction(transaction)) {
            mining.addTransaction(transaction);
            if (kademliaNodeServer.isRunning()){
                log.info("交易验证成功,广播交易");
                TransactionMessage transactionKademliaMessage = new TransactionMessage();
                transactionKademliaMessage.setSender(kademliaNodeServer.getNodeInfo());
                transactionKademliaMessage.setData(transaction);
                kademliaNodeServer.broadcastMessage(transactionKademliaMessage);
            }
        }
        return true;
    }

    /**
     * 验证区块
     * UTXO 并非仅在交易验证成功后产生，而是在交易被成功打包进区块并经过网络确认后，才成为有效的 UTXO。
     */
    @Override
    public boolean verifyBlock(Block block, boolean broadcastMessage) {
        // 验证区块合法性
        if (!validateBlock(block)) {
            log.warn("区块验证失败，哈希：{}", CryptoUtil.bytesToHex(block.getHash()));
            return false;
        }
        // 检查是否是已知区块
        if (getBlockByHash(block.getHash()) != null) {
            log.info("区块已存在，哈希：{}", CryptoUtil.bytesToHex(block.getHash()));
            return true;
        }

        Block parentBlock = getBlockByHash(block.getPreviousHash());
        if (parentBlock == null) {
            log.warn("父区块不存在，触发同步，哈希：{}", CryptoUtil.bytesToHex(block.getPreviousHash()));
            // 同步父区块（递归处理所有缺失的祖先）
            boolean parentSynced = syncMissingParentBlocks(block.getPreviousHash());
            if (!parentSynced) {
                log.error("父区块同步失败，无法验证当前区块，哈希：{}", CryptoUtil.bytesToHex(block.getHash()));
                return false;
            }
            // 同步成功后重新获取父区块
            parentBlock = getBlockByHash(block.getPreviousHash());
            if (parentBlock == null) {
                log.error("同步后父区块仍不存在，验证失败");
                return false;
            }
        }
        if (parentBlock.getHeight() + 1 != block.getHeight()) {
            log.warn("区块高度不连续，父区块高度：{}，当前区块高度：{}", parentBlock.getHeight(), block.getHeight());
            return false;
        }
        // 验证区块中的交易
        if (!validateTransactionsInBlock(block)) {
            log.warn("区块中的交易验证失败，哈希：{}", CryptoUtil.bytesToHex(block.getHash()));
            return false;
        }
        if (!validateBlockPoW(block)){
            log.warn("区块 PoW 验证失败，哈希：{}", CryptoUtil.bytesToHex(block.getHash()));
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
                kademliaNodeServer.broadcastMessage(blockMessage);
            }
        }
        return true;
    }






    /**
     * 迭代方式同步缺失的父区块（替代递归，避免栈溢出）
     */
    private boolean syncMissingParentBlocks(byte[] targetParentHash) {
        // 用栈存储待同步的区块哈希（模拟递归调用栈）
        Stack<byte[]> hashStack = new Stack<>();
        hashStack.push(targetParentHash);

        // 记录已处理的哈希，避免循环同步（如恶意区块形成环）
        Set<String> processedHashes = new HashSet<>();

        while (!hashStack.isEmpty()) {
            byte[] currentHash = hashStack.pop();
            String currentHashHex = CryptoUtil.bytesToHex(currentHash);

            // 检查是否已处理过（防环）
            if (processedHashes.contains(currentHashHex)) {
                log.warn("检测到循环依赖的区块哈希：{}，跳过", currentHashHex);
                continue;
            }
            processedHashes.add(currentHashHex);

            // 检查区块是否已存在，存在则无需同步
            if (getBlockByHash(currentHash) != null) {
                log.info("区块已存在，无需同步：{}", currentHashHex);
                continue;
            }

            // 若为创世区块且不存在，同步失败
            if (Arrays.equals(currentHash, getGenesisBlockHash())) {
                log.error("创世区块不存在，同步失败");
                return false;
            }

            // 同步当前区块
            Block currentBlock = syncSingleBlock(currentHash);
            if (currentBlock == null) {
                log.error("同步区块失败：{}，中断链条", currentHashHex);
                return false;
            }

            // 将当前区块的父哈希压入栈，继续同步（模拟递归）
            byte[] parentHash = currentBlock.getPreviousHash();
            hashStack.push(parentHash);

            // 每同步10个区块，手动触发一次GC（可选，根据内存情况调整）
            if (processedHashes.size() % 10 == 0) {
                System.gc(); // 提示JVM回收内存（非强制）
            }
        }
        return true;
    }

    /**
     * 同步单个区块（提取为独立方法，便于复用和控制）
     */
    private Block syncSingleBlock(byte[] blockHash) {
        try {
            // 1. 查找候选节点
            List<ExternalNodeInfo> closestNodes = kademliaNodeServer.getRoutingTable()
                    .findClosest(new BigInteger(1, blockHash));
            if (closestNodes.isEmpty()) {
                log.error("无候选节点提供区块：{}", CryptoUtil.bytesToHex(blockHash));
                return null;
            }
            List<NodeInfo> candidateNodes = BeanCopyUtils.copyList(closestNodes, NodeInfo.class);

            // 2. 向节点请求区块
            CompletableFuture<Block> blockFuture = new CompletableFuture<>();
            for (NodeInfo node : candidateNodes) {
                try {
                    blockSynchronizer.syncExecutor.submit(() -> {
                        try {
                            RpcProxyFactory proxyFactory = new RpcProxyFactory(kademliaNodeServer, node);
                            proxyFactory.setTimeout(RPC_TIMEOUT);
                            BlockChainService remoteService = proxyFactory.createProxy(BlockChainService.class);
                            Block result = remoteService.getBlockByHash(blockHash);
                            if (result != null) {
                                blockFuture.complete(result);
                            }
                        } catch (Exception e) {
                            log.debug("从节点{}获取区块失败，继续尝试", node, e);
                        }
                    });

                    // 等待结果（超时后尝试下一个节点）
                    Block block = blockFuture.get(PARENT_BLOCK_SYNC_TIMEOUT / candidateNodes.size(), TimeUnit.MILLISECONDS);
                    if (block != null && Arrays.equals(block.getHash(), blockHash)) {
                        // 验证并添加区块到本地
                        addBlockToMainChain(block);
                        return block;
                    }
                } catch (TimeoutException | InterruptedException e) {
                    log.debug("从节点{}获取区块超时，继续尝试", node, e);
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    log.debug("从节点{}获取区块异常，继续尝试", node, e);
                }
            }
            return null; // 所有节点均失败
        } catch (Exception e) {
            log.error("同步单个区块异常：{}", CryptoUtil.bytesToHex(blockHash), e);
            return null;
        }
    }




    /**
     * 创建隔离见证交易的签名哈希
     * @param tx 交易对象
     * @param inputIndex 输入索引  inputIndex指定当前签名对应的输入索引，确保签名仅针对当前输入的 UTXO 权限验证；
     * @param amount 金额
     * @param sigHashType 签名哈希类型  sigHashType（如ALL、NONE、SINGLE）决定交易的哪些部分（输入、输出）会被纳入哈希计算（例如ALL表示包含所有输入和输出，防止任何部分被篡改）。
     * @return 签名哈希
     */
    @Override
    public byte[] createWitnessSignatureHash(Transaction tx, int inputIndex, long amount, SigHashType sigHashType) {
        // 1. 复制交易对象，避免修改原交易
        Transaction txCopy = Transaction.copyWithoutWitness(tx);
        for (TXInput input : txCopy.getInputs()) {
            input.setScriptSig(null);
        }

        txCopy.setTime(0L);
        txCopy.setVersion(0);

        // 获取当前处理的输入
        TXInput currentInput = txCopy.getInputs().get(inputIndex);
        if (currentInput == null){
            log.warn("输入索引超出范围");
            throw new RuntimeException("输入索引超出范围");
        }

        UTXO currentUTXO = getUTXO(currentInput.getTxId(), currentInput.getVout()); // inputUTXOs 是当前输入引用的 UTXO 集合
        ScriptPubKey originalScriptPubKey  = currentUTXO.getScriptPubKey();

        ScriptSig tempByScriptPubKey = ScriptSig.createTempByScriptPubKey(originalScriptPubKey);
        String scripString = tempByScriptPubKey.toScripString();

        //在区块链交易签名流程中，代码里的临时脚本（tempByScriptPubKey）不需要执行完整验证，
        // 其核心作用是作为签名哈希（Signature Hash）计算的 “特征上下文”，确保签名能正确关联到被花费的 UTXO 的锁定规则。
        currentInput.setScriptSig(tempByScriptPubKey); // 临时设置，仅用于签名
        // 4. 创建签名哈希
        log.info("植入特征{}", scripString);
        return txCopy.calculateWitnessSignatureHash(inputIndex, amount, sigHashType);
    }

    private boolean utxoIsMature(UTXO utxo) {
        byte[] txId = utxo.getTxId();
        Block blockByTxId = getBlockByTxId(txId);
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
        Block block = getBlockByTxId(txId);
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
            // 获取该输入引用的UTXO
            UTXO utxo = getUTXO(input.getTxId(), input.getVout());
            // 验证UTXO存在且未被花费
            if (utxo == null) {
                // 如果UTXO不存在或已被花费，该输入不贡献价值
                continue;
            }
            totalInput += utxo.getValue();
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
     * 判断是否为CoinBase交易（区块中第一笔交易，输入无有效UTXO）
     */
    private boolean isCoinBaseTransaction(Transaction transaction) {
        List<TXInput> inputs = transaction.getInputs();
        if (inputs == null || inputs.size() != 1) {
            return false;
        }
        TXInput input = inputs.get(0);
        // CoinBase交易的输入txId为全零，且vout为特殊值（如-1或0，根据协议定义）
        return input.getTxId() != null
                && Arrays.equals(input.getTxId(), new byte[32])  // txId为全零
                && (input.getVout() == -1 || input.getVout() == 0);  // 匹配协议定义的特殊值
    }


    /**
     * 获取每字节手续费
     * @param tx
     * @return
     */
    @Override
    public double getFeePerByte(Transaction tx) {
        return getFee(tx) / (double) tx.getSize();
    }


    /**
     * 处理区块
     */
    private void processValidBlock(Block block) {
        // 保存区块到数据库
        popStorage.addBlock(block);
        // 获取主链最新信息
        long currentHeight = getMainLatestHeight();
        byte[] currentMainHash = getMainLatestBlockHash();

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
                log.info("分叉链难度较小，添加到备选链，高度: {}, 哈希: {}",
                        block.getHeight(), CryptoUtil.bytesToHex(block.getHash()));
                addAlternativeChains(block.getHeight(), block.getHash());
            }
        }
    }

    private void addAlternativeChains(long blockHeight, byte[] blockHash) {
        Set<byte[]> altBlockHashByHeight = popStorage.getALTBlockHashByHeight(blockHeight);
        altBlockHashByHeight.add(blockHash);
        log.info("已经将该区块加入到 备选链添加区块，高度: {}, 哈希: {}", blockHeight, CryptoUtil.bytesToHex(blockHash));
    }

    /**
     * 创建创世区块的CoinBase交易
     * CoinBase交易特殊性：
     * 1. 没有输入（或输入为特殊值）
     * 2. 输出为初始挖矿奖励
     */
    private Transaction createGenesisCoinbaseTransaction() {
        Transaction coinbaseTx = new Transaction();
        // 创建特殊输入（引用自身）
        TXInput input = new TXInput();
        input.setTxId(new byte[32]); // 全零交易ID
        input.setVout(0); // 特殊值表示CoinBase交易
        byte[] bytes = "The Times 03/Jan/2009 Chancellor on brink of second bailout for banks".getBytes();
        ScriptSig scriptSig = new ScriptSig(bytes);
        input.setScriptSig(scriptSig);
        input.setScriptSig(null); // 创世信息   解锁脚本
        List<TXInput> inputs = new ArrayList<>();
        inputs.add(input);
        coinbaseTx.setInputs(inputs);
        // 创建输出（初始奖励50 BTC = 50*1e8聪）
        TXOutput output = new TXOutput();
        output.setValue(50L * 100000000); // 50 BTC in satoshi
        // 创世区块奖励地址（可以替换为你的项目地址）
        String address = "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa";
        //获取地址公钥哈希
        byte[] addressHash = CryptoUtil.ECDSASigner.getAddressHash(address);
        ScriptPubKey pubKey = new ScriptPubKey(addressHash);
        output.setScriptPubKey(pubKey);
        List<TXOutput> outputs = new ArrayList<>();
        outputs.add(output);
        coinbaseTx.setOutputs(outputs);
        // 计算交易ID
        byte[] txId = Transaction.calculateTxId(coinbaseTx);
        coinbaseTx.setTxId(txId);
        coinbaseTx.calculateWeight();
        return coinbaseTx;
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
        // 3. 按顺序验证所有交易（不包括CoinBase）
        for (int i = 1; i < transactions.size(); i++) {
            Transaction tx = block.getTransactions().get(i);
            if (!verifyTransaction(tx)) {
                log.error("区块中打包的交易无效");
                return false;
            }
        }
        log.info("区块中的所有交易验证成功");
        return true;
    }


    @Override
    public Block getMainLatestBlock() {
        return getBlockByHash(getMainLatestBlockHash());
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
     * 比较两个哈希的难度，返回true如果第一个哈希难度更大
     */
    private boolean isDifficultyGreater(byte[] hash1, byte[] hash2) {
        log.info("比较两个哈希的难度");
        if (hash1 == null || hash2 == null) {
            return false;
        }
        // 比较两个哈希的大小，值越小难度越大
        for (int i = 0; i < hash1.length; i++) {
            if (hash1[i] < hash2[i]) {
                return true;
            } else if (hash1[i] > hash2[i]) {
                return false;
            }
        }
        return false;
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
        // 5. 应用这些区块对UTXO的修改
        for (Block block : blocksToApply) {
            //应用这些区块中的UTXO
            applyBlock(block);
            //将新链每个区块的高度与哈希写入主链索引
            popStorage.addMainHeightToBlockIndex(block.getHeight(), block.getHash());
        }
        // 6. 更新主链和当前高度
        updateMainChainHeight(newTipBlock.getHeight());
        updateMainLatestBlockHash(newTipBlock.getHash());

        // 7. 清理备选链
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
                    addUTXO(utxo);
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
                Block referencedBlock = getBlockByTxId(referencedTxId);
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
                        addUTXO(utxo);
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
        }
    }


    private void addUTXO(UTXO utxo) {
        popStorage.putUTXO(utxo);
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

    private Block getBlockByTxId(byte[] txId) {
        return popStorage.getBlockByTxId(txId);
    }


    /**
     * 找到两个链的公共祖先
     * @param block
     * @return
     */
    private Block findCommonAncestor(Block block) {
        log.info("找共同的祖先");
        Block currentA = block;
        Block currentB = getBlockByHash(getMainLatestBlockHash());

        log.info("区块A的父哈希: {}", CryptoUtil.bytesToHex(currentA.getPreviousHash()));
        log.info("区块B的父哈希: {}", CryptoUtil.bytesToHex(currentA.getPreviousHash()));
        log.info("高度{},的区块哈希: {}",block.getHeight(), CryptoUtil.bytesToHex(getMainBlockHashByHeight(block.getHeight()-1)));

        // 循环条件：当两个区块不相等时继续追溯
        while (!Arrays.equals(currentA.getHash(), currentB.getHash())) {
            // 如果A的高度大于B，A向上移动一级
            if (currentA.getHeight() > currentB.getHeight()) {
                currentA = getBlockByHash(currentA.getPreviousHash());
                if (currentA == null) return getGenesisBlock(); // 安全保护
            }
            // 如果B的高度大于A，B向上移动一级
            else if (currentB.getHeight() > currentA.getHeight()) {
                currentB = getBlockByHash(currentB.getPreviousHash());
                if (currentB == null) return getGenesisBlock(); // 安全保护
            }
            // 高度相同，则同时向上移动一级
            else {
                currentA = getBlockByHash(currentA.getPreviousHash());
                currentB = getBlockByHash(currentB.getPreviousHash());

                // 若两者同时到达创世区块仍未找到共同祖先，返回创世区块
                if (currentA == null || currentB == null) {
                    return getGenesisBlock();
                }
            }
        }
        log.info("找到共同的祖先: {}", CryptoUtil.bytesToHex(currentA.getHash()));
        return currentA;
    }


    /**
     * 获取创世区块hash
     * @return
     */
    @Override
    public byte[] getGenesisBlockHash() {
        return GENESIS_BLOCK_HASH;
    }

    /**
     * 获取创世区块
     * @return
     */
    @Override
    public Block getGenesisBlock() {
        return popStorage.getBlockByHash(GENESIS_BLOCK_HASH);
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


    // 分页结果数据结构（调整为int类型游标）



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
        //coinBase的输入的交易ID
        byte[] zeroTxId = new byte[32]; // 32字节 = 256位
        Arrays.fill(zeroTxId, (byte) 0);
        TXInput input = new TXInput(zeroTxId, 0, null);
        // 创建输出，将奖励发送到指定地址
        ScriptPubKey scriptPubKey = createScriptPubKey(addressType, bytes);
        TXOutput output = new TXOutput(calculateBlockReward(height)+totalFee, scriptPubKey);
        Transaction coinbaseTx = new Transaction();
        coinbaseTx.setVersion(TRANSACTION_VERSION_1);
        coinbaseTx.getInputs().add(input);
        coinbaseTx.getOutputs().add(output);
        coinbaseTx.setTime(System.currentTimeMillis());
        // 计算并设置交易ID
        coinbaseTx.setTxId(Transaction.calculateTxId(coinbaseTx));
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

    public byte[] createLegacySignatureHash(Transaction tx, int inputIndex, UTXO utxo, SigHashType sigHashType) {
        //打印所有的参数
        // 1. 复制交易对象，避免修改原交易
        Transaction txCopy  = new Transaction();
        txCopy.setTime(0L);
        // 1. 仅复制计算签名哈希必需的核心字段，去除无关数据
        // 保留核心字段：版本、输入列表、输出列表、锁定时间（这些是签名哈希计算的必要项）
        txCopy.setInputs(copyEssentialInputs(tx.getInputs())); // 仅复制输入的必要信息
        txCopy.setOutputs(new ArrayList<>(tx.getOutputs())); // 复制输出列表（浅拷贝足够，无需修改）
        txCopy.setLockTime(tx.getLockTime());
        // 3. 获取当前输入并验证
        TXInput currentInput = txCopy.getInputs().get(inputIndex);
        if (currentInput == null) {
            log.warn("输入索引超出范围");
            throw new RuntimeException("输入索引超出范围");
        }
        // 4. 将当前输入对应的UTXO的scriptPubKey作为临时scriptSig（核心步骤，必须执行）
        ScriptPubKey originalScriptPubKey = utxo.getScriptPubKey();
        if (originalScriptPubKey == null) {
            log.warn("UTXO的scriptPubKey为空");
            throw new IllegalArgumentException("UTXO的scriptPubKey不能为空");
        }
        ScriptSig tempScriptSig = new ScriptSig(originalScriptPubKey.getScriptBytes());
        currentInput.setScriptSig(tempScriptSig);

        // 5. 计算签名哈希
        return txCopy.calculateLegacySignatureHash(inputIndex, sigHashType);
    }

    private List<TXInput> copyEssentialInputs(List<TXInput> inputs) {
        List<TXInput> copiedInputs = new ArrayList<>();
        for (TXInput input : inputs) {
            TXInput inputCopy = new TXInput();
            // 仅复制输入的必要信息
            inputCopy.setTxId(Arrays.copyOf(input.getTxId(), input.getTxId().length));
            inputCopy.setVout(input.getVout());
            inputCopy.setSequence(input.getSequence());
            copiedInputs.add(inputCopy);
        }
        return copiedInputs;
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




    public List<Block> getBlockByStartHashAndEndHash(byte[] start, byte[] end) {
        return popStorage.getBlockByStartHashAndEndHash(start, end);
    }

    public List<Block> getBlockByStartHashAndEndHashByHeight(byte[] start, byte[] end) {
        long startHeight = popStorage.getBlockHeightByHash(start);
        long endHeight = popStorage.getBlockHeightByHash(end);
        return popStorage.getBlockByRange(startHeight, endHeight);
    }


    /**
     * 验证区块的工作量证明（PoW）是否符合难度要求
     * 工作量证明验证核心：区块哈希必须小于等于难度目标值
     * @param block 待验证的区块
     * @return 验证结果：true-符合PoW要求，false-不符合
     */
    public boolean validateBlockPoW(Block block) {
        // 1. 验证区块对象有效性
        if (block == null) {
            log.error("待验证区块为null");
            return false;
        }

        // 2. 验证区块哈希有效性（必须为32字节，符合SHA-256哈希长度）
        byte[] blockHash = block.getHash();
        if (blockHash == null || blockHash.length != 32) {
            log.error("区块哈希无效，必须为32字节");
            return false;
        }

        // 3. 验证难度目标有效性（必须为4字节压缩格式）
        byte[] difficultyTarget = block.getDifficultyTarget();
        if (difficultyTarget == null || difficultyTarget.length != 4) {
            log.error("难度目标无效，必须为4字节压缩格式");
            return false;
        }

        try {
            // 4. 将4字节压缩难度目标转换为256位目标值（BigInteger）
            BigInteger target = DifficultyUtils.compactToTarget(difficultyTarget);
            if (target.equals(BigInteger.ZERO)) {
                log.error("难度目标转换后为0，无效");
                return false;
            }

            // 5. 将区块哈希转换为BigInteger（注意：使用1作为符号位参数，确保哈希值被解析为正数）
            BigInteger hashValue = new BigInteger(1, blockHash);

            // 6. 核心验证：区块哈希值必须小于等于难度目标值
            boolean isValid = hashValue.compareTo(target) <= 0;
            if (!isValid) {
                log.error("区块PoW验证失败，哈希值({})大于目标值({})",
                        hashValue.toString(16), target.toString(16));
            }
            return isValid;
        } catch (ArithmeticException e) {
            log.error("PoW验证时发生数值计算异常", e);
            return false;
        }
    }

    public void addBlockToMainChain(Block validBlock) {
        verifyBlock(validBlock,false);
    }

    public void addBlockToAltChain(Block validBlock) {
        verifyBlock(validBlock,false);
    }

    /**
     * 比较本地与远程节点的区块差异，并发起同步请求
     */
    public void compareAndSync(KademliaNodeServer nodeServer, NodeInfo remoteNode,
                               long localHeight, byte[] localHash, byte[] localWork,
                               long remoteHeight, byte[] remoteHash, byte[] remoteWork
    ) throws ConnectException, InterruptedException {
        AsyncBlockSynchronizerImpl blockSynchronizer = new AsyncBlockSynchronizerImpl(nodeServer);
        blockSynchronizer.submitSyncTask(nodeServer,remoteNode,localHeight,localHash,localWork,remoteHeight,remoteHash,remoteWork);
    }

    @Override
    public byte[] findForkPoint(List<byte[]> remoteHashes) {
        // 遍历远程提供的哈希（按从新到旧顺序），返回第一个在本地链中存在的哈希
        for (byte[] hash : remoteHashes) {
            if (getBlockByHash(hash) != null) {
                return hash; // 找到最后一个共同区块
            }
        }
        return null; // 无共同区块
    }



    @Override
    public List<Block> getBlockByStartHashAndEndHashWithLimit(byte[] startHash, byte[] endHash, int batchSize) {
        return popStorage.getBlockByStartHashAndEndHashWithLimit(startHash, endHash, batchSize);
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
        long latestHeight = getMainLatestHeight();
        if (latestHeight < windowSize - 1) {
            // 区块数量不足窗口大小，直接返回最新区块时间
            return getMainLatestBlock().getTime();
        }

        // 3. 提取最近windowSize个主链区块的时间戳
        List<Long> timestamps = new ArrayList<>(windowSize);
        for (int i = 0; i < windowSize; i++) {
            long height = latestHeight - i;
            Block block = getMainBlockByHeight(height);
            if (block != null) {
                timestamps.add(block.getTime());
            }
        }

        // 4. 排序并取中位数
        Collections.sort(timestamps);
        int medianIndex = windowSize / 2; // 对于11个元素，索引为5（0~10）
        return timestamps.get(medianIndex);
    }

}
