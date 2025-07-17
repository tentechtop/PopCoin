package com.pop.popcoinsystem.data.storage.test;

import com.google.common.collect.Maps;
import com.pop.popcoinsystem.data.block.Block;
import com.pop.popcoinsystem.util.SerializeUtils;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import org.rocksdb.InfoLogLevel;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;

import java.io.File;
import java.util.Map;


/**
 * 无事务非族列 简单存储
 */
@Slf4j
public class BlockChainRocksDBStorageBack {

    /**
     * 区块链数据文件
     */
    private static final String DB_FILE = "rocksDb/popCoin.db/blockChain.db/";//数据目录


    /**
     * 区块桶Key
     */
    private static final String BLOCKS_BUCKET_KEY = "blocks";
    /**
     * 链状态桶Key
     */
    private static final String CHAINSTATE_BUCKET_KEY = "chainState";

    /**
     * 最新一个区块
     */
    private static final String LAST_BLOCK_KEY = "last";

    private RocksDB db;

    /**
     * 区块桶 Map
     */
    private Map<String, byte[]> blocksBucket;

    /**
     * 链状态桶 Map
     */
    @Getter
    private Map<String, byte[]> chainStateBucket;

    public static BlockChainRocksDBStorageBack getInstance() {
        return RocksDBUtilsHolder.sInstance;
    }







    /**
     * 静态内部类
     */
    private static class RocksDBUtilsHolder {
        private static final BlockChainRocksDBStorageBack sInstance = new BlockChainRocksDBStorageBack();
    }

    private BlockChainRocksDBStorageBack() {
        openDB();
        initBlockBucket();
        initChainStateBucket();
    }

    /**
     * 打开数据库
     */
    private void openDB() {
        try {
 /*           db=RocksDB.open(DB_FILE);*/

            // 1. 创建配置对象，替代默认配置
            Options options = new Options();
            // 保持原有的"如果不存在则创建"行为
            options.setCreateIfMissing(true);

            // 2. 禁用INFO日志（LOG文件）

            options.setInfoLogLevel(InfoLogLevel.ERROR_LEVEL); // 不输出任何INFO日志
            // 可选：如果仍需部分日志，可设置为ERROR级别（只记录错误）
            // options.setInfoLogLevel(InfoLogLevel.ERROR);

            // 3. 限制日志文件大小和保留数量（避免无限增长）
            options.setMaxLogFileSize(1024 * 1024); // 单个日志文件最大 1MB
            options.setKeepLogFileNum(2); // 最多保留 2 个日志文件

            String logDir = "rocksDb/popCoin.db/blockChain.db/rocksdb_logs/"; // 单独目录存放 RocksDB 日志
            new File(logDir).mkdirs(); // 确保目录存在
            options.setDbLogDir(logDir);

            // 3. 打开数据库（使用配置好的options）
            db = RocksDB.open(options, DB_FILE);

        } catch (RocksDBException e) {
            log.error("Fail to open db ! ", e);
            throw new RuntimeException("Fail to open db ! ", e);
        }
    }

    /**
     * 初始化 blocks 区块桶
     */
    private void initBlockBucket() {
        try {
            byte[] blockBucketKey = SerializeUtils.serialize(BLOCKS_BUCKET_KEY);
            byte[] blockBucketBytes = db.get(blockBucketKey);
            if (blockBucketBytes != null) {
                blocksBucket= (Map) SerializeUtils.deSerialize(blockBucketBytes);
            }else {
                blocksBucket= Maps.newHashMap();
                db.put(blockBucketKey,SerializeUtils.serialize(blocksBucket));
            }
        } catch (RocksDBException e) {
            log.error("Fail to init block bucket ! ", e);
            throw new RuntimeException("Fail to init block bucket ! ", e);
        }
    }

    /**
     * 初始化 blocks 链状态桶
     */
    private void initChainStateBucket() {
        try {
            byte[] chainStateBucketKey = SerializeUtils.serialize(CHAINSTATE_BUCKET_KEY);
            byte[] chainStateBucketBytes = db.get(chainStateBucketKey);
            if (chainStateBucketBytes != null) {
                chainStateBucket= (Map) SerializeUtils.deSerialize(chainStateBucketBytes);
            }else {
                chainStateBucket= Maps.newHashMap();
                db.put(chainStateBucketKey,SerializeUtils.serialize(chainStateBucket));
            }
        } catch (RocksDBException e) {
            log.error("Fail to init chainState bucket ! ", e);
            throw new RuntimeException("Fail to init chainState bucket ! ", e);
        }
    }

    /**
     * 保存最新一个区块的Hash值
     *
     * @param tipBlockHash
     */
    public void putLastBlockHash(String tipBlockHash) {
        try {
            blocksBucket.put(LAST_BLOCK_KEY,SerializeUtils.serialize(tipBlockHash));
            db.put(SerializeUtils.serialize(BLOCKS_BUCKET_KEY),SerializeUtils.serialize(blocksBucket));
        } catch (RocksDBException e) {
            log.error("Fail to put last block hash ! tipBlockHash=" + tipBlockHash, e);
            throw new RuntimeException("Fail to put last block hash ! tipBlockHash=" + tipBlockHash, e);
        }
    }

    /**
     * 查询最新一个区块的Hash值
     *
     * @return
     */
    public String getLastBlockHash() {
        byte[] lastBlockHashBytes=blocksBucket.get(LAST_BLOCK_KEY);
        if(lastBlockHashBytes!=null){
            return (String) SerializeUtils.deSerialize(lastBlockHashBytes);
        }
        return "";
    }

    /**
     * 保存区块
     *
     * @param block
     */
    public void putBlock(Block block) {
        try {
            blocksBucket.put(block.getHash(),SerializeUtils.serialize(block));
            db.put(SerializeUtils.serialize(BLOCKS_BUCKET_KEY),SerializeUtils.serialize(blocksBucket));
        } catch (RocksDBException e) {
            log.error("Fail to put block ! block=" + block.toString(), e);
            throw new RuntimeException("Fail to put block ! block=" + block.toString(), e);
        }
    }

    /**
     * 查询区块
     *
     * @param blockHash
     * @return
     */
    public Block getBlock(String blockHash) {
        byte[] blockBytes=blocksBucket.get(blockHash);
        if(blockBytes!=null){
            return (Block) SerializeUtils.deSerialize(blockBytes);
        }
        throw new RuntimeException("Fail to get block ! blockHash=" + blockHash);
    }

    /**
     * 清空 chainState bucket
     */
    public void cleanChainStateBucket() {
        try {
            chainStateBucket.clear();
        } catch (Exception e) {
            log.error("Fail to clear chainstate bucket ! ", e);
            throw new RuntimeException("Fail to clear chainstate bucket ! ", e);
        }
    }

    /**
     * 关闭数据库
     */
    public void closeDB() {
        try {
            db.close();
        } catch (Exception e) {
            log.error("Fail to close db ! ", e);
            throw new RuntimeException("Fail to close db ! ", e);
        }
    }

}
