package com.pop.popcoinsystem.service;

import com.pop.popcoinsystem.data.block.Block;
import com.pop.popcoinsystem.data.block.BlockDTO;
import com.pop.popcoinsystem.data.block.BlockVO;
import com.pop.popcoinsystem.data.blockChain.BlockChain;
import com.pop.popcoinsystem.data.storage.POPStorage;
import com.pop.popcoinsystem.data.transaction.Transaction;
import com.pop.popcoinsystem.data.vo.result.Result;
import com.pop.popcoinsystem.util.CryptoUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class BlockChainService {

    @Lazy
    @Resource
    private MiningService miningService;

    /**
     * 验证交易
     * 交易验证成功后 广播交易 如果本节点是矿工节点 则再添加到交易池 由矿工打包
     * 将验证结果返回
     */
    public Result<String> verifyTransaction(Transaction transaction) {
        //验证交易

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
     * 获取最新区块Hash
     */
    public byte[] getLatestBlockHash() {
        return new byte[]{};
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
}
