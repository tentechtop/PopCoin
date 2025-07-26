package com.pop.popcoinsystem.service;

import com.pop.popcoinsystem.data.block.Block;
import com.pop.popcoinsystem.data.block.BlockDTO;
import com.pop.popcoinsystem.data.block.BlockVO;
import com.pop.popcoinsystem.data.blockChain.BlockChain;
import com.pop.popcoinsystem.data.enums.UTXOStatus;
import com.pop.popcoinsystem.data.script.Script;
import com.pop.popcoinsystem.data.script.ScriptPubKey;
import com.pop.popcoinsystem.data.script.ScriptSig;
import com.pop.popcoinsystem.data.storage.POPStorage;
import com.pop.popcoinsystem.data.transaction.*;
import com.pop.popcoinsystem.data.vo.result.Result;
import com.pop.popcoinsystem.util.CryptoUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.util.*;

import static com.pop.popcoinsystem.data.script.Script.OP_RETURN;
import static com.pop.popcoinsystem.util.CryptoUtil.ECDSASigner.verifySignature;
import static com.pop.popcoinsystem.util.Numeric.hexStringToByteArray;

@Slf4j
@Service
public class BlockChainService {

    @Lazy
    @Resource
    private MiningService miningService;

    // 系统启动时执行
    private void initBlockChain() {
        // 检查是否已存在创世区块
        if (getBlockByHash(getGenesisBlockHash()) == null) {
            Block genesisBlock = createGenesisBlock();
            //创建创世区块并保存

        }
    }

    // CoinBase交易成熟度要求
    private static final int COINBASE_MATURITY = 100;

    //创建创世区块
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
        long genesisTime = 1620000000; // 示例时间戳（2021-05-03）
        genesisBlock.setTime(genesisTime);
        genesisBlock.setMedianTime(genesisTime);

        // 3. 设置难度相关参数（创世区块难度通常较低）
        genesisBlock.setDifficulty(1);
        // 比特币创世区块难度目标：0x1d00ffff（这里使用相同值）
        genesisBlock.setDifficultyTarget(hexStringToByteArray("1d00ffff"));
        genesisBlock.setChainWork("0000000000000000000000000000000000000000000000000000000100010001");

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
        genesisBlock.setStrippedSize(285); // 示例值
        genesisBlock.setSize(285);
        genesisBlock.setWeight(1140); // 4倍size（隔离见证权重计算）

        // 7. 计算区块哈希（需要找到符合难度的nonce）
        // 创世区块的nonce是固定值，通过暴力计算得到
        genesisBlock.setNonce(2083236893); // 示例nonce值（类似比特币创世块）
        byte[] blockHash = Block.calculateHash(genesisBlock);
        genesisBlock.setHash(blockHash);

        // 8. 设置工作量证明和确认数
        genesisBlock.setChainWork("0000000000000000000000000000000000000000000000000000000100010001");


        log.info("创世区块创建成功，哈希: {}", CryptoUtil.bytesToHex(blockHash));
        return genesisBlock;
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
        return coinbaseTx;
    }

    /**
     * 验证交易
     * 交易验证成功后 广播交易 如果本节点是矿工节点 则再添加到交易池 由矿工打包
     * 将验证结果返回
     */
    public Result<String> verifyTransaction(Transaction transaction) {
        //验证交易
        // 1. 基础格式验证
        if (transaction == null || transaction.getInputs() == null || transaction.getOutputs() == null) {
            log.error("交易格式无效");
            return Result.error("交易格式无效");
        }
        //是普通交易 还是 隔离见证交易

        if (transaction.isSegWit()){
            //普通交易验证
            // 2. 验证交易ID
            byte[] calculatedTxId = Transaction.calculateTxId(transaction);
            if (!Arrays.equals(calculatedTxId, transaction.getTxId())) {
                log.error("交易ID不匹配");
                return Result.error("交易ID不匹配");
            }
            List<TXInput> inputs = transaction.getInputs();

            for (TXInput input : transaction.getInputs()) {
                UTXO utxo = getUTXO(input.getTxId(), input.getVout());
                String utxoKey = getUTXOKey(input.getTxId(), input.getVout());
                if (utxo == null) {
                    log.error("引用的UTXO不存在: {}", utxoKey);
                    return Result.error("引用的UTXO不存在");
                }
                // 检查是否已花费  只要是没删除的都是可以花费的


                //UTXO 是否是coinBase UTXO 是否成熟

                //通过UTXO 查询交易 通过交易查询所在区块  看看区块高度是否满足要求




                // 验证数字签名


            }
            // 添加交易输出到临时UTXO集合
            for (int j = 0; j < transaction.getOutputs().size(); j++) {
                String utxoKey = CryptoUtil.bytesToHex(transaction.getTxId()) + ":" + j;
                UTXO utxo = new UTXO();
                utxo.setTxId(transaction.getTxId());
                utxo.setVout(j);
                utxo.setValue(transaction.getOutputs().get(j).getValue());
                utxo.setScriptPubKey(utxo.getScriptPubKey());
                //TODO 保存
            }






        }else {
            //隔离见证交易验证




        }
        // 4. 验证交易金额
        long inputSum = transaction.getInputs().stream()
                .map(input -> {
                    UTXO utxo = getUTXO(input.getTxId(), input.getVout());
                    return utxo != null ? utxo.getValue() : 0;
                })
                .mapToLong(Long::longValue)
                .sum();
        long outputSum = transaction.getOutputs().stream()
                .mapToLong(output -> output.getValue())
                .sum();
        if (inputSum < outputSum) {
            log.error("交易输出金额大于输入金额");
            return Result.error("交易输出金额大于输入金额");
        }




        //验证通过后

        //广播交易
        new Thread(() -> {
            log.info("交易验证成功,广播交易");
        });

        //如果本本节点是矿工节点 将交易提交到交易池
        miningService.addTransaction(transaction);
        return Result.OK("交易验证成功");
    }


    /**
     * 验证区块中的所有交易
     */
    private boolean validateTransactionsInBlock(Block block) {
        // 1. 验证CoinBase交易
        Transaction coinbaseTx = block.getTransactions().get(0);
        if (!isValidCoinBaseTransaction(coinbaseTx, block.getHeight())) {
            log.error("CoinBase交易无效");
            return false;
        }
        // 3. 按顺序验证所有交易（包括CoinBase）
        for (int i = 0; i < block.getTransactions().size(); i++) {
            Transaction tx = block.getTransactions().get(i);
            // 特殊处理CoinBase交易
            if (i == 0) {

                // 添加CoinBase交易的输出到临时UTXO集合
                for (int j = 0; j < tx.getOutputs().size(); j++) {
                    UTXO utxo = new UTXO();
                    utxo.setTxId(tx.getTxId());
                    utxo.setVout(j);
                    utxo.setValue(tx.getOutputs().get(j).getValue());

                }
                continue;
            }






        }

        return true;
    }




    /**
     * 验证CoinBase交易
     */
    private boolean isValidCoinBaseTransaction(Transaction tx, long blockHeight) {
        if (tx == null || tx.getInputs() == null || tx.getInputs().size() != 1) {
            return false;
        }
        TXInput input = tx.getInputs().get(0);
        if (input.getTxId() == null || !Arrays.equals(input.getTxId(), getGenesisBlockHash())) {
            return false;
        }
        if (input.getVout() != 0) {
            return false;
        }
        // 验证CoinBase奖励金额
        //获取交易所在区块高度

        long coinbaseReward = calculateCoinbaseReward(blockHeight);
        long totalOutput = tx.getOutputs().stream()
                .mapToLong(output -> output.getValue())
                .sum();
        if (totalOutput > coinbaseReward) {
            return false;
        }
        return true;
    }

    private long calculateCoinbaseReward(long blockHeight) {
        // 初始奖励（单位：聪）
        long initialReward = 50 * 100000000; // 50 BTC

        // 每210000个区块减半
        long halvings = blockHeight / 210000;

        // 超过64次减半后奖励为0
        if (halvings >= 64) {
            return 0;
        }

        // 计算当前奖励
        return initialReward >> halvings;
    }


    /**
     * 验证区块
     * UTXO 并非仅在交易验证成功后产生，而是在交易被成功打包进区块并经过网络确认后，才成为有效的 UTXO。
     * 一、UTXO 集合更新的核心步骤
     * 当一个新的区块被确认后，更新 UTXO 集合的正确流程是：
     *
     * 遍历区块中的所有交易（包括 CoinBase 交易和普通交易）
     * 处理每个交易的输入：
     * 对于每个输入，找到其引用的 UTXO（通过txid:vout定位）
     * 将这些 UTXO 标记为 “已花费” 并从 UTXO 集合中移除
     * 处理每个交易的输出：
     * 对于每个输出，检查其是否有效（如金额为正、脚本格式合法）
     * 将有效的输出添加到 UTXO 集合中，格式通常为：
     * {txid:vout → [金额, 锁定脚本, 区块高度]}
     * 二、关键细节与注意事项
     * CoinBase 交易的特殊性
     * CoinBase 交易是区块中的第一笔交易，其输出创建的 UTXO 有成熟度要求（通常需要 100 个确认后才能使用）
     * CoinBase 交易没有输入，因此无需移除任何 UTXO
     * 交易验证顺序
     * 必须按区块中交易的顺序处理，因为后续交易可能依赖前面交易创建的 UTXO
     * 数据结构优化
     * 实际系统中，UTXO 集合通常使用键值数据库（如 LevelDB）存储，以高效支持快速查找和更新
     * 为提高查询效率，可能会维护辅助索引（如地址到 UTXO 的映射）
     * 回滚机制
     * 若发生链重组（如分叉被更长链替代），需要反向操作：
     * 移除该区块添加的 UTXO
     * 恢复该区块移除的 UTXO
     */
    public boolean verifyBlock(Block block) {
        //验证区块
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
        if (!validateTransactionsInBlock(block)) {
            log.warn("区块中的交易验证失败，哈希：{}", CryptoUtil.bytesToHex(block.getHash()));
            return false;
        }
        // 7. 处理区块（保存、更新UTXO等）
        processValidBlock(block);
        // 8. 检查并处理分叉
        handleChainFork(block);


        //广播区块

        //保存区块

        //更新本地区块信息

        //更新UTXO  移除掉输入的UTXO 新增输出的UTXO

        //如果正在挖矿
        // 比特币的共识机制通过最长链原则实现全网一致性，
        // 而 “最长链” 的定义同时包含区块高度和 ** 累积工作量证明（PoW）** 两个维度。

        return true;
    }

    /**
     * 处理链分叉，实现最长链原则  最长一定是工作量最大
     */
    private void handleChainFork(Block newBlock) {
        //0 1 2 3 4
        //        4
        //分叉的产生：当两个或多个节点在几乎同一时间，基于主链的同一个 “末端区块” 挖出了新的区块时，网络会暂时出现两条并行的链（例如：主链原本是 A→B→C，此时节点 1 挖出 C→D，节点 2 挖出 C→E，形成 A→B→C→D 和 A→B→C→E 两条链）。
        //此时，节点 2 挖出的区块 E 就不是 “主链（此时可能是 C→D 链）” 的延续，而是另一条分支链的延续。

        long blockHeight = newBlock.getHeight();  //对比新区块的父哈希与主链哈希，检测是否为分叉。
        // 如果新区块不是主链的延续，检查是否应该切换到新链
        if (!Arrays.equals(newBlock.getPreviousHash(), getMainBlockHashByHeight(blockHeight - 1)  )) {
            // 计算新链的总难度
            long newChainDifficulty = calculateChainDifficulty(newBlock);

            // 计算当前主链的总难度
            long currentChainDifficulty = calculateCurrentChainDifficulty();

            // 如果新链难度更大，则切换到新链
            if (newChainDifficulty > currentChainDifficulty) {
                log.info("检测到更难的链，准备切换。新链难度: {}, 当前链难度: {}",
                        newChainDifficulty, currentChainDifficulty);
                switchToNewChain(newBlock);
            }
        }
    }

    private long calculateCurrentChainDifficulty() {
        long difficulty = 0;
        for (long i = 0; i <= getMainCurrentHeight(); i++) {
            byte[] blockHash = getMainBlockHashByHeight(i);
            if (blockHash != null) {
                Block block = getBlockByHash(blockHash);
                if (block != null) {
                    difficulty += calculateDifficulty(block.getDifficultyTarget());
                }
            }
        }
        return difficulty;
    }

    private long calculateChainDifficulty(Block tipBlock) {
        long difficulty = 0;
        Block current = tipBlock;
        while (current != null) {
            // 简化处理，实际中应该根据区块难度目标计算
            difficulty += calculateDifficulty(current.getDifficultyTarget());
            // 检查是否是创世区块
            if (Arrays.equals(current.getPreviousHash(), getGenesisBlockHash())) {
                break;
            }
            current = getBlockByHash(current.getPreviousHash());
        }


        return 0;
    }

    /**
     * 根据难度目标计算难度值
     */
    /**
     * 根据难度目标计算难度值
     */
    private long calculateDifficulty(byte[] difficultyTarget) {
        // 创世区块的目标值 (0x1d00ffff)
        // 这个值对应于比特币创世区块的难度目标
        // 实际实现中应该从配置中获取
        BigInteger genesisTarget = new BigInteger("00000000FFFF000000000000000000000000000000000000000000000000", 16);

        // 将难度目标字节数组转换为BigInteger
        // 注意：需要处理比特币的"紧凑格式"（Compact Size）
        // 难度目标以"紧凑格式"存储，前导字节表示指数，后面3字节表示系数
        // 格式：0xWWXXYYZZ → 0xXXYYZZ * 2^(8*(WW-3))
        if (difficultyTarget == null || difficultyTarget.length != 4) {
            return 1; // 默认为最低难度
        }

        // 解析紧凑格式的难度目标
        int exponent = difficultyTarget[0] & 0xFF;
        byte[] coefficientBytes = new byte[4];
        coefficientBytes[0] = 0; // 确保是正数
        coefficientBytes[1] = difficultyTarget[1];
        coefficientBytes[2] = difficultyTarget[2];
        coefficientBytes[3] = difficultyTarget[3];

        BigInteger coefficient = new BigInteger(coefficientBytes);
        BigInteger target = coefficient.shiftLeft(8 * (exponent - 3));
        // 计算难度值：创世目标 / 当前目标
        // 由于Java的BigInteger不能直接转换为long而不丢失精度
        // 我们使用double来近似表示，但在实际应用中可能需要更精确的表示
        double difficulty = genesisTarget.doubleValue() / target.doubleValue();
        // 转换为long（取整）
        return (long) difficulty;
    }


    private void processValidBlock(Block block) {
        // 1. 保存区块到数据库

        // 2. 更新区块索引

        // 3. 更新UTXO集合
        updateUTXOSet(block);

        // 4. 更新链状态
        updateChainState(block);
    }

    private void updateChainState(Block block) {
        // 1. 如果新区块高度大于当前主链高度，或者难度更大，则成为新的主链
        long blockHeight = block.getHeight();
        long currentHeight = getMainCurrentHeight();
        if (blockHeight > currentHeight ||
                (blockHeight == currentHeight && isDifficultyGreater(block.getHash(), getMainBlockHashByHeight(currentHeight)))) {
            // 如果新区块的父区块不是当前主链的最后一个区块，说明出现了分叉
            if (!Arrays.equals(block.getPreviousHash(), getMainBlockHashByHeight(currentHeight) )) {
                log.info("检测到分叉，高度: {}, 主链: {}, 新链: {}",
                        blockHeight,
                        CryptoUtil.bytesToHex(getMainBlockHashByHeight(currentHeight)),
                        CryptoUtil.bytesToHex(block.getHash()));

                // 切换到更长/更难的链
                switchToNewChain(block);
            } else {
                // 正常扩展主链
                //添加区块
                //更新主链高度


                log.info("主链扩展到高度: {}, 哈希: {}", blockHeight, CryptoUtil.bytesToHex(block.getHash()));
            }
        } else {
            // 2. 否则，将区块添加到备选链  高度冲突  private final Map<Long, List<byte[]>> alternativeChains = new ConcurrentHashMap<>();


            log.info("备选链添加区块，高度: {}, 哈希: {}", blockHeight, CryptoUtil.bytesToHex(block.getHash()));
        }

    }


    /**
     * 切换到新链，处理分叉回滚
     */
    private void switchToNewChain(Block newTipBlock) {
        // 1. 找到两个链的共同祖先
        Block commonAncestor = findCommonAncestor(newTipBlock);
        long ancestorHeight = commonAncestor.getHeight();
        // 2. 回滚当前主链到共同祖先
        List<Block> blocksToUndo = new ArrayList<>();
        for (long i = getMainCurrentHeight(); i > ancestorHeight; i--) {
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
            undoBlockUTXOs(blocksToUndo.get(i));
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
            updateUTXOSet(block);
        }
        // 6. 更新主链和当前高度


         // 7. 清理备选链

        log.info("成功切换到新链，新高度: {}, 新哈希: {}",
                getMainCurrentHeight(), CryptoUtil.bytesToHex(newTipBlock.getHash()));
    }

    /**
     * 撤销区块对UTXO的修改
     */
    private void undoBlockUTXOs(Block block) {
        // 1. 移除区块添加的UTXO
        for (Transaction tx : block.getTransactions()) {
            for (int j = 0; j < tx.getOutputs().size(); j++) {
                deleteUTXO(tx.getTxId(), j);


            }
        }

        // 2. 恢复区块花费的UTXO
        for (int i = 1; i < block.getTransactions().size(); i++) {
            Transaction tx = block.getTransactions().get(i);
            for (TXInput input : tx.getInputs()) {
                // 从历史记录或数据库中恢复UTXO  //更新为为花费
                UTXO utxo = getUTXO(input.getTxId(), input.getVout());
                if (utxo != null) {
                    utxo.setStatus(UTXOStatus.NOSPENT.getValue());
                }
                //TODO 更新

            }
        }
    }

    private Block findCommonAncestor(Block block) {
        Set<byte[]> newChainHashes = new HashSet<>();
        Block current = block;

        // 收集新链上的所有区块哈希
        while (current != null) {
            byte[] hash = current.getHash();
            newChainHashes.add(hash);
            // 检查是否是创世区块
            if (Arrays.equals(current.getPreviousHash(), getGenesisBlockHash())) {
                break;
            }
            current = getBlockByHash(hash);
        }

        // 从当前主链顶端开始向下查找共同祖先
        current = getBlockByHash(  getMainBlockHashByHeight(getMainCurrentHeight()) );
        while (current != null) {
            String hashStr = CryptoUtil.bytesToHex(current.getHash());
            if (newChainHashes.contains(hashStr)) {
                return current;
            }

            // 检查是否是创世区块
            if (Arrays.equals(current.getPreviousHash(), getGenesisBlockHash())) {
                break;
            }
            current = getBlockByHash(current.getPreviousHash());
        }

        // 如果没找到，返回创世区块
        return getGenesisBlock();
    }

    /**
     * 获取创世区块
     */
    private Block getGenesisBlock() {

        return null;
    }

    /**
     * 获取创世区块hash
     * @return
     */
    public byte[] getGenesisBlockHash() {




        return null;
    }


    //获取当前主链高度
    public long getMainCurrentHeight() {
        return 0;
    }
    /**
     * 主链 通过高度获取区块hash
     */
    public byte[] getMainBlockHashByHeight(long height) {
        // 从数据库中获取区块
        return null;
    }







    /**
     * 比较两个哈希的难度，返回true如果第一个哈希难度更大
     */
    private boolean isDifficultyGreater(byte[] hash1, byte[] hash2) {
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


    private void updateUTXOSet(Block block) {
        // 1. 处理所有交易（包括CoinBase）
        // 1. 处理所有交易（包括CoinBase）
        for (int i = 0; i < block.getTransactions().size(); i++) {
            Transaction tx = block.getTransactions().get(i);

            // 2. 处理交易输入，标记引用的UTXO为已花费
            if (i > 0) { // CoinBase交易没有输入
                for (TXInput input : tx.getInputs()) {
                    UTXO utxo = getUTXO(input.getTxId(), input.getVout());
                    if (utxo != null) {
                        utxo.setStatus(UTXOStatus.SPENT.getValue());
                        // 可以选择从集合中移除已花费的UTXO，或者保留但标记为已花费
                        // utxoSet.remove(utxoKey);

                        //每个月清理一次已经花费的UTXO
                    }
                }
            }
            // 3. 处理交易输出，添加新的UTXO
            for (int j = 0; j < tx.getOutputs().size(); j++) {
                String utxoKey = CryptoUtil.bytesToHex(tx.getTxId()) + ":" + j;
                boolean isCoinbase = (i == 0);





            }
        }
        
    }



    /**
     * 验证区块合法性
     */
    private boolean validateBlock(Block block) {
        // 1. 验证区块格式
        if (block == null || block.getTransactions().isEmpty()) {
            log.error("区块格式无效");
            return false;
        }
        // 2. 验证区块哈希
        byte[] calculatedHash = Block.calculateHash(block);
        if (!Arrays.equals(calculatedHash, block.getHash())) {
            log.error("区块哈希不匹配，计算值: {}, 区块值: {}",
                    CryptoUtil.bytesToHex(calculatedHash),
                    CryptoUtil.bytesToHex(block.getHash()));
            return false;
        }
        // 3. 验证难度目标
        if (!isValidHash(block.getHash(), block.getDifficultyTarget())) {
            log.error("区块哈希不满足难度目标");
            return false;
        }

        // 4. 验证交易合法性（略，需结合UTXO验证）
        return true;
    }


    //根据hash获取区块
    public Block getBlockByHash(byte[] hash) {
        // 从数据库中获取区块
        return null;
    }



    //备选链 通过高度获取 区块hash
    public byte[] getBackupBlockByHeight(long height) {
        // 从数据库中获取区块
        return null;
    }
    



    /**
     * 检查哈希是否满足难度目标
     */
    private boolean isValidHash(byte[] hash, byte[] difficultyTarget) {
        if (hash == null || difficultyTarget == null || hash.length != 32 || difficultyTarget.length != 32) {
            return false;
        }
        // 难度目标格式为: [前导零字节数, 目标值的剩余部分]
        // 简化处理，假设difficultyTarget直接是目标哈希值
        for (int i = 0; i < hash.length; i++) {
            if (hash[i] < difficultyTarget[i]) {
                return true;
            } else if (hash[i] > difficultyTarget[i]) {
                return false;
            }
        }
        return true;
    }





    /**
     * 从一个输入中获取UTXO
     */
    public UTXO getUTXOByInput(TXInput input) {
        // 从数据库中获取 UTXO

        return null;
    }
    //根据交易ID和vout获取UTXO
    public UTXO getUTXO(byte[] txId, int vout) {
        // 从数据库中获取 UTXO
        String utxoKey = CryptoUtil.bytesToHex(txId) + ":" + vout;

        return null;
    }

    public String getUTXOKey(byte[] txId, int vout) {
        // 从数据库中获取 UTXO
        return CryptoUtil.bytesToHex(txId) + ":" + vout;
    }


    /**
     * 获取最新区块Hash
     */
    public byte[] getLatestBlockHash() {
        return new byte[]{};
    }
    /**
     * 获取区块高度
     */
    public long getBlockHeight(byte[] hash) {
        //获取区块
        //获取高度


        return 100;
    }


    /**
     * 获取区块链信息
     * @return
     */
    public Result<BlockChain> getBlockChainInfo() {
        return Result.OK();
    }

    /**
     * 查询区块
     * @param blockVO
     * @return
     */
    public Result<BlockDTO> getBlock(BlockVO blockVO) {

        return Result.OK();
    }

    /**
     * 查询区块高度
     * @param hash
     * @return
     */
    public Result<Long> getBlockHeight(String hash) {
        byte[] bytes = CryptoUtil.hexToBytes(hash);
        POPStorage instance = POPStorage.getInstance();
        return Result.OK();
    }


    /**
     * 删除UTXO
     */
    public void deleteUTXO(byte[] txId, int vo) {
        // 从数据库中删除 UTXO

    }
}
