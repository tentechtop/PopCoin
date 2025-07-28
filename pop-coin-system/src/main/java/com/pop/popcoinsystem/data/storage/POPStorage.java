package com.pop.popcoinsystem.data.storage;

import com.pop.popcoinsystem.data.block.Block;
import com.pop.popcoinsystem.data.miner.Miner;
import com.pop.popcoinsystem.data.transaction.UTXO;
import com.pop.popcoinsystem.network.common.NodeSettings;
import com.pop.popcoinsystem.util.ByteUtils;
import com.pop.popcoinsystem.util.CryptoUtil;
import com.pop.popcoinsystem.util.SerializeUtils;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.rocksdb.*;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;


import static com.pop.popcoinsystem.util.CryptoUtil.POP_NET_VERSION;


@Slf4j
public class POPStorage {
    // 数据库存储路径
    private static final String DB_PATH = "rocksDb/popCoin.db/blockChain" + POP_NET_VERSION + ".db/";
    //这些KEY都保存在BLOCK_CHAIN 中 因为他们单独特殊
    private static final byte[] KEY_UTXO_COUNT = "key_utxo_count".getBytes();//UTXO总数
    private static final byte[] KEY_GENESIS_BLOCK_HASH = "key_genesis_block_hash".getBytes();//创世区块hash
    private static final byte[] KEY_MAIN_LATEST_HEIGHT = "key_main_latest_height".getBytes();//主链当前高度 最新高度
    private static final byte[] KEY_MAIN_LATEST_BLOCK_HASH = "key_main_latest_block_hash".getBytes();


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
    //更新主链高度到区块的索引
    //保存主链中高度到区块hash索引
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
            // 直接写入区块列族（键：区块哈希，值：序列化区块）
            db.put(ColumnFamily.BLOCK.getHandle(), blockHash, blockData);
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
                byte[] blockData = SerializeUtils.serialize(block);
                writeBatch.put(ColumnFamily.BLOCK.getHandle(), blockHash, blockData);
            }
            db.write(writeOptions, writeBatch);
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
    //删除区块
    public void deleteBlock(byte[] hash) {
        try {
            db.delete(ColumnFamily.BLOCK.getHandle(), hash);
        } catch (RocksDBException e) {
            log.error("删除区块失败: blockHash={}", hash, e);
            throw new RuntimeException("删除区块失败", e);
        }
    }
    //批量删除区块
    public void deleteBlockBatch(List<byte[]> hashes) {
        rwLock.writeLock().lock();
        WriteBatch writeBatch = null;
        try {
            writeBatch = new WriteBatch();
            WriteOptions writeOptions = new WriteOptions();
            for (byte[] hash : hashes) {
                writeBatch.delete(ColumnFamily.BLOCK.getHandle(), hash);
            }
            db.write(writeOptions, writeBatch);
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
    //根据hash获取区块
    public Block getBlockByHash(byte[] hash) {
        if (hash == null){
            return null;
        }
        try {
            byte[] blockData = db.get(ColumnFamily.BLOCK.getHandle(), hash);
            if (blockData == null) {
                return null;
            }
            return (Block)SerializeUtils.deSerialize(blockData);
        } catch (RocksDBException e) {
            log.error("获取区块失败: blockHash={}", hash, e);
            throw new RuntimeException("获取区块失败", e);
        }
    }













    //UTXO操作........................................................................................................
    public void putUTXO(UTXO utxo) {
        byte[] serialize = SerializeUtils.serialize(utxo);
        try {
            byte[] key = (CryptoUtil.bytesToHex(utxo.getTxId()) + ":" + utxo.getVout()).getBytes();
            db.put(ColumnFamily.UTXO.getHandle(),key,serialize );
            //UTXO总数
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
        try {
            String utxoKey = getUTXOKey(txId, vout);
            db.delete(ColumnFamily.UTXO.getHandle(), utxoKey.getBytes());
            //UTXO总数
            byte[] bytes = db.get(ColumnFamily.BLOCK_CHAIN.getHandle(), KEY_UTXO_COUNT);
            long count = bytes == null ? 0 : ByteUtils.bytesToLong(bytes);
            db.put(ColumnFamily.BLOCK_CHAIN.getHandle(), KEY_UTXO_COUNT, ByteUtils.toBytes(count - 1));
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
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
                writeBatch.delete(ColumnFamily.UTXO.getHandle(), utxoKey.getBytes());
            }
            db.write(writeOptions, writeBatch);
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
    //分页获取UTXO 每次5000个
    /**
     * 分页查询 UTXO 集合
     * @param pageSize 每页大小
     * @param lastKey 上一页的最后一个键（第一页传 null）
     * @return 分页结果（包含当前页 UTXO 列表和当前页最后一个键）
     * lastKey 为第一次 会包含在查询结果里面
     */
    public PageResult<UTXO> queryUTXOPage(int pageSize, String lastKey) {
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
            return new PageResult<>(utxoList, currentLastKey, count < pageSize); // 最后一页的标志：实际数量 < pageSize
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




    // 分页结果封装类
    @Getter
    @Setter
    public static class PageResult<T> {
        private List<T> data; // 当前页数据
        private String lastKey; // 当前页最后一个键（用于下一页查询）
        private boolean isLastPage; // 是否为最后一页

        public PageResult(List<T> data, String lastKey, boolean isLastPage) {
            this.data = data;
            this.lastKey = lastKey;
            this.isLastPage = isLastPage;
        }
    }


























    //新增或者修改-本节点的设置信息 key - NODE_SETTING_KEY
    public void addOrUpdateNodeSetting(NodeSettings value) {
        try {
            byte[] valueBytes = SerializeUtils.serialize(value);
            db.put(ColumnFamily.BLOCK_CHAIN.getHandle(), KEY_NODE_SETTING, valueBytes);
        } catch (RocksDBException e) {
            log.error("节点状态失败: key={}", KEY_NODE_SETTING, e);
            throw new RuntimeException("节点状态失败", e);
        }
    }

    //获取本节点的设置信息
    public NodeSettings getNodeSetting() {
        try {
            byte[] valueBytes = db.get(ColumnFamily.BLOCK_CHAIN.getHandle(), KEY_NODE_SETTING);
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
            db.put(ColumnFamily.BLOCK_CHAIN.getHandle(), KEY_MINER, valueBytes);
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
            byte[] valueBytes = db.get(ColumnFamily.BLOCK_CHAIN.getHandle(), KEY_MINER);
            if (valueBytes == null) {
                return null; // 不存在返回null，避免抛出异常
            }
            return (Miner)SerializeUtils.deSerialize(valueBytes);
        } catch (RocksDBException e) {
            log.error("获取矿工信息失败: key={}", KEY_MINER, e);
            throw new RuntimeException("获取矿工信息失败", e);
        }
    }



    //
    private final ReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final RocksDB db;
    private static class InstanceHolder {
        private static final POPStorage INSTANCE = new POPStorage();
    }
    public static POPStorage getInstance() {
        return POPStorage.InstanceHolder.INSTANCE;
    }

    private POPStorage() {
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
