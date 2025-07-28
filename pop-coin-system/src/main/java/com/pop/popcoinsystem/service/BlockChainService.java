package com.pop.popcoinsystem.service;

import com.pop.popcoinsystem.application.service.Wallet;
import com.pop.popcoinsystem.application.service.WalletStorage;
import com.pop.popcoinsystem.data.block.Block;
import com.pop.popcoinsystem.data.block.BlockDTO;
import com.pop.popcoinsystem.data.block.BlockVO;
import com.pop.popcoinsystem.data.blockChain.BlockChain;
import com.pop.popcoinsystem.data.enums.UTXOStatus;
import com.pop.popcoinsystem.data.miner.Miner;
import com.pop.popcoinsystem.data.script.Script;
import com.pop.popcoinsystem.data.script.ScriptPubKey;
import com.pop.popcoinsystem.data.script.ScriptSig;
import com.pop.popcoinsystem.data.storage.POPStorage;
import com.pop.popcoinsystem.data.transaction.*;
import com.pop.popcoinsystem.data.transaction.dto.TXInputDTO;
import com.pop.popcoinsystem.data.transaction.dto.TXOutputDTO;
import com.pop.popcoinsystem.data.transaction.dto.TransactionDTO;
import com.pop.popcoinsystem.data.transaction.dto.WitnessDTO;
import com.pop.popcoinsystem.data.vo.result.Result;
import com.pop.popcoinsystem.util.BeanCopyUtils;
import com.pop.popcoinsystem.util.ByteUtils;
import com.pop.popcoinsystem.util.CryptoUtil;
import com.pop.popcoinsystem.util.DifficultyUtils;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.checkerframework.checker.units.qual.C;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.*;

import static com.pop.popcoinsystem.data.script.Script.OP_RETURN;
import static com.pop.popcoinsystem.data.storage.POPStorage.getUTXOKey;
import static com.pop.popcoinsystem.data.transaction.Transaction.calculateBlockReward;
import static com.pop.popcoinsystem.data.vo.result.Result.SC_OK_200;
import static com.pop.popcoinsystem.util.CryptoUtil.ECDSASigner.verifySignature;
import static com.pop.popcoinsystem.util.Numeric.hexStringToByteArray;

@Slf4j
@Service
public class BlockChainService {

    private static final int COINBASE_MATURITY = 100;// CoinBase交易成熟度要求
    public static final String GENESIS_BLOCK_HASH_HEX = "000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f";
    private static final byte[] GENESIS_BLOCK_HASH = CryptoUtil.hexToBytes(GENESIS_BLOCK_HASH_HEX);

    private POPStorage popStorage;

    private MiningService miningService;

    private MiningService miningService2;

    @PostConstruct
    private void initBlockChain() throws Exception {
        miningService = new MiningService(this);
        popStorage = POPStorage.getInstance();
        log.info("初始化区块链服务...");
        // 检查是否已存在创世区块  不存在就创建
        Block genesisBlock = getBlockByHash(GENESIS_BLOCK_HASH);
        if (getBlockByHash(GENESIS_BLOCK_HASH) == null) {
            log.info("创世区块是空的");
            genesisBlock = createGenesisBlock();
            //保存区块
            popStorage.addBlock(genesisBlock);
            //保存最新的区块hash
            popStorage.updateMainLatestBlockHash(GENESIS_BLOCK_HASH);
            log.info("创世区块hash:"+GENESIS_BLOCK_HASH_HEX);
            //最新区块高度
            popStorage.updateMainLatestHeight(genesisBlock.getHeight());
            //保存主链中 高度高度到 hash的索引
            popStorage.addMainHeightToBlockIndex(genesisBlock.getHeight(), GENESIS_BLOCK_HASH);
            log.info("创世区块高度:"+genesisBlock.getHeight());
        }
        WalletStorage walletStorage = WalletStorage.getInstance();
        Wallet walleta = walletStorage.getWallet("BTC-Miner");
        if (walleta == null){
            KeyPair keyPairA = CryptoUtil.ECDSASigner.generateKeyPair();
            PrivateKey privateKey = keyPairA.getPrivate();
            PublicKey publicKey = keyPairA.getPublic();
            walleta = new Wallet();
            walleta.setName("BTC-Miner");
            walleta.setPublicKeyHex(CryptoUtil.bytesToHex(publicKey.getEncoded()));
            walleta.setPrivateKeyHex(CryptoUtil.bytesToHex(privateKey.getEncoded()));
            walletStorage.addWallet(walleta);
        }
        String p2PKHAddressByPK = CryptoUtil.ECDSASigner.createP2PKHAddressByPK(CryptoUtil.hexToBytes(walleta.getPublicKeyHex()));

        Miner miner = new Miner();
        miner.setAddress(p2PKHAddressByPK);
        miner.setName("BTC-Miner");
        miningService.setMiningInfo(miner);

        //延迟5秒后开始挖矿
        Thread.sleep(5000);
        miningService.startMining();
    }

    /**
     * 创建创世区块（区块链的第一个区块）
     * 创世区块特殊性：
     * 1. 没有前序区块（previousHash为全零）
     * 2. 高度为0
     * 3. 仅包含一笔CoinBase交易（挖矿奖励）
     * 4. 时间戳通常设置为项目启动时间
     */
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
    public Result<String> verifyAndAddTradingPool(Transaction transaction) {
        Result<String> verifyResult = verifyTransaction(transaction);
        if (verifyResult.getCode().equals(SC_OK_200)) {
            // 验证成功，将交易添加到交易池
            miningService.addTransaction(transaction);
            //广播交易
            new Thread(() -> {
                log.info("交易验证成功,广播交易");
            });
        }
        return verifyResult;
    }

    /**
     * 验证交易
     */
    public Result<String> verifyTransaction(Transaction transaction) {
        // 基础格式验证
        if (transaction == null || transaction.getInputs() == null || transaction.getOutputs() == null) {
            log.error("交易格式无效");
            return Result.error("交易格式无效");
        }
        // 交易金额验证
        long inputSum = transaction.getInputs().stream()
                .map(input -> {
                    UTXO utxo = getUTXO(input.getTxId(), input.getVout());
                    return utxo != null ? utxo.getValue() : 0;
                })
                .mapToLong(Long::longValue)
                .sum();
        long outputSum = transaction.getOutputs().stream()
                .mapToLong(TXOutput::getValue)
                .sum();
        if (inputSum < outputSum) {
            log.error("交易输出金额大于输入金额");
            return Result.error("交易输出金额大于输入金额");
        }

        // 交易ID验证
        byte[] calculatedTxId = Transaction.calculateTxId(transaction);
        if (!Arrays.equals(calculatedTxId, transaction.getTxId())) {
            log.error("交易ID不匹配");
            return Result.error("交易ID不匹配");
        }

        // 验证输入的UTXO是否未花费


        if (transaction.isSegWit()){
            //普通交易验证

        }else {
            //隔离见证交易验证

        }
        return Result.OK("交易验证成功");
    }



    /**
     * 验证区块
     * UTXO 并非仅在交易验证成功后产生，而是在交易被成功打包进区块并经过网络确认后，才成为有效的 UTXO。
     */
    public boolean verifyBlock(Block block) {
        // 1. 验证区块合法性
        if (!validateBlock(block)) {
            log.warn("区块验证失败，哈希：{}", CryptoUtil.bytesToHex(block.getHash()));
            return false;
        }
        // 2. 检查是否是已知区块
        if (getBlockByHash(block.getHash()) != null) {
            log.info("区块已存在，哈希：{}", CryptoUtil.bytesToHex(block.getHash()));
            return true;
        }
        // 3. 检查区块的父区块是否存在
        if (getBlockByHash(block.getPreviousHash()) == null) {
            log.warn("父区块不存在，哈希：{}", CryptoUtil.bytesToHex(block.getPreviousHash()));
            return false;
        }
        // 4. 获取父区块高度 新区块与父区块高度是否连续
        Block parentBlock = getBlockByHash(block.getPreviousHash());
        if (parentBlock.getHeight() + 1 != block.getHeight()) {
            log.warn("区块高度不连续，父区块高度：{}，当前区块高度：{}", parentBlock.getHeight(), block.getHeight());
            return false;
        }
        // 6. 验证区块中的交易
        Result<String> validateTransactionsInBlock = validateTransactionsInBlock(block);
        if (!validateTransactionsInBlock.getCode().equals(SC_OK_200)) {
            log.warn("区块中的交易验证失败，哈希：{}", CryptoUtil.bytesToHex(block.getHash()));
            log.error(validateTransactionsInBlock.getMessage());
            return false;
        }
        // 7. 处理区块（保存、更新UTXO等）  只要区块通过验证就 保存区块 保存区块产生的UTXO
        processValidBlock(block);
        //广播区块
        new Thread(() -> {
            log.info("区块验证成功,广播区块");
        });
        return true;
    }

    /**
     * 验证CoinBase交易
     */
    private Result<String> isValidCoinBaseTransaction(Transaction tx, long blockHeight) {
        if (tx == null || tx.getInputs() == null || tx.getInputs().size() != 1) {
            return Result.error("交易格式错误");
        }
        TXInput input = tx.getInputs().get(0);
        if (input.getTxId() == null) {
            return Result.error("CoinBase交易输入的txid为空 正确应该是全零");
        }
        if (input.getVout() != 0) {
            return Result.error("CoinBase交易的输出索引应该是零");
        }
        //获取交易所在区块高度
        long coinbaseReward = calculateBlockReward(blockHeight);
        long totalOutput = tx.getOutputs().stream()
                .mapToLong(TXOutput::getValue)
                .sum();
        if (totalOutput > coinbaseReward) {
            return Result.error("交易金额错误,正确的区块奖励应该是: "+coinbaseReward+" 聪");
        }
        return Result.ok("coinBase验证成功");
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
    private Result<String> validateTransactionsInBlock(Block block) {
        // 1. 验证CoinBase交易  区块中第一笔交易一定是CoinBase交易
        List<Transaction> transactions = block.getTransactions();
        Transaction coinbaseTx = transactions.get(0);
        Result<String> validCoinBaseTransaction = isValidCoinBaseTransaction(coinbaseTx, block.getHeight());
        if (!Objects.equals(validCoinBaseTransaction.getCode(), SC_OK_200)) {
            return validCoinBaseTransaction;
        }
        //去除第一个CoinBase交易
        transactions.remove(0);
        // 3. 按顺序验证所有交易（不包括CoinBase）
        for (int i = 0; i < transactions.size(); i++) {
            Transaction tx = block.getTransactions().get(i);
            Result<String> verifyTransaction = verifyTransaction(tx);
            if (!Objects.equals(verifyTransaction.getCode(), SC_OK_200)) {
                return verifyTransaction;
            }
        }
        return Result.ok("所有交易验证成功");
    }


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


    //应用区块中的交易 添加交易产生的UTXO 销毁交易引用的UTXO
    public void applyBlock(Block block) {
        // 1. 处理所有交易（包括CoinBase）
        for (int i = 0; i < block.getTransactions().size(); i++) {
            Transaction tx = block.getTransactions().get(i);
            //添加交易到区块的索引
            if (i == 0){
                //coinBase 没有输入 不用处理
            }
            // 2. 处理交易输入 删除引用的UTXO
            if (i > 0) {
                for (TXInput input : tx.getInputs()) {
                    deleteUTXO(input.getTxId(), input.getVout());
                }
            }
            // 3. 处理交易输出，添加新的UTXO
            for (int j = 0; j < tx.getOutputs().size(); j++) {
                String utxoKey = getUTXOKey(tx.getTxId(), j);
                //新增UTXO


            }
        }
    }


    //回滚区块中交易 销毁交易产生的UTXO 重新添加交易引用的UTXO
    public void rollbackBlock(Block block) {
        for (int i = 0; i < block.getTransactions().size(); i++) {
            Transaction tx = block.getTransactions().get(i);
            if (i == 0){
                //这是第一笔交易 是coinBase交易
                //CoinBase不用处理输入
                //撤销输出的UTXO
                for (int j = 0; j < tx.getOutputs().size(); j++) {
                    deleteUTXO(tx.getTxId(), j);

                }
            }
            if (i > 0) {
                //恢复输入的UTXO
                for (TXInput input : tx.getInputs()) {
                    // 获取输入引用的原始交易ID和输出索引
                    byte[] referencedTxId = input.getTxId();
                    int referencedVout = input.getVout();
                    //新增UTXO
                    //根据交易ID查询所在的区块
                    Block referencedBlock  = getBlockByTxId(input.getTxId());
                    if (referencedBlock != null) {
                        //查询引用的交易
                        Transaction referencedTx = findTransactionInBlock(referencedBlock, referencedTxId);
                        if (referencedTx != null && referencedVout < referencedTx.getOutputs().size()) {
                            TXOutput referencedOutput = referencedTx.getOutputs().get(referencedVout);
                            // 重建UTXO并添加到集合
                            UTXO utxo = new UTXO();
                            utxo.setTxId(referencedTxId);
                            utxo.setVout(referencedVout);
                            utxo.setValue(referencedOutput.getValue());
                            utxo.setScriptPubKey(referencedOutput.getScriptPubKey());

                            // 添加到UTXO存储
                            log.info("回滚UTXO: txId={}, vout={}",
                                    CryptoUtil.bytesToHex(referencedTxId), referencedVout);

                        }
                    }
                }
                //撤销输出的UTXO
                for (int j = 0; j < tx.getOutputs().size(); j++) {
                    deleteUTXO(tx.getTxId(), j);
                }
            }
        }


        // 1. 移除区块添加的UTXO
        for (Transaction tx : block.getTransactions()) {
            for (int j = 0; j < tx.getOutputs().size(); j++) {
                deleteUTXO(tx.getTxId(), j);

            }

        }
        // 2. 恢复区块花费的UTXO
        for (int i = 1; i < block.getTransactions().size(); i++) {
            Transaction tx = block.getTransactions().get(i);

        }
    }

    private Transaction findTransactionInBlock(Block referencedBlock, byte[] referencedTxId) {

        return null;
    }

    private Block getBlockByTxId(byte[] txId) {
        return null;
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
        log.info("高度9的区块哈希: {}", CryptoUtil.bytesToHex(getMainBlockHashByHeight(block.getHeight()-1)));

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
    public byte[] getGenesisBlockHash() {
        return GENESIS_BLOCK_HASH;
    }

    /**
     * 获取创世区块
     * @return
     */
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

        // 4. 验证交易合法性（略，需结合UTXO验证）
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
    public Block getBlockByHash(byte[] hash) {
        // 从数据库中获取区块
        return popStorage.getBlockByHash(hash);
    }


    /**
     * 获取当前区块信息
     * @return
     */
    public Result<BlockChain> getBlockChainInfo() {
        return Result.ok();
    }

    /**
     * 获取区块信息
     * @param blockHashHex
     * @return
     */
    public Result<BlockDTO> getBlock(String blockHashHex) {
        Block block = popStorage.getBlockByHash(CryptoUtil.hexToBytes(blockHashHex));
        if (block == null){
            return Result.error("区块不存在");
        }
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
        return Result.ok(blockDTO);
    }

    public Result<BlockDTO> getBlock(long height) {
        Block block = popStorage.getMainBlockByHeight(height);
        if (block == null){
            return Result.error("区块不存在");
        }
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
        return Result.ok(blockDTO);
    }

    /**
     * 获取UTXO
     */
    public UTXO getUTXO(byte[] txId, int vout) {
        return popStorage.getUTXO(txId, vout);
    }

    /**
     * 主链最新高度
     */
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
        //交易到区块的索引
        //查询区块
        //获取区块中的交易
        return Result.ok();
    }
}
