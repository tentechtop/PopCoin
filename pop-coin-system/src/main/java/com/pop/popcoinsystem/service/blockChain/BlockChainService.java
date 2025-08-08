package com.pop.popcoinsystem.service.blockChain;

import com.pop.popcoinsystem.aop.annotation.RpcServiceAlias;
import com.pop.popcoinsystem.data.block.Block;
import com.pop.popcoinsystem.data.block.BlockBody;
import com.pop.popcoinsystem.data.block.BlockDTO;
import com.pop.popcoinsystem.data.block.BlockHeader;
import com.pop.popcoinsystem.data.blockChain.BlockChain;
import com.pop.popcoinsystem.data.enums.SigHashType;
import com.pop.popcoinsystem.data.transaction.UTXO;
import com.pop.popcoinsystem.data.transaction.UTXOSearch;
import com.pop.popcoinsystem.data.transaction.Transaction;
import com.pop.popcoinsystem.data.transaction.dto.TransactionDTO;
import com.pop.popcoinsystem.data.vo.result.Result;
import com.pop.popcoinsystem.data.vo.result.ListPageResult;
import com.pop.popcoinsystem.data.vo.result.TPageResult;
import com.pop.popcoinsystem.network.service.KademliaNodeServer;
import com.pop.popcoinsystem.network.common.NodeInfo;

import java.net.ConnectException;
import java.util.List;
import java.util.Map;

@RpcServiceAlias("BlockChainService")// 标记为需要注册的RPC服务
public interface BlockChainService {

    /**
     * 验证交易合法性
     */
    boolean verifyTransaction(Transaction transaction);

    /**
     * 验证区块合法性（包括交易、PoW、链连续性等）
     */
    boolean verifyBlock(Block block, boolean broadcastMessage);

    /**
     * 创建创世区块
     */
    Block createGenesisBlock();

    /**
     * 验证交易并添加到交易池（验证成功后广播）
     */
    boolean verifyAndAddTradingPool(Transaction transaction,boolean broadcastMessage);



    /**
     * 验证区块头
     */
    boolean verifyBlockHeader(BlockHeader blockHeader);

    /**
     * 计算交易手续费（输入总额 - 输出总额）
     */
    long getFee(Transaction transaction);

    /**
     * 计算每字节手续费
     */
    double getFeePerByte(Transaction tx);

    /**
     * 获取主链最新区块
     */
    Block getMainLatestBlock();

    /**
     * 获取创世区块哈希
     */
    byte[] getGenesisBlockHash();

    /**
     * 获取创世区块
     */
    Block getGenesisBlock();

    /**
     * 根据哈希获取区块
     */
    Block getBlockByHash(byte[] hash);

    /**
     * 根据哈希获取区块体
     */
    BlockBody getBlockByHashList(List<byte[]> hashList);

    /**
     * 获取区块链基本信息（高度、最新哈希等）
     */
    Result<BlockChain> getBlockChainInfo();

    /**
     * 根据区块哈希（十六进制）获取区块详情
     */
    Result<BlockDTO> getBlock(String blockHashHex);

    /**
     * 根据区块高度获取区块详情
     */
    Result<BlockDTO> getBlock(long height);

    /**
     * 根据交易ID和输出索引获取UTXO
     */
    UTXO getUTXO(byte[] txId, int vout);

    /**
     * 根据UTXO唯一键获取UTXO
     */
    UTXO getUTXO(String utxoKey);

    /**
     * 获取主链最新高度
     */
    long getMainLatestHeight();

    /**
     * 删除指定UTXO（交易输入花费时调用）
     */
    void deleteUTXO(byte[] txId, int vout);

    /**
     * 获取主链最新区块哈希
     */
    byte[] getMainLatestBlockHash();

    /**
     * 根据高度获取主链区块
     */
    Block getMainBlockByHeight(long height);

    /**
     * 根据交易ID（十六进制）获取交易详情
     */
    Result<TransactionDTO> getTransaction(String txId);

    /**
     * 分页查询UTXO列表
     */
    ListPageResult<UTXO> queryUTXOPage(int pageSize, String cursor);

    /**
     * 根据脚本哈希分页查询UTXO（用于地址余额查询）
     */
    TPageResult<UTXOSearch> selectUtxoAmountsByScriptHash(byte[] scriptHash, int pageSize, String lastUtxoKey);


    /**
     * 根据高度范围查询区块列表
     */
    Result getBlockByRange(long start, long end);


    /**
     * 将验证通过的区块添加到主链
     */
    void addBlockToMainChain(Block validBlock);

    /**
     * 比较本地与远程节点的区块差异，并发起同步请求
     */
    void compareAndSync(KademliaNodeServer nodeServer, NodeInfo remoteNode,
                        long localHeight, byte[] localHash, byte[] localWork,
                        long remoteHeight, byte[] remoteHash, byte[] remoteWork)
            throws ConnectException, InterruptedException;

    List<Block> getBlockByStartHashAndEndHashWithLimit(byte[] startHash, byte[] endHash, int batchSize);

    byte[] getMainBlockHashByHeight(long batchEndHeight);

    BlockDTO getBlockDto(Block block);

    Result getBalance(String address);

    Result startSync();

    Result getSyncProgress();

    Result stopSync();

    BlockHeader getBlockHeader(long height);

    void addBlockHeader(BlockHeader header,long height);

    List<BlockHeader> getBlockHeaders(long startHeight, int count);

    Map<Long,byte[]> getBlockHashes(List<Long> heightsToCheck);

    byte[] getBlockHash(long mid);

    void refreshLatestHeight();

    Result<BlockDTO> getTransactionBlock(String txId);

    Result getAllUTXO();

    List<Block> getBlocksByHashes(List<byte[]> batchHashes);


    List<Block> getBlocksByHeights(List<Long> batchHeights);
}