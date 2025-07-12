package com.pop.popcoinsystem.data.storage;

import com.pop.popcoinsystem.data.block.Block;
import com.pop.popcoinsystem.util.SerializeUtils;
import lombok.extern.slf4j.Slf4j;
import org.rocksdb.*;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;


@Slf4j
public class TransactionBlockChainStorage {

    // 数据库存储路径
    private static final String DB_PATH = "rocksDb/popCoin.db/transaction/blockChain.db/";

    // 列族名称（逻辑分区）
    private static final String CF_BLOCKS = "blocks";       // 存储区块
    private static final String CF_CHAINSTATE = "chainstate"; // 存储链状态
    private static final String CF_METADATA = "metadata";   // 存储元数据（如最新区块哈希）

    // 元数据键（存储在CF_METADATA列族）
    private static final byte[] KEY_LAST_BLOCK_HASH = "last_block_hash".getBytes();


    private final TransactionDB  db;

    private final ReadWriteLock rwLock = new ReentrantReadWriteLock();


    // 列族句柄（需要在关闭时释放）
    //用于标识和操作特定的 Column Family（列族），是连接应用程序与 RocksDB 内部列族数据的 “桥梁”。
    //使用 RocksDB 原生列族时，数据读写直接操作磁盘（配合内存缓存），不会有这种 “全量加载到内存” 的问题，更适合区块链这种需要持久化大量数据的场景。
    //而是随用随取，用完即弃（或仅在缓存中短暂保留）。
    private ColumnFamilyHandle cfBlocks;
    private ColumnFamilyHandle cfChainState;
    private ColumnFamilyHandle cfMetadata;


    // 单例实例
    private static class InstanceHolder {
        private static final TransactionBlockChainStorage INSTANCE = new TransactionBlockChainStorage();
    }


    public static TransactionBlockChainStorage getInstance() {
        return TransactionBlockChainStorage.InstanceHolder.INSTANCE;
    }

    private TransactionBlockChainStorage() {
        try {
            this.db = openRocksDBWithColumnFamilies();
            registerShutdownHook();
        } catch (RocksDBException e) {
            log.error("初始化数据库失败", e);
            throw new RuntimeException("数据库初始化失败", e);
        }
    }



    private TransactionDB openRocksDBWithColumnFamilies() throws RocksDBException {
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
        for (String cfName : new String[]{CF_BLOCKS, CF_CHAINSTATE, CF_METADATA}) {
            if (!existingCfNames.contains(cfName)) {
                cfDescriptors.add(new ColumnFamilyDescriptor(cfName.getBytes(), new ColumnFamilyOptions()));
            } else {
                cfDescriptors.add(new ColumnFamilyDescriptor(cfName.getBytes(), new ColumnFamilyOptions()));
            }
        }

        // 配置事务数据库

        // 4. 打开数据库并获取列族句柄
        DBOptions options = new DBOptions()
                .setCreateIfMissing(true)
                .setCreateMissingColumnFamilies(true)
                .setInfoLogLevel(InfoLogLevel.ERROR_LEVEL)
                .setMaxLogFileSize(1024 * 1024)
                .setKeepLogFileNum(2);
        String logDir = DB_PATH+"rocksdb_logs/"; // 单独目录存放 RocksDB 日志
        new File(logDir).mkdirs(); // 确保目录存在
        options.setDbLogDir(logDir);

        TransactionDBOptions transactionDBOptions = new TransactionDBOptions();
        transactionDBOptions.setMaxNumLocks(1000000);



        TransactionDB txnDb = null;

        // 打开事务数据库
        // 数据库全局选项
        // 事务数据库选项
        // 数据库路径
        // 列族描述符列表
        // 用于存储打开的列族句柄
        TransactionDB db = TransactionDB.open(options, transactionDBOptions, DB_PATH, cfDescriptors, cfHandles);


        // 5. 绑定列族句柄（索引对应cfDescriptors顺序）
        this.cfBlocks = cfHandles.get(1);         // 对应CF_BLOCKS
        this.cfChainState = cfHandles.get(2);     // 对应CF_CHAINSTATE
        this.cfMetadata = cfHandles.get(3);       // 对应CF_METADATA
        return db;
    }

    /**
     * 注册JVM关闭钩子，确保资源释放
     */
    private void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("关闭数据库资源...");
            try {
                // 释放列族句柄
                if (cfBlocks != null) cfBlocks.close();
                if (cfChainState != null) cfChainState.close();
                if (cfMetadata != null) cfMetadata.close();
                // 关闭数据库
                if (db != null) db.close();
            } catch (Exception e) {
                log.error("数据库关闭失败", e);
            }
        }));
    }


    // ------------------------------ 区块操作 ------------------------------
    /**
     * 保存区块
     */
    /**
     * 显式事务：保存区块+更新最新区块哈希（确保原子性）
     * 两个操作必须同时成功/失败，避免数据不一致
     */
    public void putBlock(Block block) {
        rwLock.writeLock().lock();
        Transaction tx = null;
        try {
            // 1. 创建事务（可配置写选项，如同步刷盘）
            WriteOptions writeOptions = new WriteOptions().setSync(true);  // 强制刷盘，确保数据持久化
            tx = db.beginTransaction(writeOptions);  // 开启事务

            // 2. 事务内执行操作（多个操作）
            byte[] blockHash = block.getHash().getBytes();
            byte[] blockData = SerializeUtils.serialize(block);

            // 操作1：保存区块到blocks列族
            tx.put(cfBlocks, blockHash, blockData);
            // 操作2：更新最新区块哈希到metadata列族
            tx.put(cfMetadata, KEY_LAST_BLOCK_HASH, blockHash);

            // 3. 提交事务（所有操作原子生效）
            tx.commit();
            log.info("事务提交成功: 区块哈希={}", block.getHash());

        } catch (RocksDBException e) {
            // 4. 若失败，回滚事务（所有操作失效）
            if (tx != null) {
                try {
                    tx.rollback();
                    log.warn("事务回滚成功: 区块哈希={}", block.getHash());
                } catch (RocksDBException rollbackEx) {
                    log.error("事务回滚失败", rollbackEx);
                }
            }
            log.error("保存区块事务失败: blockHash={}", block.getHash(), e);
            throw new RuntimeException("保存区块失败", e);
        } finally {
            // 5. 释放资源
            if (tx != null) {
                tx.close();  // 关闭事务（无论成功失败都需释放）
            }
            rwLock.writeLock().unlock();
        }
    }


    /**
     * 显式事务：批量保存多个区块（确保全部成功或全部失败）
     */
    public void putBlocksInBatch(List<Block> blocks) {
        if (blocks.isEmpty()) {
            return;
        }
        rwLock.writeLock().lock();
        Transaction tx = null;
        try {
            // 1. 创建事务
            WriteOptions writeOptions = new WriteOptions().setSync(true);
            tx = db.beginTransaction(writeOptions);

            // 2. 事务内批量操作
            Block lastBlock = blocks.get(blocks.size() - 1);  // 最后一个区块作为最新区块
            for (Block block : blocks) {
                byte[] blockHash = block.getHash().getBytes();
                byte[] blockData = SerializeUtils.serialize(block);
                tx.put(cfBlocks, blockHash, blockData);  // 批量保存区块
            }
            // 更新最新区块哈希
            tx.put(cfMetadata, KEY_LAST_BLOCK_HASH, lastBlock.getHash().getBytes());

            // 3. 提交事务
            tx.commit();
            log.info("批量区块事务提交成功: 共{}个区块，最新哈希={}", blocks.size(), lastBlock.getHash());

        } catch (RocksDBException e) {
            // 4. 回滚事务
            if (tx != null) {
                try {
                    tx.rollback();
                    log.warn("批量区块事务回滚成功: 共{}个区块", blocks.size());
                } catch (RocksDBException rollbackEx) {
                    log.error("批量事务回滚失败", rollbackEx);
                }
            }
            log.error("批量保存区块事务失败", e);
            throw new RuntimeException("批量保存区块失败", e);
        } finally {
            if (tx != null) {
                tx.close();
            }
            rwLock.writeLock().unlock();
        }
    }





    /**
     * 查询区块
     */
    public Block getBlock(String blockHash) {
        rwLock.readLock().lock();
        try {
            byte[] blockData = db.get(cfBlocks, blockHash.getBytes());
            if (blockData == null) {
                throw new RuntimeException("区块不存在: " + blockHash);
            }
            return (Block) SerializeUtils.deSerialize(blockData);
        } catch (RocksDBException e) {
            log.error("查询区块失败: blockHash={}", blockHash, e);
            throw new RuntimeException("查询区块失败", e);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    //按照hash查询前100个区块

    /**
     * 根据区块哈希查询其前100个区块
     * @param blockHash 目标区块的哈希
     * @return 前100个区块（按顺序：前1个 -> 前100个），不足100个则返回实际数量
     */
    public List<Block> getPrevious100Blocks(String blockHash) {
        rwLock.readLock().lock();
        try {
            List<Block> result = new ArrayList<>(100);
            Block currentBlock = getBlock(blockHash); // 先获取目标区块

            // 最多追溯100个区块
            for (int i = 0; i < 100; i++) {
                String prevHash = currentBlock.getPreviousHash();
                if (prevHash == null || prevHash.isEmpty()) {
                    // 已追溯到创世块（没有前序区块），终止循环
                    break;
                }
                // 获取前序区块
                Block prevBlock = getBlock(prevHash);
                result.add(prevBlock);

                // 继续追溯下一个前序区块
                currentBlock = prevBlock;
            }
            return result;
        } catch (RuntimeException e) {
            log.error("查询前100个区块失败: 起始区块哈希={}", blockHash, e);
            throw e;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * 根据区块哈希查询其前序区块（最多1000个）
     * @param blockHash 目标区块的哈希
     * @param count     需要查询的前序区块数量（范围：1-1000）
     * @return 前序区块列表（按顺序：前1个 -> 前N个），不足则返回实际数量
     */
    public List<Block> getPreviousBlocks(String blockHash, int count) {
        // 参数校验
        if (count <= 0 || count > 1000) {
            throw new IllegalArgumentException("查询数量必须在1-1000之间");
        }
        rwLock.readLock().lock();
        try {
            List<Block> result = new ArrayList<>(count);
            Block currentBlock = getBlock(blockHash); // 获取目标区块
            // 循环追溯前序区块
            for (int i = 0; i < count; i++) {
                String prevHash = currentBlock.getPreviousHash();
                if (prevHash == null || prevHash.isEmpty()) {
                    // 已追溯到创世块，终止循环
                    break;
                }
                // 获取前序区块
                Block prevBlock = getBlock(prevHash);
                result.add(prevBlock);
                // 继续追溯下一个前序区块
                currentBlock = prevBlock;
            }
            return result;
        } catch (RuntimeException e) {
            log.error("查询前序区块失败: 起始区块哈希={}, 查询数量={}", blockHash, count, e);
            throw e;
        } finally {
            rwLock.readLock().unlock();
        }
    }


    public List<Block> getPreviousBlocksAndSelf(String blockHash, int count) {
        // 参数校验
        if (count <= 0 || count > 1000) {
            throw new IllegalArgumentException("查询数量必须在1-1000之间");
        }

        rwLock.readLock().lock();
        try {
            List<Block> result = new ArrayList<>(count);
            Block currentBlock = getBlock(blockHash); // 获取目标区块

            // **先将起始区块加入结果列表**
            result.add(currentBlock);

            // 循环追溯前序区块（最多count-1个，因为起始区块已占1个名额）
            for (int i = 1; i < count; i++) { // 注意：i从1开始
                String prevHash = currentBlock.getPreviousHash();
                if (prevHash == null || prevHash.isEmpty()) {
                    // 已追溯到创世块，终止循环
                    break;
                }

                // 获取前序区块
                Block prevBlock = getBlock(prevHash);
                result.add(prevBlock);

                // 继续追溯下一个前序区块
                currentBlock = prevBlock;
            }

            return result;
        } catch (RuntimeException e) {
            log.error("查询前序区块失败: 起始区块哈希={}, 查询数量={}", blockHash, count, e);
            throw e;
        } finally {
            rwLock.readLock().unlock();
        }
    }


    // ------------------------------ 链状态操作 ------------------------------

    /**
     * 保存链状态（键值对）
     */
    public void putChainState(String key, Object value) {
        rwLock.writeLock().lock();
        try {
            byte[] valueBytes = SerializeUtils.serialize(value);
            db.put(cfChainState, key.getBytes(), valueBytes);
        } catch (RocksDBException e) {
            log.error("保存链状态失败: key={}", key, e);
            throw new RuntimeException("保存链状态失败", e);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * 显式事务：保存链状态（键值对）
     * 支持批量原子更新多个键值对
     */
    public void putChainState(Map<String, Object> keyValuePairs) {
        if (keyValuePairs == null || keyValuePairs.isEmpty()) {
            return;
        }
        rwLock.writeLock().lock();
        Transaction tx = null;
        try {
            // 创建事务并配置写选项
            WriteOptions writeOptions = new WriteOptions().setSync(true);
            tx = db.beginTransaction(writeOptions);

            // 事务内批量更新链状态
            for (Map.Entry<String, Object> entry : keyValuePairs.entrySet()) {
                byte[] keyBytes = entry.getKey().getBytes();
                byte[] valueBytes = SerializeUtils.serialize(entry.getValue());
                tx.put(cfChainState, keyBytes, valueBytes);
            }

            // 提交事务
            tx.commit();
            log.info("链状态事务提交成功: 共{}个键值对", keyValuePairs.size());

        } catch (RocksDBException e) {
            // 回滚事务
            try {
                tx.rollback();
                log.warn("链状态事务回滚成功");
            } catch (RocksDBException rollbackEx) {
                log.error("链状态事务回滚失败", rollbackEx);
            }
            log.error("保存链状态事务失败", e);
            throw new RuntimeException("保存链状态失败", e);
        } finally {
            if (tx != null) {
                tx.close();
            }
            rwLock.writeLock().unlock();
        }
    }



    /**
     * 获取链状态
     */
    public <T> T getChainState(String key, Class<T> clazz) {
        rwLock.readLock().lock();
        try {
            byte[] valueBytes = db.get(cfChainState, key.getBytes());
            if (valueBytes == null) {
                return null; // 不存在返回null，避免抛出异常
            }
            return clazz.cast(SerializeUtils.deSerialize(valueBytes));
        } catch (RocksDBException e) {
            log.error("获取链状态失败: key={}", key, e);
            throw new RuntimeException("获取链状态失败", e);
        } finally {
            rwLock.readLock().unlock();
        }
    }


    /**
     * 清空链状态
     */
    public void cleanChainStateBucket() {
        rwLock.writeLock().lock();
        try {
            // 遍历并删除链状态列族所有数据
            try ( RocksIterator iterator = db.newIterator(cfChainState) ) {
                iterator.seekToFirst();
                while (iterator.isValid()) {
                    db.delete(cfChainState, iterator.key());
                    iterator.next();
                }
            }
        } catch (RocksDBException e) {
            log.error("清空链状态失败", e);
            throw new RuntimeException("清空链状态失败", e);
        } finally {
            rwLock.writeLock().unlock();
        }
    }





    /**
     * 手动关闭数据库（一般无需调用，依赖关闭钩子）
     */
    public void close() {
        try {
            if (cfBlocks != null) cfBlocks.close();
            if (cfChainState != null) cfChainState.close();
            if (cfMetadata != null) cfMetadata.close();
            if (db != null) db.close();
        } catch (Exception e) {
            log.error("手动关闭数据库失败", e);
        }
    }
}
