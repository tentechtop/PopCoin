package com.pop.popcoinsystem.data.storage.back;

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

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static com.pop.popcoinsystem.util.CryptoUtil.POP_NET_VERSION;


@Slf4j
public class POPStorageBack {
    // 数据库存储路径
    private static final String DB_PATH = "rocksDb/popCoin.db/blockChain" + POP_NET_VERSION + ".db/";
    private static final byte[] KEY_LAST_BLOCK_CHAIN = "key_last_block_chain".getBytes();
    private static final byte[] KEY_LAST_BLOCK_HASH = "key_last_block_hash".getBytes();
    private static final byte[] KEY_NODE_SETTING = "key_node_setting".getBytes();
    private static final byte[] KEY_MINER = "key_miner".getBytes();
    // 分片元数据相关键
    private static final byte[] KEY_UTXO_SHARD_INFO = "key_utxo_shard_info".getBytes();
    private static final byte[] KEY_UTXO_COUNT = "key_utxo_count".getBytes();



    // 使用枚举管理列族
    private enum ColumnFamily {
        BLOCK("CF_BLOCK", "block",new ColumnFamilyOptions()),
        BLOCK_INDEX("CF_BLOCK_INDEX", "blockIndex",new ColumnFamilyOptions()),
        BLOCK_CHAIN("CF_BLOCK_CHAIN", "blockChain",new ColumnFamilyOptions()),
        BLOCK_HASH("CF_BLOCK_HASH", "blockHash",new ColumnFamilyOptions()),

        UTXO("CF_UTXO", "utxo",new ColumnFamilyOptions()
                .setTableFormatConfig(new BlockBasedTableConfig()
                        .setBlockCacheSize(64 * 1024 * 1024) // 64MB 块缓存
                        .setCacheIndexAndFilterBlocks(true)) ),
        ADDRESS_UTXO("CF_ADDRESS_UTXO", "addressUtxo",new ColumnFamilyOptions()),
        MINER("CF_MINER", "miner",new ColumnFamilyOptions()),
        NODE("CF_NODE", "node",new ColumnFamilyOptions()),
        WALLET("CF_WALLET", "wallet",new ColumnFamilyOptions()),
        UTXO_SHARD_META("CF_UTXO_SHARD_META", "utxoShardMeta", new ColumnFamilyOptions()),
        UTXO_SHARD_PREFIX("CF_UTXO_SHARD_", "utxoShardMeta", new ColumnFamilyOptions()),

        ;
        private final String logicalName;
        private final String actualName;
        private final ColumnFamilyOptions options;
        ColumnFamily(String logicalName, String actualName, ColumnFamilyOptions options) {
            this.logicalName = logicalName;
            this.actualName = actualName;
            this.options = options;
        }
        @Setter
        @Getter
        private ColumnFamilyHandle handle;
    }





    private final RocksDB db;


    private static class InstanceHolder {
        private static final POPStorageBack INSTANCE = new POPStorageBack();
    }

    public static POPStorageBack getInstance() {
        return POPStorageBack.InstanceHolder.INSTANCE;
    }

    private POPStorageBack() {
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

    // ------------------------------ 数据操作 ------------------------------
    /**
     * 保存区块
     */
    public void putBlock(Block block) {
        try {
            byte[] blockHash = block.getHash();
            byte[] blockData = SerializeUtils.serialize(block);
            // 直接写入区块列族（键：区块哈希，值：序列化区块）
            db.put(ColumnFamily.BLOCK.handle, blockHash, blockData);
            // 更新最新区块哈希
            db.put(ColumnFamily.BLOCK_HASH.handle, KEY_LAST_BLOCK_HASH, blockHash);
            //高度→区块哈希 索引
            db.put(ColumnFamily.BLOCK_INDEX.handle, ByteUtils.toBytes(block.getHeight()), blockHash);

            //交易id -> 区块哈希

        } catch (RocksDBException e) {
            log.error("保存区块失败: blockHash={}", block.getHash(), e);
            throw new RuntimeException("保存区块失败", e);
        }
    }

    //通过高度查询区块
    public Block getBlockByHeight(long height){
        try {
            byte[] blockData = db.get(ColumnFamily.BLOCK_INDEX.handle, ByteUtils.toBytes(height));
            if (blockData == null) {
                throw new RuntimeException("区块不存在: " + height);
            }
            return (Block) SerializeUtils.deSerialize(blockData);
        } catch (RocksDBException e) {
            throw new RuntimeException("查询区块失败", e);
        }
    }


    /**
     * 查询区块
     */
    public Block getBlock(byte[] blockHash) {
        try {
            byte[] blockData = db.get(ColumnFamily.BLOCK.handle, blockHash);
            if (blockData == null) {
                throw new RuntimeException("区块不存在: " + CryptoUtil.bytesToHex(blockHash));
            }
            return (Block) SerializeUtils.deSerialize(blockData);
        } catch (RocksDBException e) {
            log.error("查询区块失败: blockHash={}", CryptoUtil.bytesToHex(blockHash), e);
            throw new RuntimeException("查询区块失败", e);
        }
    }

    /**
     * 根据区块哈希查询其前100个区块
     * @param blockHash 目标区块的哈希
     * @return 前100个区块（按顺序：前1个 -> 前100个），不足100个则返回实际数量
     */
    public List<Block> getPrevious100Blocks(byte[] blockHash) {
        try {
            List<Block> result = new ArrayList<>(100);
            Block currentBlock = getBlock(blockHash); // 先获取目标区块

            // 最多追溯100个区块
            for (int i = 0; i < 100; i++) {
                byte[] previousHash = currentBlock.getPreviousHash();
                if (previousHash == null || previousHash.length==0) {
                    // 已追溯到创世块（没有前序区块），终止循环
                    break;
                }
                // 获取前序区块
                Block prevBlock = getBlock(previousHash);
                result.add(prevBlock);

                // 继续追溯下一个前序区块
                currentBlock = prevBlock;
            }
            return result;
        } catch (RuntimeException e) {
            log.error("查询前100个区块失败: 起始区块哈希={}", blockHash, e);
            throw e;
        }
    }

    /**
     * 根据区块哈希查询其前序区块（最多1000个）
     * @param blockHash 目标区块的哈希
     * @param count     需要查询的前序区块数量（范围：1-1000）
     * @return 前序区块列表（按顺序：前1个 -> 前N个），不足则返回实际数量
     */
    public List<Block> getPreviousBlocks(byte[] blockHash, int count) {
        // 参数校验
        if (count <= 0 || count > 1000) {
            throw new IllegalArgumentException("查询数量必须在1-1000之间");
        }
        try {
            List<Block> result = new ArrayList<>(count);
            Block currentBlock = getBlock(blockHash); // 获取目标区块
            // 循环追溯前序区块
            for (int i = 0; i < count; i++) {
                byte[] previousHash = currentBlock.getPreviousHash();
                if (previousHash == null || previousHash.length == 0) {
                    // 已追溯到创世块，终止循环
                    break;
                }
                // 获取前序区块
                Block prevBlock = getBlock(previousHash);
                result.add(prevBlock);
                // 继续追溯下一个前序区块
                currentBlock = prevBlock;
            }
            return result;
        } catch (RuntimeException e) {
            log.error("查询前序区块失败: 起始区块哈希={}, 查询数量={}", blockHash, count, e);
            throw e;
        }
    }

    public List<Block> getPreviousBlocksAndSelf(byte[] blockHash, int count) {
        // 参数校验
        if (count <= 0 || count > 1000) {
            throw new IllegalArgumentException("查询数量必须在1-1000之间");
        }
        try {
            List<Block> result = new ArrayList<>(count);
            Block currentBlock = getBlock(blockHash); // 获取目标区块
            // **先将起始区块加入结果列表**
            result.add(currentBlock);

            // 循环追溯前序区块（最多count-1个，因为起始区块已占1个名额）
            for (int i = 1; i < count; i++) { // 注意：i从1开始
                byte[] previousHash = currentBlock.getPreviousHash();
                if (previousHash == null || previousHash.length==0) {
                    // 已追溯到创世块，终止循环
                    break;
                }
                // 获取前序区块
                Block prevBlock = getBlock(previousHash);
                result.add(prevBlock);
                // 继续追溯下一个前序区块
                currentBlock = prevBlock;
            }
            return result;
        } catch (RuntimeException e) {
            log.error("查询前序区块失败: 起始区块哈希={}, 查询数量={}", blockHash, count, e);
            throw e;
        }
    }

    //新增或者修改-本节点的设置信息 key - NODE_SETTING_KEY
    public void addOrUpdateNodeSetting(NodeSettings value) {
        try {
            byte[] valueBytes = SerializeUtils.serialize(value);
            db.put(ColumnFamily.NODE.handle, KEY_NODE_SETTING, valueBytes);
        } catch (RocksDBException e) {
            log.error("节点状态失败: key={}", KEY_NODE_SETTING, e);
            throw new RuntimeException("节点状态失败", e);
        }
    }

    //获取本节点的设置信息
    public NodeSettings getNodeSetting() {
        try {
            byte[] valueBytes = db.get(ColumnFamily.NODE.handle, KEY_NODE_SETTING);
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
            db.put(ColumnFamily.MINER.handle, KEY_MINER, valueBytes);
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
            byte[] valueBytes = db.get(ColumnFamily.MINER.handle, KEY_MINER);
            if (valueBytes == null) {
                return null; // 不存在返回null，避免抛出异常
            }
            return (Miner)SerializeUtils.deSerialize(valueBytes);
        } catch (RocksDBException e) {
            log.error("获取矿工信息失败: key={}", KEY_MINER, e);
            throw new RuntimeException("获取矿工信息失败", e);
        }
    }








    //UTXO.............................................................................................................

    public void addUtxo(UTXO utxo) {
        try {
            String utxoKey = CryptoUtil.bytesToHex(utxo.getTxId()) + ":" + utxo.getVout();
            byte[] valueBytes = SerializeUtils.serialize(utxo);
            db.put(ColumnFamily.UTXO.handle, utxoKey.getBytes(), valueBytes);
        } catch (RocksDBException e) {
            log.error("保存UTXO失败: key={}", KEY_MINER, e);
            throw new RuntimeException("保存UTXO失败", e);
        }
    }
    synchronized public void addUtxos(List<UTXO> batch) {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        WriteBatch writeBatch = null;
        try {
            // 创建写入批次
            writeBatch = new WriteBatch();
            // 遍历UTXO列表，添加到批次中
            for (UTXO utxo : batch) {
                String utxoKey = CryptoUtil.bytesToHex(utxo.getTxId()) + ":" + utxo.getVout();
                byte[] valueBytes = SerializeUtils.serialize(utxo);
                writeBatch.put(ColumnFamily.UTXO.handle, utxoKey.getBytes(), valueBytes);
            }
            // 配置写入选项（根据需求调整）
            WriteOptions writeOptions = new WriteOptions();
            writeOptions.setSync(false); // 禁用同步写入以提高性能
            // 执行批量写入
            db.write(writeOptions, writeBatch);
        } catch (RocksDBException e) {
            log.error("批量添加UTXO失败，大小: {}", batch.size(), e);
            throw new RuntimeException("批量添加UTXO失败", e);
        } finally {
            // 确保资源释放
            if (writeBatch != null) {
                writeBatch.close();
            }
        }
    }


    //删除
    public void deleteUtxo(UTXO utxo) {
        try {
            String utxoKey = CryptoUtil.bytesToHex(utxo.getTxId()) + ":" + utxo.getVout();
            db.delete(ColumnFamily.UTXO.handle, utxoKey.getBytes());
        } catch (RocksDBException e) {
            log.error("删除UTXO失败: key={}", utxo.getTxId(), e);
            throw new RuntimeException("删除UTXO失败", e);
        }
    }
    //批量删除
    public void deleteUtxos(List<UTXO> batch) {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        WriteBatch writeBatch = null;
        try {
            // 创建写入批次
            writeBatch = new WriteBatch();
            // 遍历UTXO列表，添加删除操作到批次中
            for (UTXO utxo : batch) {
                String utxoKey = CryptoUtil.bytesToHex(utxo.getTxId()) + ":" + utxo.getVout();
                writeBatch.delete(ColumnFamily.UTXO.handle, utxoKey.getBytes());
            }
            // 配置写入选项（根据需求调整）
            WriteOptions writeOptions = new WriteOptions();
            writeOptions.setSync(false); // 禁用同步写入以提高性能
            // 执行批量删除
            db.write(writeOptions, writeBatch);
        } catch (RocksDBException e) {
            log.error("批量删除UTXO失败，大小: {}", batch.size(), e);
            throw new RuntimeException("批量删除UTXO失败", e);
        } finally {
            // 确保资源释放
            if (writeBatch != null) {
                writeBatch.close();
            }
        }
    }

    //根据txid 和 vout查询
    public UTXO getUtxo(byte[] txId, int vout) {
        try {
            // 构建UTXO键（格式：txIdHex:vout）
            String utxoKey = CryptoUtil.bytesToHex(txId) + ":" + vout;
            byte[] keyBytes = utxoKey.getBytes();

            // 从数据库读取
            byte[] valueBytes = db.get(ColumnFamily.UTXO.handle, keyBytes);

            // 反序列化为UTXO对象
            if (valueBytes != null && valueBytes.length > 0) {
                return (UTXO) SerializeUtils.deSerialize(valueBytes);
            }

            return null; // 未找到
        } catch (RocksDBException e) {
            log.error("查询UTXO失败: txId={}, vout={}",
                    CryptoUtil.bytesToHex(txId), vout, e);
            throw new RuntimeException("查询UTXO失败", e);
        }
    }
    //根据key查询
    public UTXO getUtxo(String utxoKey) {
        try {
            // 构建UTXO键（格式：txIdHex:vout）
            byte[] keyBytes = utxoKey.getBytes();
            // 从数据库读取
            byte[] valueBytes = db.get(ColumnFamily.UTXO.handle, keyBytes);
            // 反序列化为UTXO对象
            if (valueBytes != null && valueBytes.length > 0) {
                return (UTXO) SerializeUtils.deSerialize(valueBytes);
            }
            return null; // 未找到
        } catch (RocksDBException e) {
            log.error("查询UTXO失败: txId={}", utxoKey);
            throw new RuntimeException("查询UTXO失败", e);
        }
    }
    //查询所有的UTXO
    public List<UTXO> getAllUtxos() {
        RocksIterator iterator = null;
        try {
            // 创建迭代器并定位到第一个键
            iterator = db.newIterator(ColumnFamily.UTXO.handle);
            iterator.seekToFirst();
            // 遍历所有键值对
            List<UTXO> result = new ArrayList<>();
            while (iterator.isValid()) {
                byte[] valueBytes = iterator.value();
                UTXO utxo = (UTXO) SerializeUtils.deSerialize(valueBytes);
                result.add(utxo);
                iterator.next();
            }
            return result;
        }catch (Exception e){
            log.error("查询所有UTXO失败", e);
            throw new RuntimeException("查询所有UTXO失败", e);
        } finally {
            // 确保迭代器资源被释放
            if (iterator != null) {
                iterator.close();
            }
        }
    }

}
