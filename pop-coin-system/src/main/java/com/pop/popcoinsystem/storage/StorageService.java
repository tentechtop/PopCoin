package com.pop.popcoinsystem.storage;

import com.pop.popcoinsystem.data.block.Block;
import com.pop.popcoinsystem.data.block.BlockBody;
import com.pop.popcoinsystem.data.block.BlockHeader;
import com.pop.popcoinsystem.data.miner.Miner;
import com.pop.popcoinsystem.data.script.ScriptPubKey;
import com.pop.popcoinsystem.data.transaction.Transaction;
import com.pop.popcoinsystem.data.transaction.UTXO;
import com.pop.popcoinsystem.data.transaction.UTXOSearch;
import com.pop.popcoinsystem.data.vo.result.AnyResult;
import com.pop.popcoinsystem.data.vo.result.TPageResult;
import com.pop.popcoinsystem.data.vo.result.ListPageResult;
import com.pop.popcoinsystem.network.common.ExternalNodeInfo;
import com.pop.popcoinsystem.network.common.NodeSettings;
import com.pop.popcoinsystem.service.blockChain.asyn.SyncProgress;
import com.pop.popcoinsystem.service.blockChain.asyn.SyncTaskRecord;
import com.pop.popcoinsystem.service.blockChain.asyn.SyncStatus;
import com.pop.popcoinsystem.util.ByteUtils;
import com.pop.popcoinsystem.util.CryptoUtil;
import com.pop.popcoinsystem.util.SerializeUtils;
import lombok.extern.slf4j.Slf4j;
import org.rocksdb.*;

import java.io.File;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static com.pop.popcoinsystem.constant.BlockChainConstants.*;
import static com.pop.popcoinsystem.util.YamlReaderUtils.getNestedValue;
import static com.pop.popcoinsystem.util.YamlReaderUtils.loadYaml;

@Slf4j
public class StorageService {

    //存储路径
    private static String storagePath = STORAGE_PATH;
    static {
        Map<String, Object> config = loadYaml("application.yml");
        if (config != null) {
            storagePath = (String) getNestedValue(config, "system.storagePath");
            log.debug("读取存储路径: " + storagePath);
        }
    }

    // 数据库存储路径
    private static String DB_PATH = storagePath+"/network" + NET_VERSION + ".db/";

    //这些KEY都保存在BLOCK_CHAIN 中 因为他们单独特殊
    private static final byte[] KEY_UTXO_COUNT = "key_utxo_count".getBytes();//UTXO总数
    private static final byte[] KEY_MAIN_LATEST_HEIGHT = "key_main_latest_height".getBytes();//主链当前高度 最新高度
    private static final byte[] KEY_MAIN_LATEST_BLOCK_HASH = "key_main_latest_block_hash".getBytes();

    //区块链当前工作总量chainWork
    private static final byte[] KEY_CHAIN_WORK = "key_chain_work".getBytes();

    //最新区块难度值
    private static final byte[] KEY_BLOCK_DIFFICULTY = "key_block_difficulty".getBytes();





    /*节点相关设置*/
    private static final byte[] KEY_NODE_SETTING = "key_node_setting".getBytes();
    private static final byte[] KEY_MINER = "key_miner".getBytes();





    /*更新主链当前高度*/
    public void updateMainLatestHeight(long height) {
        try {
            byte[] heightBytes = ByteUtils.toBytes(height);
            db.put(ColumnFamily.BLOCK_CHAIN.getHandle(), KEY_MAIN_LATEST_HEIGHT, heightBytes);
        } catch (RocksDBException e) {
            log.error("更新主链当前高度失败: height={}", height, e);
            throw new RuntimeException("更新主链当前高度失败", e);
        }
    }
    //更新主链当前区块hash
    public void updateMainLatestBlockHash(byte[] blockHash) {
        try {
            db.put(ColumnFamily.BLOCK_CHAIN.getHandle(), KEY_MAIN_LATEST_BLOCK_HASH, blockHash);
        } catch (RocksDBException e) {
            log.error("更新主链当前区块hash失败: blockHash={}", blockHash, e);
            throw new RuntimeException("更新主链当前区块hash失败", e);
        }
    }
    //获取主链最新的区块Hash
    public byte[] getMainLatestBlockHash() {
        try {
            return db.get(ColumnFamily.BLOCK_CHAIN.getHandle(), KEY_MAIN_LATEST_BLOCK_HASH);
        } catch (RocksDBException e) {
            log.error("获取主链当前区块hash失败", e);
            throw new RuntimeException("获取主链当前区块hash失败", e);
        }
    }
    /*获取主链当前高度*/
    public long getMainLatestHeight() {
        try {
            byte[] heightBytes = db.get(ColumnFamily.BLOCK_CHAIN.getHandle(), KEY_MAIN_LATEST_HEIGHT);
            if (heightBytes == null) {
                return 0;
            }
            return ByteUtils.bytesToLong(heightBytes);
        } catch (RocksDBException e) {
            log.error("获取主链当前高度失败", e);
            throw new RuntimeException("获取主链当前高度失败", e);
        }
    }
    //更新主链高度到区块的索引 保存主链中高度到区块hash索引
    public void addMainHeightToBlockIndex(long blockHeight, byte[] blockHash) {
        try {
            db.put(ColumnFamily.MAIN_BLOCK_CHAIN_INDEX.getHandle(), ByteUtils.toBytes(blockHeight), blockHash);
        } catch (RocksDBException e) {
            log.error("更新主链高度到区块的索引失败: blockHeight={}, blockHash={}", blockHeight, blockHash, e);
            throw new RuntimeException("更新主链高度到区块的索引失败", e);
        }
    }
    //主链索中 通过高度获取区块hash
    public byte[] getMainBlockHashByHeight(long height) {
        try {
            byte[] blockHash = db.get(ColumnFamily.MAIN_BLOCK_CHAIN_INDEX.getHandle(), ByteUtils.toBytes(height));
            if (blockHash == null) {
                return null;
            }
            return blockHash;
        } catch (RocksDBException e) {
            log.error("通过高度获取区块hash失败: height={}", height, e);
            throw new RuntimeException("通过高度获取区块hash失败", e);
        }
    }
    //删除主链中高度到区块hash索引
    public void deleteMainBlockHeight(long height) {
        try {
            byte[] heightBytes = ByteUtils.toBytes(height);
            db.delete(ColumnFamily.MAIN_BLOCK_CHAIN_INDEX.getHandle(), heightBytes);
        } catch (RocksDBException e) {
            log.error("删除主链高度失败: height={}", height, e);
            throw new RuntimeException("删除主链高度失败", e);
        }
    }


    public Block getMainBlockByHeight(long height) {
        //先获取hash
        byte[] blockHash = getMainBlockHashByHeight(height);
        return getBlockByHash(blockHash);
    }




    //备选链操作.............................................................................................................
    //新增备选链条 高度对应的 区块hash 备选链存储，用于处理分叉
    public void putALTBlockHeight(long height, byte[] hash) {
        try {
            byte[] heightBytes = ByteUtils.toBytes(height);  //List<byte[]>
            //先获取
            byte[] oldHash = db.get(ColumnFamily.ALT_BLOCK_CHAIN_INDEX.getHandle(), heightBytes);
            HashSet<byte[]> blockHash = new HashSet<>();
            if (oldHash == null) {
                blockHash.add(hash);
            }else {
                blockHash = (HashSet<byte[]>) SerializeUtils.deSerialize(oldHash);
                blockHash.add(hash);
            }
            db.put(ColumnFamily.ALT_BLOCK_CHAIN_INDEX.getHandle(), heightBytes, SerializeUtils.serialize(blockHash));
        } catch (RocksDBException e) {
            log.error("保存备选链高度失败: height={}", height, e);
            throw new RuntimeException("保存备选链高度失败", e);
        }
    }
    //删除备选链 中的一个区块缩影
    public void deleteALTBlockHeight(long height, byte[] hash) {
        try {
            byte[] heightBytes = ByteUtils.toBytes(height);
            byte[] oldHash = db.get(ColumnFamily.ALT_BLOCK_CHAIN_INDEX.getHandle(), heightBytes);
            HashSet<byte[]> blockHash = (HashSet<byte[]>) SerializeUtils.deSerialize(oldHash);
            blockHash.remove(hash);
            db.put(ColumnFamily.ALT_BLOCK_CHAIN_INDEX.getHandle(), heightBytes, SerializeUtils.serialize(blockHash));
        } catch (RocksDBException e) {
            log.error("删除备选链高度失败: height={}", height, e);
            throw new RuntimeException("删除备选链高度失败", e);
        }
    }
    //根据高度获取备选 该高度的所有备选
    public Set<byte[]> getALTBlockHashByHeight(long height) {
        try {
            byte[] heightBytes = ByteUtils.toBytes(height);
            byte[] blockHash = db.get(ColumnFamily.ALT_BLOCK_CHAIN_INDEX.getHandle(), heightBytes);
            HashSet<byte[]> bytes = new HashSet<>();
            if (blockHash == null){
                return bytes;
            }else {
                bytes = (HashSet<byte[]>) SerializeUtils.deSerialize(blockHash);
            }
            return bytes;
        } catch (RocksDBException e) {
            log.error("通过高度获取区块hash失败: height={}", height, e);
            throw new RuntimeException("通过高度获取区块hash失败", e);
        }
    }









    // ------------------------------ 数据操作 ------------------------------
    /**
     * 保存区块
     */
    public void addBlock(Block block) {
        try {
            byte[] blockHash = block.getHash();
            byte[] blockData = SerializeUtils.serialize(block);
/*            // 直接写入区块列族（键：区块哈希，值：序列化区块）
            db.put(ColumnFamily.BLOCK.getHandle(), blockHash, blockData);*/

            // 1. 拆分区块为头和体
            BlockHeader header = block.extractHeader();
            BlockBody body = block.extractBody();
            // 2. 存储区块头（到原BLOCK列族）
            byte[] headerData = SerializeUtils.serialize(header);
            db.put(ColumnFamily.BLOCK.getHandle(), blockHash, headerData);

            // 3. 存储区块体（到新增BLOCK_BODY列族）
            byte[] bodyData = SerializeUtils.serialize(body);
            db.put(ColumnFamily.BLOCK_BODY.getHandle(), blockHash, bodyData);

            // 4. 存储哈希-高度映射（到新增BLOCK_HASH_HEIGHT列族）
            byte[] heightBytes = ByteUtils.toBytes(block.getHeight());
            db.put(ColumnFamily.BLOCK_HASH_HEIGHT.getHandle(), blockHash, heightBytes);

            // 5. 存储哈希-chainWork映射（到新增BLOCK_HASH_CHAIN_WORK列族）
            byte[] chainWork = block.getChainWork();
            db.put(ColumnFamily.BLOCK_HASH_CHAIN_WORK.getHandle(), blockHash, chainWork);


            //添加交易到区块的索引
            for (int i = 0; i < block.getTransactions().size(); i++) {
                db.put(ColumnFamily.TRANSACTION_INDEX.getHandle(), block.getTransactions().get(i).getTxId(), blockHash);
            }

        } catch (RocksDBException e) {
            log.error("保存区块失败: blockHash={}", block.getHash(), e);
            throw new RuntimeException("保存区块失败", e);
        }
    }


    //批量保存区块
    public void addBlockBatch(List<Block> blocks) {
        rwLock.writeLock().lock();
        WriteBatch writeBatch = null;
        try {
            writeBatch = new WriteBatch();
            WriteOptions writeOptions = new WriteOptions();
            for (Block block : blocks) {
                byte[] blockHash = block.getHash();
                if (blockHash == null) {
                    continue; // 跳过无效区块
                }

                // 拆分区块
                BlockHeader header = block.extractHeader();
                BlockBody body = block.extractBody();

                // 批量写入区块头、区块体、哈希-高度映射
                writeBatch.put(ColumnFamily.BLOCK.getHandle(), blockHash, SerializeUtils.serialize(header));
                writeBatch.put(ColumnFamily.BLOCK_BODY.getHandle(), blockHash, SerializeUtils.serialize(body));
                writeBatch.put(ColumnFamily.BLOCK_HASH_HEIGHT.getHandle(), blockHash, ByteUtils.toBytes(block.getHeight()));
                writeBatch.put(ColumnFamily.BLOCK_HASH_CHAIN_WORK.getHandle(), blockHash, block.getChainWork());

                //交易id到区块hash的索引
                for (int i = 0; i < block.getTransactions().size(); i++) {
                    writeBatch.put(ColumnFamily.TRANSACTION_INDEX.getHandle(), block.getTransactions().get(i).getTxId(), blockHash);
                }
            }
            db.write(writeOptions, writeBatch);
        }catch (RocksDBException e) {
            log.error("批量保存区块信息失败", e);
            throw new RuntimeException("批量保存区块信息失败", e);
        } finally {
            // 确保资源释放
            if (writeBatch != null) {
                writeBatch.close();
            }
            rwLock.writeLock().unlock();
        }
    }
    //删除区块
    public void deleteBlock(byte[] hash) {
        try {
            //先获取这个区块
            Block block = getBlockByHash(hash);
            if (block == null) {
                return; // 区块不存在，直接返回
            }
            // 2. 删除交易索引
            for (Transaction tx : block.getTransactions()) {
                db.delete(ColumnFamily.TRANSACTION_INDEX.getHandle(), tx.getTxId());
            }
            // 3. 删除区块头、区块体、哈希-高度映射
            db.delete(ColumnFamily.BLOCK.getHandle(), hash);
            db.delete(ColumnFamily.BLOCK_BODY.getHandle(), hash);
            db.delete(ColumnFamily.BLOCK_HASH_HEIGHT.getHandle(), hash);
            db.delete(ColumnFamily.BLOCK_HASH_CHAIN_WORK.getHandle(), hash);

        } catch (RocksDBException e) {
            log.error("删除区块失败: blockHash={}", hash, e);
            throw new RuntimeException("删除区块失败", e);
        }
    }
    //批量删除区块
    /**
     * 批量删除区块（同步删除区块头、区块体、哈希-高度映射和交易索引）
     */
    public void deleteBlockBatch(List<byte[]> hashes) {
        rwLock.writeLock().lock();
        WriteBatch writeBatch = null;
        WriteOptions writeOptions = null;
        try {
            writeBatch = new WriteBatch();
            writeOptions = new WriteOptions();
            for (byte[] hash : hashes) {
                if (hash == null) {
                    continue; // 跳过空哈希
                }
                // 1. 获取完整区块（用于删除交易索引）
                Block block = getBlockByHash(hash);
                if (block == null) {
                    continue; // 区块不存在，跳过
                }
                // 2. 批量删除该区块的所有交易索引
                for (Transaction tx : block.getTransactions()) {
                    writeBatch.delete(ColumnFamily.TRANSACTION_INDEX.getHandle(), tx.getTxId());
                }
                // 3. 批量删除区块头、区块体、哈希-高度映射
                writeBatch.delete(ColumnFamily.BLOCK.getHandle(), hash);
                writeBatch.delete(ColumnFamily.BLOCK_BODY.getHandle(), hash);
                writeBatch.delete(ColumnFamily.BLOCK_HASH_HEIGHT.getHandle(), hash);
                writeBatch.delete(ColumnFamily.BLOCK_HASH_CHAIN_WORK.getHandle(), hash);
            }
            // 执行批量删除（原子操作）
            db.write(writeOptions, writeBatch);
        } catch (RocksDBException e) {
            log.error("批量删除区块失败", e);
            throw new RuntimeException("批量删除区块失败", e);
        } finally {
            // 释放资源
            if (writeBatch != null) {
                writeBatch.close();
            }
            if (writeOptions != null) {
                writeOptions.close();
            }
            rwLock.writeLock().unlock();
        }
    }

    //根据hash获取区块
    public Block getBlockByHash(byte[] hash) {
        if (hash == null){
            return null;
        }
        try {
            // 1. 获取区块头
            byte[] headerData = db.get(ColumnFamily.BLOCK.getHandle(), hash);
            if (headerData == null) {
                return null; // 区块头不存在，返回空
            }
            BlockHeader header = (BlockHeader) SerializeUtils.deSerialize(headerData);
            // 2. 获取区块体
            byte[] bodyData = db.get(ColumnFamily.BLOCK_BODY.getHandle(), hash);
            if (bodyData == null) {
                return null; // 区块体不存在，返回空（数据不完整）
            }
            BlockBody body = (BlockBody) SerializeUtils.deSerialize(bodyData);
            // 3. 合并为完整区块
            //获取区块所在高度
            long blockHeightByHash = getBlockHeightByHash(hash);
            getBlockHeightByHash(hash);
            byte[] chainWork = getBlockChainWorkByHash(hash);

            long medianTime = calculateMedianTime(header, blockHeightByHash,hash);
            return Block.merge(header, body,hash,blockHeightByHash,medianTime,chainWork);
        } catch (RocksDBException e) {
            log.error("获取区块失败: blockHash={}", hash, e);
            throw new RuntimeException("获取区块失败", e);
        }
    }

    public BlockHeader getBlockHeaderByHash(byte[] hash) {
        if (hash == null){
            return null;
        }
        try {
            // 1. 获取区块头
            byte[] headerData = db.get(ColumnFamily.BLOCK.getHandle(), hash);
            if (headerData == null) {
                return null; // 区块头不存在，返回空
            }
            return (BlockHeader)SerializeUtils.deSerialize(headerData);
        } catch (RocksDBException e) {
            log.error("获取区块失败: blockHash={}", hash, e);
            throw new RuntimeException("获取区块失败", e);
        }
    }



    /**
     * 基于区块头计算当前区块及其之前最多10个主链区块的时间戳中位数（共11个区块）
     * 若区块数量不足11个（如创世区块附近），则基于现有数据计算
     * @param currentHeader 当前目标区块的区块头（非空）
     * @return 时间戳中位数（毫秒级Unix时间戳）
     * @throws IllegalArgumentException 若输入区块头为空
     * @throws RuntimeException 若无法获取有效区块时间戳
     */
    public long calculateMedianTime(BlockHeader currentHeader, long blockHeight, byte[] blockHash) {
        // 创世区块（高度0）无祖先，中位时间等于自身时间
        if (blockHeight == 0) {
            return currentHeader.getTime();
        }

        // 校验输入
        if (currentHeader == null) {
            throw new IllegalArgumentException("区块头不能为空");
        }

        // 1. 确定实际窗口大小：最多11个，不足则取现有全部祖先
        int actualWindowSize = (int) Math.min(TIME_WINDOW_SIZE, blockHeight);
        List<Long> timestamps = new ArrayList<>(actualWindowSize);
        log.debug("开始计算中位数时间，区块哈希={}，高度={}，目标窗口大小：{}",
                CryptoUtil.bytesToHex(blockHash), blockHeight, actualWindowSize);

        // 2. 收集前N个主链祖先的时间戳（从父区块开始，与validate逻辑一致）
        long currentHeight = blockHeight - 1; // 从父区块高度开始
        BlockHeader currentAncestorHeader = getBlockHeaderByHeight(currentHeight); // 主链父区块

        while (timestamps.size() < actualWindowSize && currentAncestorHeader != null) {
            long blockTime = currentAncestorHeader.getTime();
            if (blockTime > 0) { // 过滤无效时间戳
                timestamps.add(blockTime);
                log.trace("已收集高度={}的时间戳：{}，累计数量：{}",
                        currentHeight, blockTime, timestamps.size());
            }

            // 追溯上一个主链祖先
            currentHeight--;
            if (currentHeight < 0) {
                log.debug("已遍历至创世区块之前，停止收集");
                break;
            }
            currentAncestorHeader = getBlockHeaderByHeight(currentHeight);
        }

        // 3. 处理收集结果（不足时基于现有数据计算）
        if (timestamps.isEmpty()) {
            log.error("未收集到任何有效时间戳，无法计算中位数");
            throw new RuntimeException("无有效时间戳数据");
        }
        log.debug("实际收集到{}个有效时间戳（目标：{}）", timestamps.size(), actualWindowSize);

        // 4. 排序并计算中位数（与validate逻辑一致）
        Collections.sort(timestamps);
        int medianIndex = timestamps.size() / 2;
        long medianTime = timestamps.get(medianIndex);
        log.debug("中位数时间计算完成，参与计算的时间戳：{}，中位数：{}", timestamps, medianTime);
        return medianTime;
    }


    public BlockHeader getBlockHeaderByHeight(long height) {
        // 1. 校验高度合法性（高度不能为负数）
        if (height < 0) {
            log.warn("获取区块头失败：高度不能为负数，height={}", height);
            return null;
        }
        // 2. 通过高度获取主链区块哈希（依赖已实现的索引方法）
        byte[] blockHash = getMainBlockHashByHeight(height);
        if (blockHash == null) {
            log.debug("主链中不存在高度为{}的区块，无法获取区块头", height);
            return null;
        }
        // 3. 通过哈希获取区块头（复用已实现的哈希查询方法）
        return getBlockHeaderByHash(blockHash);
    }


    private boolean isGenesisPrevHash(byte[] prevHash) {
        return Arrays.equals(prevHash, GENESIS_PREV_BLOCK_HASH);
    }


    //根据交易id获取区块hash
    public byte[] getBlockHashByTxId(byte[] txId) {
        try {
            return db.get(ColumnFamily.TRANSACTION_INDEX.getHandle(), txId);
        } catch (RocksDBException e) {
            log.error("获取区块hash失败: txId={}", txId, e);
            throw new RuntimeException("获取区块hash失败", e);
        }
    }

    //根据交易id获取区块
    public Block getBlockByTxId(byte[] txId) {
        byte[] blockHash = getBlockHashByTxId(txId);
        if (blockHash == null){
            return null;
        }
        return getBlockByHash(blockHash);
    }
    /**
     * 通过区块哈希快速获取高度（无需加载完整区块）
     */
    public long getBlockHeightByHash(byte[] blockHash) {
        try {
            byte[] heightBytes = db.get(ColumnFamily.BLOCK_HASH_HEIGHT.getHandle(), blockHash);
            if (heightBytes == null) {
                return -1; // 哈希不存在
            }
            return ByteUtils.bytesToLong(heightBytes);
        } catch (RocksDBException e) {
            log.error("通过哈希获取高度失败: blockHash={}", blockHash, e);
            throw new RuntimeException("通过哈希获取高度失败", e);
        }
    }
    /**
     * 通过区块hash获取 这个区块和这个区块之前的工作总量
     */
    public byte[] getBlockChainWorkByHash(byte[] blockHash) {
        try {
            byte[] chainWork = db.get(ColumnFamily.BLOCK_HASH_CHAIN_WORK.getHandle(), blockHash);
            if (chainWork == null) {
                return null; // 哈希不存在
            }
            return chainWork;
        } catch (RocksDBException e) {
            log.error("通过哈希获取高度失败: blockHash={}", blockHash, e);
            throw new RuntimeException("通过哈希获取高度失败", e);
        }
    }


    /**
     * 根据高度范围迭代查询主链区块
     * @param startHeight 起始高度（包含）
     * @param pageSize 每页数量（1-500）
     * @return 分页结果，包含区块列表、最后查询的高度、是否为最后一页
     */
    public AnyResult<Block,Long> queryBlocksByHeight(long startHeight, int pageSize) {
        // 参数校验
        if (startHeight < 0) {
            throw new IllegalArgumentException("起始高度不能为负数: " + startHeight);
        }
        if (pageSize <= 0 || pageSize > 500) {
            throw new IllegalArgumentException("每页数量必须在1-500之间: " + pageSize);
        }
        rwLock.readLock().lock();
        try {
            List<Block> blockList = new ArrayList<>(pageSize);
            long latestHeight = getMainLatestHeight(); // 获取主链最新高度
            long currentHeight = startHeight;
            int collected = 0;
            // 循环收集区块，直到达到页大小或超过最新高度
            while (collected < pageSize && currentHeight <= latestHeight) {
                // 获取当前高度对应的区块哈希
                byte[] blockHash = getMainBlockHashByHeight(currentHeight);
                if (blockHash != null) {
                    // 通过哈希获取完整区块
                    Block block = getBlockByHash(blockHash);
                    if (block != null) {
                        blockList.add(block);
                        collected++;
                    } else {
                        log.warn("区块哈希存在但区块数据缺失，高度: {}", currentHeight);
                    }
                } else {
                    log.debug("主链中不存在该高度的区块，高度: {}", currentHeight);
                }
                currentHeight++;
            }
            // 判断是否还有更多区块
            boolean hasMore = currentHeight <= latestHeight;
            // 计算最后查询的高度（若未查询到数据则为起始高度）
            long lastQueryHeight = collected > 0 ? (currentHeight - 1) : startHeight;
            return new AnyResult(blockList,lastQueryHeight,hasMore);
        } catch (Exception e) {
            log.error("根据高度查询区块失败，起始高度: {}, 页大小: {}", startHeight, pageSize, e);
            throw new RuntimeException("区块查询失败", e);
        } finally {
            rwLock.readLock().unlock();
        }
    }


    /**
     * 根据高度范围查询主链区块
     * @param start 起始高度（包含）
     * @param end 结束高度（包含）
     * @return 范围内的所有区块列表（按高度升序排列）
     */
    public List<Block> getBlockByRange(long start, long end) {
        // 参数校验
        if (start < 0 || end < 0) {
            throw new IllegalArgumentException("高度不能为负数: start=" + start + ", end=" + end);
        }
        if (start > end) {
            throw new IllegalArgumentException("起始高度不能大于结束高度: start=" + start + ", end=" + end);
        }

        rwLock.readLock().lock();
        try {
            List<Block> blockList = new ArrayList<>();
            long latestHeight = getMainLatestHeight(); // 获取主链最新高度

            // 修正结束高度，不能超过最新高度
            long actualEnd = Math.min(end, latestHeight);

            // 如果起始范围无效（起始起始已超过最新高度），返回空列表
            if (start > actualEnd) {
                log.warn("查询范围超出主链最新高度，当前最新高度: {}", latestHeight);
                return blockList;
            }

            // 遍历范围内的每个高度，获取对应的区块
            for (long height = start; height <= actualEnd; height++) {
                byte[] blockHash = getMainBlockHashByHeight(height);
                if (blockHash != null) {
                    Block block = getBlockByHash(blockHash);
                    if (block != null) {
                        blockList.add(block);
                    } else {
                        log.warn("区块哈希存在但区块数据缺失，高度: {}", height);
                    }
                } else {
                    log.debug("主链中不存在该高度的区块，高度: {}", height);
                }
            }

            return blockList;
        } catch (Exception e) {
            log.error("根据高度范围查询区块失败，start: {}, end: {}", start, end, e);
            throw new RuntimeException("区块范围查询失败", e);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * 根据起始区块哈希和结束区块哈希，查询两者之间的主链区块（包含两端），并限制返回数量
     * 注：仅支持主链上的连续区块查询，若区块不在主链或不连续则返回空列表
     * @param startHash 起始区块哈希
     * @param endHash 结束区块哈希
     * @param batchSize 最大返回数量（1-500）
     * @return 两个区块之间的主链区块（按区块链顺序排列），数量不超过batchSize，若不符合条件则返回空列表
     */
    public List<Block> getBlockByStartHashAndEndHashWithLimit(byte[] startHash, byte[] endHash, int batchSize) {
        // 1. 校验输入参数
        if (startHash == null || endHash == null) {
            log.warn("起始或结束区块哈希不能为空");
            return Collections.emptyList();
        }
        if (batchSize <= 0 || batchSize > 500) {
            throw new IllegalArgumentException("批量大小必须在1-500之间: " + batchSize);
        }

        // 2. 获取起始和结束区块
        Block startBlock = getBlockByHash(startHash);
        Block endBlock = getBlockByHash(endHash);

        // 3. 检查区块是否存在
        if (startBlock == null) {
            log.warn("起始区块不存在，哈希: {}", CryptoUtil.bytesToHex(startHash));
            return Collections.emptyList();
        }
        if (endBlock == null) {
            log.warn("结束区块不存在，哈希: {}", CryptoUtil.bytesToHex(endHash));
            return Collections.emptyList();
        }

        // 4. 检查区块是否在主链上
        long startHeight = startBlock.getHeight();
        byte[] mainChainStartHash = getMainBlockHashByHeight(startHeight);
        if (!Arrays.equals(mainChainStartHash, startBlock.getHash())) {
            log.warn("起始区块不在主链上，哈希: {}", CryptoUtil.bytesToHex(startHash));
            return Collections.emptyList();
        }

        long endHeight = endBlock.getHeight();
        byte[] mainChainEndHash = getMainBlockHashByHeight(endHeight);
        if (!Arrays.equals(mainChainEndHash, endBlock.getHash())) {
            log.warn("结束区块不在主链上，哈希: {}", CryptoUtil.bytesToHex(endHash));
            return Collections.emptyList();
        }

        // 5. 验证区块是否在同一条连续链上
        if (!isBlocksInSameChain(startBlock, endBlock)) {
            log.warn("起始区块与结束区块不在同一条连续链上，无法查询范围");
            return Collections.emptyList();
        }

        // 6. 确定遍历方向和范围
        List<Block> result = new ArrayList<>(batchSize);
        long currentHeight;
        long targetHeight;
        int step;

        if (startHeight <= endHeight) {
            // 正序遍历（从低到高）
            currentHeight = startHeight;
            targetHeight = endHeight;
            step = 1;
        } else {
            // 倒序遍历（从高到低）
            currentHeight = startHeight;
            targetHeight = endHeight;
            step = -1;
        }

        // 7. 按批次大小收集区块
        int collected = 0;
        while (collected < batchSize &&
                ((step > 0 && currentHeight <= targetHeight) ||
                        (step < 0 && currentHeight >= targetHeight))) {

            // 获取当前高度的区块哈希
            byte[] blockHash = getMainBlockHashByHeight(currentHeight);
            if (blockHash != null) {
                Block block = getBlockByHash(blockHash);
                if (block != null) {
                    result.add(block);
                    collected++;
                } else {
                    log.warn("区块哈希存在但数据缺失，高度: {}", currentHeight);
                }
            } else {
                log.debug("主链中不存在该高度的区块，高度: {}", currentHeight);
            }

            currentHeight += step;
        }

        return result;
    }


    /**
     * 验证两个区块是否在同一条连续链上（通过前驱哈希追溯）
     * @param start 起始区块
     * @param end 结束区块
     * @return 若在同一条连续链上则返回true，否则返回false
     */
    private boolean isBlocksInSameChain(Block start, Block end) {
        long startHeight = start.getHeight();
        long endHeight = end.getHeight();

        // 情况1：起始区块高度 <= 结束区块高度 → 验证end是否能追溯到start
        if (startHeight <= endHeight) {
            Block current = end;
            while (current.getHeight() > startHeight) {
                current = getBlockByHash(current.getPreviousHash());
                if (current == null) { // 前驱区块不存在，链断裂
                    return false;
                }
            }
            // 最终应追溯到起始区块
            return Arrays.equals(current.getHash(), start.getHash());
        }

        // 情况2：起始区块高度 > 结束区块高度 → 验证start是否能追溯到end
        else {
            Block current = start;
            while (current.getHeight() > endHeight) {
                current = getBlockByHash(current.getPreviousHash());
                if (current == null) { // 前驱区块不存在，链断裂
                    return false;
                }
            }
            // 最终应追溯到结束区块
            return Arrays.equals(current.getHash(), end.getHash());
        }
    }




    // 常量定义
    private static final String UTXO_INDEX_SEPARATOR = "_UTXO_"; // 分隔符

    //UTXO操作........................................................................................................
    public void putUTXO(UTXO utxo) {
        byte[] serialize = SerializeUtils.serialize(utxo);
        try {
            //保存原始UTXO
            String utxoKey = getUTXOKey(utxo.getTxId(), utxo.getVout());
            byte[] key = utxoKey.getBytes();
            db.put(ColumnFamily.UTXO.getHandle(),key,serialize );

            //建立脚本Hash_utxoKey  到 金额的索引
            ScriptPubKey scriptPubKey = utxo.getScriptPubKey();
            byte[] scriptKey = CryptoUtil.applyRIPEMD160(CryptoUtil.applySHA256(scriptPubKey.serialize()));

            // key:<20字节脚本哈希_UTXO_utxoKey> value:<8字节金额>  新的UTXO族列   ColumnFamily.CF_SCRIPT_UTXO.getHandle()

            // 3. 写入脚本哈希-UTXO索引
            byte[] scriptHash = calculateScriptHash(utxo.getScriptPubKey());
            String indexKey = generateScriptHashUtxoKey(scriptHash, utxoKey);
            db.put(ColumnFamily.SCRIPT_UTXO.getHandle(),
                    indexKey.getBytes(),
                    amountToBytes(utxo.getValue()));


            //更新UTXO总数
            byte[] bytes = db.get(ColumnFamily.BLOCK_CHAIN.getHandle(), KEY_UTXO_COUNT);
            long count = bytes == null ? 0 : ByteUtils.bytesToLong(bytes);
            db.put(ColumnFamily.BLOCK_CHAIN.getHandle(), KEY_UTXO_COUNT, ByteUtils.toBytes(count + 1));


        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        }
    }
    public void putUTXOBatch(List<UTXO> batch) {
        rwLock.writeLock().lock();
        WriteBatch writeBatch = null;
        try {
            writeBatch = new WriteBatch();
            WriteOptions writeOptions = new WriteOptions();
            for (UTXO utxo : batch) {
                String utxoKey = getUTXOKey(utxo.getTxId(), utxo.getVout());
                // 1. 序列化UTXO数据并添加到批量写
                byte[] utxoData = SerializeUtils.serialize(utxo);
                writeBatch.put(ColumnFamily.UTXO.getHandle(), utxoKey.getBytes(), utxoData);


                // 2. 写入索引
                byte[] scriptHash = calculateScriptHash(utxo.getScriptPubKey());
                String indexKey = generateScriptHashUtxoKey(scriptHash, utxoKey);
                writeBatch.put(ColumnFamily.SCRIPT_UTXO.getHandle(),
                        indexKey.getBytes(),
                        amountToBytes(utxo.getValue()));


            }
            // 执行批量写入
            db.write(writeOptions, writeBatch);
            //UTXO总数
            byte[] bytes = db.get(ColumnFamily.BLOCK_CHAIN.getHandle(), KEY_UTXO_COUNT);
            long count = bytes == null ? 0 : ByteUtils.bytesToLong(bytes);
            db.put(ColumnFamily.BLOCK_CHAIN.getHandle(), KEY_UTXO_COUNT, ByteUtils.toBytes(count + batch.size()));


        }catch (RocksDBException e) {
            log.error("批量保存UTXO信息失败", e);
            throw new RuntimeException("批量保存UTXO信息失败", e);
        } finally {
            // 确保资源释放
            if (writeBatch != null) {
                writeBatch.close();
            }
            rwLock.writeLock().unlock();
        }
    }
    //删除UTXO
    public void deleteUTXO(byte[] txId, int vout) {
        rwLock.writeLock().lock();
        try {
            String utxoKey = getUTXOKey(txId, vout);
            // 1. 查询UTXO获取脚本哈希（用于删除索引）
            UTXO utxo = getUTXO(utxoKey);
            if (utxo != null) {
                byte[] scriptHash = calculateScriptHash(utxo.getScriptPubKey());
                String indexKey = generateScriptHashUtxoKey(scriptHash, utxoKey);
                db.delete(ColumnFamily.SCRIPT_UTXO.getHandle(), indexKey.getBytes());
            }

            // 2. 删除原始UTXO
            db.delete(ColumnFamily.UTXO.getHandle(), utxoKey.getBytes());


            // 3. 更新UTXO总数
            byte[] countBytes = db.get(ColumnFamily.BLOCK_CHAIN.getHandle(), KEY_UTXO_COUNT);
            long count = countBytes == null ? 0 : ByteUtils.bytesToLong(countBytes);
            if (count > 0) {
                db.put(ColumnFamily.BLOCK_CHAIN.getHandle(), KEY_UTXO_COUNT, ByteUtils.toBytes(count - 1));
            }

        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        }finally {
            rwLock.writeLock().unlock();
        }
    }
    public void deleteUTXO(UTXO utxo) {
        rwLock.writeLock().lock();
        try {
            String utxoKey = getUTXOKey(utxo.getTxId(), utxo.getVout());
            // 1. 查询UTXO获取脚本哈希（用于删除索引）
            byte[] scriptHash = calculateScriptHash(utxo.getScriptPubKey());
            String indexKey = generateScriptHashUtxoKey(scriptHash, utxoKey);
            db.delete(ColumnFamily.SCRIPT_UTXO.getHandle(), indexKey.getBytes());

            // 2. 删除原始UTXO
            db.delete(ColumnFamily.UTXO.getHandle(), utxoKey.getBytes());

            // 3. 更新UTXO总数
            byte[] countBytes = db.get(ColumnFamily.BLOCK_CHAIN.getHandle(), KEY_UTXO_COUNT);
            long count = countBytes == null ? 0 : ByteUtils.bytesToLong(countBytes);
            if (count > 0) {
                db.put(ColumnFamily.BLOCK_CHAIN.getHandle(), KEY_UTXO_COUNT, ByteUtils.toBytes(count - 1));
            }

        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        }finally {
            rwLock.writeLock().unlock();
        }
    }



    //批量删除UTXO
    public void deleteUTXOBatch(List<UTXO> batch) {
        rwLock.writeLock().lock();
        WriteBatch writeBatch = null;
        try {
            writeBatch = new WriteBatch();
            WriteOptions writeOptions = new WriteOptions();
            for (UTXO utxo: batch) {
                String utxoKey = getUTXOKey(utxo.getTxId(), utxo.getVout());
                // 1. 删除索引
                byte[] scriptHash = calculateScriptHash(utxo.getScriptPubKey());
                String indexKey = generateScriptHashUtxoKey(scriptHash, utxoKey);
                writeBatch.delete(ColumnFamily.SCRIPT_UTXO.getHandle(), indexKey.getBytes());

                writeBatch.delete(ColumnFamily.UTXO.getHandle(), utxoKey.getBytes());
            }
            db.write(writeOptions, writeBatch);


            // 更新UTXO总数
            byte[] countBytes = db.get(ColumnFamily.BLOCK_CHAIN.getHandle(), KEY_UTXO_COUNT);
            long count = countBytes == null ? 0 : ByteUtils.bytesToLong(countBytes);
            long newCount = Math.max(0, count - batch.size());
            db.put(ColumnFamily.BLOCK_CHAIN.getHandle(), KEY_UTXO_COUNT, ByteUtils.toBytes(newCount));


        }catch (RocksDBException e) {
            log.error("批量删除UTXO信息失败", e);
            throw new RuntimeException("批量删除UTXO信息失败", e);
        } finally {
            // 确保资源释放
            if (writeBatch != null) {
                writeBatch.close();
            }
        }
    }



    //获取UTXO
    public UTXO getUTXO(byte[] txId, int vout) {
        try {
            String utxoKey = getUTXOKey(txId, vout);
            byte[] valueBytes = db.get(ColumnFamily.UTXO.getHandle(), utxoKey.getBytes());
            if (valueBytes == null) {
                return null; // 不存在返回null，避免抛出异常
            }
            return (UTXO)SerializeUtils.deSerialize(valueBytes);
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        }
    }
    public UTXO getUTXO(String utxoKey) {
        try {
            byte[] valueBytes = db.get(ColumnFamily.UTXO.getHandle(), utxoKey.getBytes());
            if (valueBytes == null) {
                return null; // 不存在返回null，避免抛出异常
            }
            return (UTXO)SerializeUtils.deSerialize(valueBytes);
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        }
    }


    //分页获取UTXO 每次5000个
    /**
     * 分页查询 UTXO 集合
     * @param pageSize 每页大小
     * @param lastKey 上一页的最后一个键（第一页传 null）
     * @return 分页结果（包含当前页 UTXO 列表和当前页最后一个键）
     * lastKey 为第一次 会包含在查询结果里面
     */
    public ListPageResult<UTXO> queryUTXOPage(int pageSize, String lastKey) {
        // 校验 pageSize 范围
        if (pageSize <= 0 || pageSize > 5000) {
            throw new IllegalArgumentException("每页数量必须在 1-5000 之间");
        }
        rwLock.readLock().lock();
        RocksIterator iterator = null;
        try {
            // 获取 UTXO 列族的迭代器
            iterator = db.newIterator(ColumnFamily.UTXO.getHandle());
            List<UTXO> utxoList = new ArrayList<>(pageSize);
            String currentLastKey = null;
            // 定位迭代器起始位置：如果有 lastKey，从该键的下一个位置开始；否则从开头开始
            if (lastKey != null && !lastKey.isEmpty()) {
                byte[] lastKeyBytes = lastKey.getBytes();
                iterator.seek(lastKeyBytes); // 定位到 lastKey 位置
                if (iterator.isValid() && Arrays.equals(iterator.key(), lastKeyBytes)) {
                    iterator.next(); // 跳过 lastKey，从下一个键开始
                }
            } else {
                iterator.seekToFirst(); // 第一页，从第一个键开始
            }
            // 遍历获取 pageSize 个 UTXO
            int count = 0;
            while (iterator.isValid() && count < pageSize) {
                byte[] keyBytes = iterator.key();
                byte[] valueBytes = iterator.value();
                // 反序列化 UTXO
                UTXO utxo = (UTXO) SerializeUtils.deSerialize(valueBytes);
                utxoList.add(utxo);
                // 记录当前键（作为下一页的 lastKey）
                currentLastKey = new String(keyBytes);
                iterator.next();
                count++;
            }
            return new ListPageResult<>(utxoList, currentLastKey, count < pageSize); // 最后一页的标志：实际数量 < pageSize
        } catch (Exception e) {
            log.error("UTXO 分页查询失败", e);
            throw new RuntimeException("UTXO 分页查询失败", e);
        } finally {
            if (iterator != null) {
                iterator.close(); // 关闭迭代器，释放资源
            }
            rwLock.readLock().unlock();
        }
    }




    /**
     * 分页查询指定脚本哈希的UTXO金额
     * @param scriptHash 20字节脚本哈希
     * @param pageSize 每页数量(1-5000)
     * @param lastUtxoKey 上一页最后一个UTXO键(首次查询传null)
     * @return 分页结果(包含金额列表、最后一个UTXO键、是否为最后一页)
     */
    public ListPageResult<Long> queryUtxoAmountsByScriptHash(byte[] scriptHash, int pageSize, String lastUtxoKey) {
        if (scriptHash == null || scriptHash.length != 20) {
            throw new IllegalArgumentException("脚本哈希必须为20字节");
        }
        if (pageSize <= 0 || pageSize > 5000) {
            throw new IllegalArgumentException("每页数量必须在1-5000之间");
        }

        rwLock.readLock().lock();
        RocksIterator iterator = null;
        ReadOptions readOptions = null;
        try {
            // 1. 构建前缀
            String scriptHashHex = CryptoUtil.bytesToHex(scriptHash);
            String prefix = scriptHashHex + UTXO_INDEX_SEPARATOR;
            byte[] prefixBytes = prefix.getBytes();

            // 2. 配置迭代器
            readOptions = new ReadOptions().setPrefixSameAsStart(true);
            iterator = db.newIterator(ColumnFamily.SCRIPT_UTXO.getHandle(), readOptions);

            // 3. 定位起始位置
            if (lastUtxoKey != null && !lastUtxoKey.isEmpty()) {
                String startKey = prefix + lastUtxoKey;
                iterator.seek(startKey.getBytes());
                // 跳过上一页最后一个键
                if (iterator.isValid() && new String(iterator.key()).equals(startKey)) {
                    iterator.next();
                }
            } else {
                iterator.seek(prefixBytes);
            }

            // 4. 扫描分页数据
            List<Long> amounts = new ArrayList<>(pageSize);
            String currentLastUtxoKey = null;
            int count = 0;

            while (iterator.isValid() && count < pageSize) {
                byte[] keyBytes = iterator.key();
                String key = new String(keyBytes);

                // 检查是否仍为当前脚本哈希的前缀
                if (!key.startsWith(prefix)) {
                    break;
                }

                // 提取金额和UTXO键
                long amount = bytesToAmount(iterator.value());
                amounts.add(amount);

                // 提取utxoKey(格式: 脚本哈希_UTXO_utxoKey → 截取后半部分)
                currentLastUtxoKey = key.substring(prefix.length());

                iterator.next();
                count++;
            }

            // 判断是否为最后一页
            boolean isLastPage = count < pageSize;
            return new ListPageResult<>(amounts, currentLastUtxoKey, isLastPage);

        } catch (Exception e) {
            log.error("分页查询脚本哈希UTXO金额失败", e);
            throw new RuntimeException("分页查询失败", e);
        } finally {
            if (iterator != null) iterator.close();
            if (readOptions != null) readOptions.close();
            rwLock.readLock().unlock();
        }
    }


    public TPageResult<UTXOSearch> selectUtxoAmountsByScriptHash(byte[] scriptHash, int pageSize, String lastUtxoKey) {
        if (scriptHash == null || scriptHash.length != 20) {
            throw new IllegalArgumentException("脚本哈希必须为20字节");
        }
        if (pageSize <= 0 || pageSize > 5000) {
            throw new IllegalArgumentException("每页数量必须在1-5000之间");
        }

        HashSet<String> utxoKeySet = new HashSet<>();


        rwLock.readLock().lock();
        RocksIterator iterator = null;
        ReadOptions readOptions = null;
        try {
            // 1. 构建前缀
            String scriptHashHex = CryptoUtil.bytesToHex(scriptHash);
            String prefix = scriptHashHex + UTXO_INDEX_SEPARATOR;
            byte[] prefixBytes = prefix.getBytes();

            // 2. 配置迭代器
            readOptions = new ReadOptions().setPrefixSameAsStart(true);
            iterator = db.newIterator(ColumnFamily.SCRIPT_UTXO.getHandle(), readOptions);

            // 3. 定位起始位置
            if (lastUtxoKey != null && !lastUtxoKey.isEmpty()) {
                String startKey = prefix + lastUtxoKey;
                iterator.seek(startKey.getBytes());
                // 跳过上一页最后一个键
                if (iterator.isValid() && new String(iterator.key()).equals(startKey)) {
                    iterator.next();
                }
            } else {
                iterator.seek(prefixBytes);
            }

            // 4. 扫描分页数据
            List<Long> amounts = new ArrayList<>(pageSize);
            String currentLastUtxoKey = null;
            int count = 0;

            while (iterator.isValid() && count < pageSize) {
                byte[] keyBytes = iterator.key();
                String key = new String(keyBytes);

                // 检查是否仍为当前脚本哈希的前缀
                if (!key.startsWith(prefix)) {
                    break;
                }

                // 提取金额和UTXO键
                long amount = bytesToAmount(iterator.value());
                amounts.add(amount);

                // 提取utxoKey(格式: 脚本哈希_UTXO_utxoKey → 截取后半部分)
                currentLastUtxoKey = key.substring(prefix.length());
                utxoKeySet.add(currentLastUtxoKey);

                iterator.next();
                count++;
            }
            // 判断是否为最后一页
            //计算 amounts 总额
            long total = amounts.stream().mapToLong(Long::longValue).sum();
            boolean isLastPage = count < pageSize;
            UTXOSearch utxoSearch = new UTXOSearch();
            utxoSearch.setTotal(total);
            utxoSearch.setUtxos(utxoKeySet);
            return new TPageResult<>(utxoSearch, currentLastUtxoKey, isLastPage)  ;
        } catch (Exception e) {
            log.error("分页查询脚本哈希UTXO金额失败", e);
            throw new RuntimeException("分页查询失败", e);
        } finally {
            if (iterator != null) iterator.close();
            if (readOptions != null) readOptions.close();
            rwLock.readLock().unlock();
        }
    }









    /**
     * 计算指定脚本哈希的总余额(聪)
     * @param scriptHash 20字节脚本哈希
     * @return 总余额
     */
    public long calculateBalanceByScriptHash(byte[] scriptHash) {
        if (scriptHash == null || scriptHash.length != 20) {
            return 0;
        }
        rwLock.readLock().lock();
        RocksIterator iterator = null;
        ReadOptions readOptions = null;
        try {
            String prefix = CryptoUtil.bytesToHex(scriptHash) + UTXO_INDEX_SEPARATOR;
            readOptions = new ReadOptions().setPrefixSameAsStart(true);
            iterator = db.newIterator(ColumnFamily.SCRIPT_UTXO.getHandle(), readOptions);
            iterator.seek(prefix.getBytes());

            long total = 0;
            while (iterator.isValid()) {
                String key = new String(iterator.key());
                if (!key.startsWith(prefix)) {
                    break;
                }
                total += bytesToAmount(iterator.value());
                iterator.next();
            }
            return total;

        } catch (Exception e) {
            log.error("计算脚本哈希余额失败", e);
            throw new RuntimeException("计算余额失败", e);
        } finally {
            if (iterator != null) iterator.close();
            if (readOptions != null) readOptions.close();
            rwLock.readLock().unlock();
        }
    }










    public Transaction getTransaction(byte[] txId) {
        //根据交易Id查询区块
        byte[] blockHash = getBlockHashByTxId(txId);
        //查询区块
        Block blockByHash = getBlockByHash(blockHash);
        if (blockByHash != null){
            //获取区块中的交易
            List<Transaction> transactions = blockByHash.getTransactions();
            for (Transaction transaction: transactions) {
                if (Arrays.equals(transaction.getTxId(), txId)) {
                    return transaction;
                }
            }
        }
        return null;
    }


    /**
     * 生成索引键: <20字节脚本哈希(hex)>_UTXO_<utxoKey>
     * @param scriptHash 20字节脚本哈希
     * @param utxoKey 原始UTXO键(txid:vout)
     * @return 索引键字符串
     */
    private String generateScriptHashUtxoKey(byte[] scriptHash, String utxoKey) {
        if (scriptHash.length != 20) {
            throw new IllegalArgumentException("脚本哈希必须为20字节");
        }
        return CryptoUtil.bytesToHex(scriptHash) + UTXO_INDEX_SEPARATOR + utxoKey;
    }

    /**
     * 将金额(聪)转换为8字节数组
     */
    private byte[] amountToBytes(long amount) {
        return ByteUtils.toBytes(amount);
    }

    /**
     * 将8字节数组转换为金额(聪)
     */
    private long bytesToAmount(byte[] bytes) {
        if (bytes == null || bytes.length != 8) {
            return 0;
        }
        return ByteUtils.bytesToLong(bytes);
    }

    /**
     * 计算脚本哈希(20字节): SHA-256 → RIPEMD-160
     */
    private byte[] calculateScriptHash(ScriptPubKey scriptPubKey) {
        byte[] scriptBytes = scriptPubKey.serialize();
        return CryptoUtil.applyRIPEMD160(CryptoUtil.applySHA256(scriptBytes));
    }



    //同步........................................................................................................
    public void saveSyncTaskRecord(SyncTaskRecord syncTaskRecord){
        try {
            byte[] valueBytes = SerializeUtils.serialize(syncTaskRecord);
            db.put(ColumnFamily.SYNC_TASK.getHandle(), syncTaskRecord.getTaskId().getBytes(), valueBytes);
        } catch (RocksDBException e) {
            log.error("保存同步任务失败: key={}", syncTaskRecord.getTaskId(), e);
            throw new RuntimeException("保存同步任务失败", e);
        }
    }
    /**
     * 根据任务ID获取同步任务记录
     * @param taskId 同步任务ID
     * @return 对应的同步任务记录，若不存在则返回null
     */
    public SyncTaskRecord getSyncTaskRecord(String taskId) {
        // 参数校验
        if (taskId == null || taskId.isEmpty()) {
            log.warn("获取同步任务记录失败：任务ID为空");
            return null;
        }
        try {
            // 将任务ID转换为字节数组作为查询键
            byte[] keyBytes = taskId.getBytes();
            // 从SYNC_TASK列族查询对应记录
            byte[] valueBytes = db.get(ColumnFamily.SYNC_TASK.getHandle(), keyBytes);
            if (valueBytes == null) {
                log.debug("未找到同步任务记录：taskId={}", taskId);
                return null;
            }
            // 反序列化任务记录并返回
            return (SyncTaskRecord) SerializeUtils.deSerialize(valueBytes);
        } catch (RocksDBException e) {
            log.error("获取同步任务记录失败：taskId={}", taskId, e);
            throw new RuntimeException("获取同步任务记录失败", e);
        }
    }


    // 恢复运行中的任务
    /**
     * 获取所有处于运行状态的同步任务记录
     * @return 运行中的同步任务列表（无运行中任务时返回空列表）
     */
    public List<SyncTaskRecord> getRunningSyncTasks() {
        rwLock.readLock().lock();
        RocksIterator iterator = null;
        try {
            // 获取同步任务列族的迭代器
            iterator = db.newIterator(ColumnFamily.SYNC_TASK.getHandle());
            List<SyncTaskRecord> runningTasks = new ArrayList<>();

            // 遍历所有同步任务记录
            iterator.seekToFirst();
            while (iterator.isValid()) {
                byte[] valueBytes = iterator.value();
                if (valueBytes != null) {
                    // 反序列化任务记录
                    SyncTaskRecord task = (SyncTaskRecord) SerializeUtils.deSerialize(valueBytes);
                    // 判断任务是否处于运行中状态（假设SyncTaskRecord有isRunning()方法判断状态）
                    if (task != null && (task.getStatus().equals(SyncStatus.RUNNING)|| task.getStatus().equals(SyncStatus.PAUSED))) {
                        runningTasks.add(task);
                    }
                }
                iterator.next();
            }
            return runningTasks;
        } catch (Exception e) {
            log.error("获取运行中的同步任务失败", e);
            throw new RuntimeException("获取运行中的同步任务失败", e);
        } finally {
            // 释放迭代器资源
            if (iterator != null) {
                iterator.close();
            }
            rwLock.readLock().unlock();
        }
    }


    /**
     * 保存下载的区块头
     * @param header
     */
    public void saveDownloadedHeader(BlockHeader header) {

    }


    public BlockHeader getDownloadedHeader(long height) {

        return null;
    }

    public void deleteDownloadedHeader(long height) {

    }






    //.................................................................................................................
    //以标准配置（160 位 ID + K=20）为例：
    //最大节点信息数量 = 20 × 160 = 3200 个。
    //新增路由表节点
    public void addOrUpdateRouteTableNode(ExternalNodeInfo nodeInfo) {
        try {
            byte[] valueBytes = SerializeUtils.serialize(nodeInfo);
            db.put(ColumnFamily.ROUTING_TABLE.getHandle(), nodeInfo.getId().toByteArray(), valueBytes);
        } catch (RocksDBException e) {
            log.error("新增路由表节点失败: key={}", nodeInfo.getId(), e);
            throw new RuntimeException("新增路由表节点失败", e);
        }
    }
    /**
     * 批量新增或更新路由表节点
     * @param nodeInfos 节点信息列表
     */
    public void addOrUpdateRouteTableNodeBatch(List<ExternalNodeInfo> nodeInfos) {
        if (nodeInfos == null || nodeInfos.isEmpty()) {
            log.warn("批量添加路由表节点：空列表，无需处理");
            return;
        }
        rwLock.writeLock().lock();
        WriteBatch writeBatch = null;
        WriteOptions writeOptions = null;
        try {
            writeBatch = new WriteBatch();
            writeOptions = new WriteOptions();
            // 批量写入路由表节点
            for (ExternalNodeInfo nodeInfo : nodeInfos) {
                byte[] key = nodeInfo.getId().toByteArray();
                byte[] valueBytes = SerializeUtils.serialize(nodeInfo);
                writeBatch.put(ColumnFamily.ROUTING_TABLE.getHandle(), key, valueBytes);
            }
            // 执行批量写入（原子操作）
            db.write(writeOptions, writeBatch);
            log.info("批量添加路由表节点成功，数量：{}", nodeInfos.size());
        } catch (RocksDBException e) {
            log.error("批量添加路由表节点失败，数量：{}", nodeInfos.size(), e);
            throw new RuntimeException("批量添加路由表节点失败", e);
        } finally {
            // 确保资源释放
            if (writeBatch != null) {
                writeBatch.close();
            }
            if (writeOptions != null) {
                writeOptions.close();
            }
            rwLock.writeLock().unlock();
        }
    }

    //获取路由表节点
    public ExternalNodeInfo getRouteTableNode(BigInteger nodeId) {
        try {
            byte[] valueBytes = db.get(ColumnFamily.ROUTING_TABLE.getHandle(), nodeId.toByteArray());
            if (valueBytes == null) {
                return null; // 不存在返回null，避免抛出异常
            }
            return (ExternalNodeInfo)SerializeUtils.deSerialize(valueBytes);
        } catch (RocksDBException e) {
            log.error("获取路由表节点失败: key={}", nodeId, e);
            throw new RuntimeException("获取路由表节点失败", e);
        }
    }
    //删除
    public void deleteRouteTableNode(BigInteger nodeId) {
        try {
            db.delete(ColumnFamily.ROUTING_TABLE.getHandle(), nodeId.toByteArray());
        } catch (RocksDBException e) {
            log.error("删除路由表节点失败: key={}", nodeId, e);
            throw new RuntimeException("删除路由表节点失败", e);
        }
    }

    /**
     * 迭代查询所有路由表节点
     * @return 所有路由表节点列表（无节点时返回空列表）
     */
    public List<ExternalNodeInfo> iterateAllRouteTableNodes() {
        rwLock.readLock().lock();
        RocksIterator iterator = null;
        try {
            // 获取路由表列族的迭代器
            iterator = db.newIterator(ColumnFamily.ROUTING_TABLE.getHandle());
            List<ExternalNodeInfo> nodeList = new ArrayList<>();

            // 从第一个键开始遍历
            iterator.seekToFirst();
            while (iterator.isValid()) {
                // 反序列化节点信息
                byte[] valueBytes = iterator.value();
                ExternalNodeInfo nodeInfo = (ExternalNodeInfo) SerializeUtils.deSerialize(valueBytes);
                nodeList.add(nodeInfo);
                // 移动到下一个键
                iterator.next();
            }
            return nodeList;
        } catch (Exception e) {
            log.error("迭代查询所有路由表节点失败", e);
            throw new RuntimeException("迭代查询路由表节点失败", e);
        } finally {
            // 确保迭代器关闭和锁释放
            if (iterator != null) {
                iterator.close();
            }
            rwLock.readLock().unlock();
        }
    }










    //新增或者修改-本节点的设置信息 key - NODE_SETTING_KEY
    public void addOrUpdateNodeSetting(NodeSettings value) {
        try {
            byte[] valueBytes = SerializeUtils.serialize(value);
            db.put(ColumnFamily.NODE_INFO.getHandle(), KEY_NODE_SETTING, valueBytes);
        } catch (RocksDBException e) {
            log.error("节点状态失败: key={}", KEY_NODE_SETTING, e);
            throw new RuntimeException("节点状态失败", e);
        }
    }

    //获取本节点的设置信息
    public NodeSettings getNodeSetting() {
        try {
            byte[] valueBytes = db.get(ColumnFamily.NODE_INFO.getHandle(), KEY_NODE_SETTING);
            if (valueBytes == null) {
                return null; // 不存在返回null，避免抛出异常
            }
            return (NodeSettings)SerializeUtils.deSerialize(valueBytes);
        } catch (RocksDBException e) {
            log.error("获取节点状态失败: key={}", KEY_NODE_SETTING, e);
            throw new RuntimeException("获取节点状态失败", e);
        }
    }

    /**
     * 新增或者修改本节点的矿工信息
     */
    public void addOrUpdateMiner(Miner value) {
        try {
            byte[] valueBytes = SerializeUtils.serialize(value);
            db.put(ColumnFamily.MINER_INFO.getHandle(), KEY_MINER, valueBytes);
        } catch (RocksDBException e) {
            log.error("保存矿工信息失败: key={}", KEY_MINER, e);
            throw new RuntimeException("保存矿工信息失败", e);
        }
    }
    /**
     * 获取本节点的矿工信息
     */
    public Miner getMiner() {
        try {
            byte[] valueBytes = db.get(ColumnFamily.MINER_INFO.getHandle(), KEY_MINER);
            if (valueBytes == null) {
                return null; // 不存在返回null，避免抛出异常
            }
            return (Miner)SerializeUtils.deSerialize(valueBytes);
        } catch (RocksDBException e) {
            log.error("获取矿工信息失败: key={}", KEY_MINER, e);
            throw new RuntimeException("获取矿工信息失败", e);
        }
    }



    //..................................................................................................................
    private final ReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final RocksDB db;



    private static class InstanceHolder {
        private static final StorageService INSTANCE = new StorageService();
    }
    public static StorageService getInstance() {
        return StorageService.InstanceHolder.INSTANCE;
    }

    private StorageService() {
        try {
            this.db = openRocksDBWithColumnFamilies();
            registerShutdownHook();
        } catch (RocksDBException e) {
            log.error("初始化数据库失败", e);
            throw new RuntimeException("数据库初始化失败", e);
        }
    }

    private RocksDB openRocksDBWithColumnFamilies() throws RocksDBException {
        File dbDir = new File(DB_PATH);
        if (!dbDir.exists()) {
            boolean mkdirs = dbDir.mkdirs();
            if (!mkdirs) {
                throw new RuntimeException("创建数据库目录失败: " + DB_PATH);
            }
        }

        // 1. 读取现有列族
        List<byte[]> bytes = RocksDB.listColumnFamilies(new Options(), DB_PATH);
        List<String> existingCfNames = new ArrayList<>();
        for (byte[] bytes1 : bytes) {
            existingCfNames.add(new String(bytes1));
        }
        List<ColumnFamilyDescriptor> cfDescriptors = new ArrayList<>();
        List<ColumnFamilyHandle> cfHandles = new ArrayList<>();

        // 2. 配置默认列族（必须包含）
        cfDescriptors.add(new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, new ColumnFamilyOptions()));

        // 3. 配置自定义列族（不存在则创建）
        for (ColumnFamily cf : ColumnFamily.values()) {
            String cfName = cf.actualName;
            ColumnFamilyOptions options = cf.options;
            cfDescriptors.add(new ColumnFamilyDescriptor(cfName.getBytes(), options));
        }

        // 4. 打开数据库并获取列族句柄
        DBOptions options = new DBOptions()
                .setCreateIfMissing(true) // 保持原有的"如果不存在则创建"行为
                .setCreateMissingColumnFamilies(true)
                .setInfoLogLevel(InfoLogLevel.ERROR_LEVEL) // 禁用INFO日志（LOG文件）
                .setMaxLogFileSize(1024 * 1024) // 限制日志文件大小和保留数量（避免无限增长）
                .setKeepLogFileNum(2); // 最多保留 2 个日志文件

        // 配置日志
        String logDir = DB_PATH + "rocksdb_logs/"; // 单独目录存放 RocksDB 日志
        new File(logDir).mkdirs(); // 确保目录存在
        options.setDbLogDir(logDir);

        RocksDB db = RocksDB.open(options, DB_PATH, cfDescriptors, cfHandles);

        // 5. 绑定列族句柄（索引对应cfDescriptors顺序）
        // 跳过默认列族（索引0），从1开始绑定自定义列族
        for (int i = 0; i < ColumnFamily.values().length; i++) {
            ColumnFamily.values()[i].setHandle(cfHandles.get(i + 1));
        }
        return db;
    }

    /**
     * 注册JVM关闭钩子，确保资源释放
     */
    private void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("关闭数据库资源...");
            closeInternal();
        }));
    }

    /**
     * 手动关闭数据库（一般无需调用，依赖关闭钩子）
     */
    public void close() {
        log.info("手动关闭数据库资源...");
        closeInternal();
    }

    /**
     * 内部关闭方法，统一处理资源释放
     */
    private void closeInternal() {
        // 释放列族句柄
        for (ColumnFamily cf : ColumnFamily.values()) {
            if (cf.getHandle() != null) {
                cf.getHandle().close();
            }
        }
        // 关闭数据库
        if (db != null) {
            db.close();
        }
    }


    public static String getUTXOKey(byte[] txId, int vout) {
        // 从数据库中获取 UTXO
        return CryptoUtil.bytesToHex(txId) + ":" + vout;
    }


}
