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

import java.io.File;
import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.pop.popcoinsystem.util.CryptoUtil.POP_NET_VERSION;


@Slf4j
public class POPStorage {

    private static final byte[] KEY_LAST_BLOCK_CHAIN = "key_last_block_chain".getBytes();
    private static final byte[] KEY_LAST_BLOCK_HASH = "key_last_block_hash".getBytes();
    private static final byte[] KEY_NODE_SETTING = "key_node_setting".getBytes();
    private static final byte[] KEY_MINER = "key_miner".getBytes();



    // 使用枚举管理列族

    private enum ColumnFamily {
        BLOCK("CF_BLOCK", "block",new ColumnFamilyOptions()),
        BLOCK_INDEX("CF_BLOCK_INDEX", "blockIndex",new ColumnFamilyOptions()),
        BLOCK_CHAIN("CF_BLOCK_CHAIN", "blockChain",new ColumnFamilyOptions()),
        BLOCK_HASH("CF_BLOCK_HASH", "blockHash",new ColumnFamilyOptions()),
        UTXO("CF_UTXO", "utxo",new ColumnFamilyOptions()),
        ADDRESS_UTXO("CF_ADDRESS_UTXO", "addressUtxo",new ColumnFamilyOptions()),
        MINER("CF_MINER", "miner",new ColumnFamilyOptions()),
        NODE("CF_NODE", "node",new ColumnFamilyOptions()),
        WALLET("CF_WALLET", "wallet",new ColumnFamilyOptions()),
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

    // 数据库存储路径
    private static final String DB_PATH = "rocksDb/popCoin.db/blockChain" + POP_NET_VERSION + ".db/";
    private final RocksDB db;
    private final ReadWriteLock rwLock = new ReentrantReadWriteLock();

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
        try {
            // 使用线程安全的方式释放资源
            rwLock.writeLock().lock();
            try {
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
            } finally {
                rwLock.writeLock().unlock();
            }
        } catch (Exception e) {
            log.error("数据库关闭失败", e);
        }
    }

    // ------------------------------ 数据操作 ------------------------------
    /**
     * 保存区块
     */
    public void putBlock(Block block) {
        rwLock.writeLock().lock();
        try {
            byte[] blockHash = block.getHash();
            byte[] blockData = SerializeUtils.serialize(block);
            // 直接写入区块列族（键：区块哈希，值：序列化区块）
            db.put(ColumnFamily.BLOCK.handle, blockHash, blockData);
            // 更新最新区块哈希
            db.put(ColumnFamily.BLOCK_HASH.handle, KEY_LAST_BLOCK_HASH, blockHash);
            //高度→区块哈希 索引
            db.put(ColumnFamily.BLOCK_INDEX.handle, ByteUtils.toBytes(block.getHeight()), blockHash);
        } catch (RocksDBException e) {
            log.error("保存区块失败: blockHash={}", block.getHash(), e);
            throw new RuntimeException("保存区块失败", e);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    //通过高度查询区块
    public Block getBlockByHeight(long height){
        rwLock.readLock().lock();
        try {
            byte[] blockData = db.get(ColumnFamily.BLOCK_INDEX.handle, ByteUtils.toBytes(height));
            if (blockData == null) {
                throw new RuntimeException("区块不存在: " + height);
            }
            return (Block) SerializeUtils.deSerialize(blockData);
        } catch (RocksDBException e) {
            throw new RuntimeException("查询区块失败", e);
        } finally {
            rwLock.readLock().unlock();
        }
    }


    /**
     * 查询区块
     */
    public Block getBlock(byte[] blockHash) {
        rwLock.readLock().lock();
        try {
            byte[] blockData = db.get(ColumnFamily.BLOCK.handle, blockHash);
            if (blockData == null) {
                throw new RuntimeException("区块不存在: " + CryptoUtil.bytesToHex(blockHash));
            }
            return (Block) SerializeUtils.deSerialize(blockData);
        } catch (RocksDBException e) {
            log.error("查询区块失败: blockHash={}", CryptoUtil.bytesToHex(blockHash), e);
            throw new RuntimeException("查询区块失败", e);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * 根据区块哈希查询其前100个区块
     * @param blockHash 目标区块的哈希
     * @return 前100个区块（按顺序：前1个 -> 前100个），不足100个则返回实际数量
     */
    public List<Block> getPrevious100Blocks(byte[] blockHash) {
        rwLock.readLock().lock();
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
    public List<Block> getPreviousBlocks(byte[] blockHash, int count) {
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
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public List<Block> getPreviousBlocksAndSelf(byte[] blockHash, int count) {
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
        } finally {
            rwLock.readLock().unlock();
        }
    }




    //新增或者修改-本节点的设置信息 key - NODE_SETTING_KEY
    public void addOrUpdateNodeSetting(NodeSettings value) {
        rwLock.writeLock().lock();
        try {
            byte[] valueBytes = SerializeUtils.serialize(value);
            db.put(ColumnFamily.NODE.handle, KEY_NODE_SETTING, valueBytes);
        } catch (RocksDBException e) {
            log.error("节点状态失败: key={}", KEY_NODE_SETTING, e);
            throw new RuntimeException("节点状态失败", e);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    //获取本节点的设置信息
    public NodeSettings getNodeSetting() {
        rwLock.readLock().lock();
        try {
            byte[] valueBytes = db.get(ColumnFamily.NODE.handle, KEY_NODE_SETTING);
            if (valueBytes == null) {
                return null; // 不存在返回null，避免抛出异常
            }
            return (NodeSettings)SerializeUtils.deSerialize(valueBytes);
        } catch (RocksDBException e) {
            log.error("获取节点状态失败: key={}", KEY_NODE_SETTING, e);
            throw new RuntimeException("获取节点状态失败", e);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * 新增或者修改本节点的矿工信息
     */
    public void addOrUpdateMiner(Miner value) {
        rwLock.writeLock().lock();
        try {
            byte[] valueBytes = SerializeUtils.serialize(value);
            db.put(ColumnFamily.MINER.handle, KEY_MINER, valueBytes);
        } catch (RocksDBException e) {
            log.error("保存矿工信息失败: key={}", KEY_MINER, e);
            throw new RuntimeException("保存矿工信息失败", e);
        } finally {
            rwLock.writeLock().unlock();
        }
    }
    /**
     * 获取本节点的矿工信息
     */
    public Miner getMiner() {
        rwLock.readLock().lock();
        try {
            byte[] valueBytes = db.get(ColumnFamily.MINER.handle, KEY_MINER);
            if (valueBytes == null) {
                return null; // 不存在返回null，避免抛出异常
            }
            return (Miner)SerializeUtils.deSerialize(valueBytes);
        } catch (RocksDBException e) {
            log.error("获取矿工信息失败: key={}", KEY_MINER, e);
            throw new RuntimeException("获取矿工信息失败", e);
        } finally {
            rwLock.readLock().unlock();
        }
    }


    /**
     * UTXO集合
     */
    // UTXO分片大小（可根据实际场景调整）
    private static final int UTXO_SHARD_SIZE = 1000;
    // 分片计数器键前缀（地址→分片数量）
    private static final String SHARD_COUNT_PREFIX = "shardCount:";
    // 分片数据键前缀（地址+分片索引→UTXO键集合）
    private static final String SHARD_DATA_PREFIX = "shardData:";
    // 反向索引键前缀（UTXO键→所属分片标识）
    private static final String REVERSE_INDEX_PREFIX = "reverseIndex:";


    /**
     * 新增UTXO（优化分片存储）
     */
    public void addUtxo(UTXO value) {
        rwLock.writeLock().lock();
        try {
            String utxoKey = CryptoUtil.bytesToHex(value.getTxId()) + ":" + value.getVout();
            String address = value.getAddress();

            // 1. 存储单个UTXO（键：utxoKey，值：UTXO对象）
            byte[] utxoData = SerializeUtils.serialize(value);
            db.put(ColumnFamily.UTXO.handle, utxoKey.getBytes(), utxoData);

            // 2. 维护地址→UTXO分片映射
            // 2.1 获取当前地址的分片数量
            String shardCountKey = SHARD_COUNT_PREFIX + address;
            byte[] shardCountBytes = db.get(ColumnFamily.UTXO.handle, shardCountKey.getBytes());
            int shardCount = shardCountBytes == null ? 0 : (int) ByteUtils.fromBytesToInt(shardCountBytes);

            // 2.2 确定目标分片索引
            int targetShardIndex;
            Set<String> targetShard;
            if (shardCount == 0) {
                // 无分片时创建第一个分片
                targetShardIndex = 0;
                targetShard = new HashSet<>();
                shardCount = 1;
            } else {
                // 有分片时检查最后一个分片是否已满
                targetShardIndex = shardCount - 1;
                String lastShardKey = SHARD_DATA_PREFIX + address + ":" + targetShardIndex;
                byte[] lastShardBytes = db.get(ColumnFamily.UTXO.handle, lastShardKey.getBytes());
                targetShard = lastShardBytes == null ? new HashSet<>() : (Set<String>) SerializeUtils.deSerialize(lastShardBytes);

                // 最后一个分片已满，创建新分片
                if (targetShard.size() >= UTXO_SHARD_SIZE) {
                    targetShardIndex = shardCount;
                    targetShard = new HashSet<>();
                    shardCount++;
                }
            }

            // 2.3 写入目标分片
            targetShard.add(utxoKey);
            String targetShardKey = SHARD_DATA_PREFIX + address + ":" + targetShardIndex;
            db.put(ColumnFamily.UTXO.handle, targetShardKey.getBytes(), SerializeUtils.serialize(targetShard));

            // 2.4 更新分片数量
            db.put(ColumnFamily.UTXO.handle, shardCountKey.getBytes(), ByteUtils.intToBytes(shardCount)); // 修正：使用intToBytes

            // 3. 维护反向索引（UTXO键→分片标识）
            String reverseIndexKey = REVERSE_INDEX_PREFIX + utxoKey;
            String shardIdentifier = address + ":" + targetShardIndex; // 地址:分片索引
            db.put(ColumnFamily.UTXO.handle, reverseIndexKey.getBytes(), shardIdentifier.getBytes());

        } catch (RocksDBException e) {
            log.error("保存UTXO信息失败", e);
            throw new RuntimeException("保存UTXO信息失败", e);
        } finally {
            rwLock.writeLock().unlock();
        }
    }


    public void addUtxos(List<UTXO> batch) {
        rwLock.writeLock().lock();
        try (WriteBatch writeBatch = new WriteBatch();
             WriteOptions writeOptions = new WriteOptions()) {

            // 按地址分组UTXO，以便批量处理同一地址的UTXO分片
            Map<String, List<UTXO>> addressGroups = new HashMap<>();

            // 为每个UTXO构建基础数据并按地址分组
            for (UTXO utxo : batch) {
                String utxoKey = CryptoUtil.bytesToHex(utxo.getTxId()) + ":" + utxo.getVout();
                String address = utxo.getAddress();

                // 1. 序列化UTXO数据并添加到批量写
                byte[] utxoData = SerializeUtils.serialize(utxo);
                writeBatch.put(ColumnFamily.UTXO.handle, utxoKey.getBytes(), utxoData);

                // 按地址分组
                addressGroups.computeIfAbsent(address, k -> new ArrayList<>()).add(utxo);
            }

            // 2. 批量处理每个地址的UTXO分片
            for (Map.Entry<String, List<UTXO>> entry : addressGroups.entrySet()) {
                String address = entry.getKey();
                List<UTXO> addressUtxos = entry.getValue();

                // 获取当前地址的分片数量
                String shardCountKey = SHARD_COUNT_PREFIX + address;
                byte[] shardCountBytes = db.get(ColumnFamily.UTXO.handle, shardCountKey.getBytes());
                int shardCount = shardCountBytes == null ? 0 : (int) ByteUtils.fromBytesToInt(shardCountBytes);

                // 确定当前使用的分片
                int currentShardIndex = shardCount == 0 ? 0 : shardCount - 1;
                Set<String> currentShard = loadShard(address, currentShardIndex);

                // 逐个处理UTXO，必要时创建新分片
                for (UTXO utxo : addressUtxos) {
                    String utxoKey = CryptoUtil.bytesToHex(utxo.getTxId()) + ":" + utxo.getVout();

                    // 检查当前分片是否已满
                    if (currentShard.size() >= UTXO_SHARD_SIZE) {
                        // 保存当前分片
                        saveShard(writeBatch, address, currentShardIndex, currentShard);

                        // 创建新分片
                        currentShardIndex = shardCount++;
                        currentShard = new HashSet<>();
                    }

                    // 添加UTXO到分片
                    currentShard.add(utxoKey);

                    // 3. 维护反向索引（UTXO键→分片标识）
                    String reverseIndexKey = REVERSE_INDEX_PREFIX + utxoKey;
                    String shardIdentifier = address + ":" + currentShardIndex;
                    writeBatch.put(ColumnFamily.UTXO.handle, reverseIndexKey.getBytes(), shardIdentifier.getBytes());
                }

                // 保存最后处理的分片
                saveShard(writeBatch, address, currentShardIndex, currentShard);

                // 更新分片数量
                writeBatch.put(ColumnFamily.UTXO.handle, shardCountKey.getBytes(), ByteUtils.intToBytes(shardCount));
            }

            // 执行批量写入
            db.write(writeOptions, writeBatch);

        } catch (RocksDBException e) {
            log.error("批量保存UTXO信息失败", e);
            throw new RuntimeException("批量保存UTXO信息失败", e);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    // 辅助方法：从数据库加载分片数据
    private Set<String> loadShard(String address, int shardIndex) throws RocksDBException {
        String shardKey = SHARD_DATA_PREFIX + address + ":" + shardIndex;
        byte[] shardBytes = db.get(ColumnFamily.UTXO.handle, shardKey.getBytes());
        return shardBytes == null ? new HashSet<>() : (Set<String>) SerializeUtils.deSerialize(shardBytes);
    }

    // 辅助方法：将分片数据添加到批量写操作
    private void saveShard(WriteBatch writeBatch, String address, int shardIndex, Set<String> shard)
            throws RocksDBException {
        String shardKey = SHARD_DATA_PREFIX + address + ":" + shardIndex;
        writeBatch.put(ColumnFamily.UTXO.handle, shardKey.getBytes(), SerializeUtils.serialize(shard));
    }

    /**
     * 删除UTXO（优化分片存储）
     */
    public void deleteUtxo(UTXO value) {
        rwLock.writeLock().lock();
        try {
            String utxoKey = CryptoUtil.bytesToHex(value.getTxId()) + ":" + value.getVout();

            // 1. 通过反向索引找到所属分片
            String reverseIndexKey = REVERSE_INDEX_PREFIX + utxoKey;
            byte[] shardIdBytes = db.get(ColumnFamily.UTXO.handle, reverseIndexKey.getBytes());
            if (shardIdBytes == null) {
                log.warn("UTXO不存在，无需删除: {}", utxoKey);
                return;
            }
            String shardIdentifier = new String(shardIdBytes);
            String[] parts = shardIdentifier.split(":", 2);
            if (parts.length != 2) {
                log.error("无效的分片标识: {}", shardIdentifier);
                return;
            }
            String address = parts[0];
            int shardIndex = Integer.parseInt(parts[1]);

            // 2. 从分片移除UTXO键
            String shardKey = SHARD_DATA_PREFIX + address + ":" + shardIndex;
            byte[] shardBytes = db.get(ColumnFamily.UTXO.handle, shardKey.getBytes());
            if (shardBytes == null) {
                log.error("分片不存在: {}", shardKey);
                return;
            }
            Set<String> shard = (Set<String>) SerializeUtils.deSerialize(shardBytes);
            shard.remove(utxoKey);
            db.put(ColumnFamily.UTXO.handle, shardKey.getBytes(), SerializeUtils.serialize(shard));

            // 3. 删除反向索引和UTXO数据
            db.delete(ColumnFamily.UTXO.handle, reverseIndexKey.getBytes());
            db.delete(ColumnFamily.UTXO.handle, utxoKey.getBytes());

        } catch (RocksDBException e) {
            log.error("删除UTXO失败", e);
            throw new RuntimeException("删除UTXO失败", e);
        } finally {
            rwLock.writeLock().unlock();
        }
    }


    /**
     * 获取地址对应的所有UTXO
     */
    /**
     * 获取地址对应的所有UTXO（优化版：并行分片处理+批量读取）
     */
    public List<UTXO> getUtxosByAddress(String address) {
        rwLock.readLock().lock();
        try {
            // 1. 准备读取选项（可根据场景调整缓存策略）
            ReadOptions readOptions = new ReadOptions()
                    .setFillCache(true); // 频繁访问的地址建议开启缓存，临时查询可设为false

            // 2. 获取地址的分片数量
            String shardCountKey = SHARD_COUNT_PREFIX + address;
            byte[] shardCountBytes = db.get(ColumnFamily.UTXO.handle, shardCountKey.getBytes());

            int shardCount = shardCountBytes == null ? 0 : ByteUtils.fromBytesToInt(shardCountBytes);
            if (shardCount == 0) {
                return Collections.emptyList();
            }

            // 3. 并行读取所有分片，收集UTXO键（利用多核加速分片处理）
            Set<String> allUtxoKeys = Collections.synchronizedSet(new HashSet<>());
            IntStream.range(0, shardCount).parallel().forEach(shardIndex -> {
                try {
                    String shardKey = SHARD_DATA_PREFIX + address + ":" + shardIndex;
                    byte[] shardBytes = db.get(ColumnFamily.UTXO.handle, shardKey.getBytes());

                    if (shardBytes != null) {
                        Set<String> shard = (Set<String>) SerializeUtils.deSerialize(shardBytes);
                        allUtxoKeys.addAll(shard);
                    }
                } catch (RocksDBException e) {
                    throw new RuntimeException("读取分片失败", e);
                }
            });

            if (allUtxoKeys.isEmpty()) {
                return Collections.emptyList();
            }

            // 4. 批量读取UTXO（核心优化：用multiGet替代循环get，减少IO次数）
            List<byte[]> keys = allUtxoKeys.stream()
                    .map(String::getBytes)
                    .collect(Collectors.toList());
            log.info("批量读取UTXO数量: " + keys.size());



            // 方法2：使用 multiGet（需提供键的引用列表，避免自动装箱）
            List<ColumnFamilyHandle> columnFamilies = Collections.nCopies(keys.size(), ColumnFamily.UTXO.handle);
            // 批量获取所有UTXO的字节数据（一次IO操作）
            long l = System.currentTimeMillis();
            List<byte[]> utxoBytesList = db.multiGetAsList(readOptions,columnFamilies,keys);
            log.info("批量读取耗时: " + (System.currentTimeMillis() - l));

            // 5. 批量反序列化为UTXO对象
            List<UTXO> result = new ArrayList<>(allUtxoKeys.size());
            Iterator<String> keyIter = allUtxoKeys.iterator();

            long l1 = System.currentTimeMillis();
            for (byte[] utxoBytes : utxoBytesList) {
                if (utxoBytes != null) {
                    result.add((UTXO) SerializeUtils.deSerialize(utxoBytes));
                } else {
                    // 处理数据不一致（理论上不应出现，仅作容错）
                    String missingKey = keyIter.next();
                    log.warn("UTXO数据不一致: 地址={}, 缺失UTXO键={}", address, missingKey);
                }
            }
            log.info("批量反序列化耗时: " + (System.currentTimeMillis() - l1));

            return result;
        } catch (RocksDBException e) {
            log.error("查询地址UTXO失败: address={}", address, e);
            throw new RuntimeException("查询地址UTXO失败", e);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * 获取单个UTXO（保持不变）
     */
    public UTXO getUtxo(byte[] txId, int vout) {
        rwLock.readLock().lock();
        try {
            String utxoKey = CryptoUtil.bytesToHex(txId) + ":" + vout;
            byte[] valueBytes = db.get(ColumnFamily.UTXO.handle, utxoKey.getBytes());
            return valueBytes == null ? null : (UTXO) SerializeUtils.deSerialize(valueBytes);
        } catch (RocksDBException e) {
            log.error("获取UTXO失败", e);
            throw new RuntimeException("获取UTXO失败", e);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public UTXO getUtxoByKey(String key) {
        rwLock.readLock().lock();
        try {
            byte[] valueBytes = db.get(ColumnFamily.UTXO.handle, key.getBytes());
            return valueBytes == null ? null : (UTXO) SerializeUtils.deSerialize(valueBytes);
        } catch (RocksDBException e) {
            log.error("获取UTXO失败", e);
            throw new RuntimeException("获取UTXO失败", e);
        } finally {
            rwLock.readLock().unlock();
        }
    }


















    //备选测试
/*    *//**
     * 新增UTXO（优化分片存储）
     *//*
    public void addUtxo(UTXO value) {
        rwLock.writeLock().lock();
        try {
            String utxoKey = CryptoUtil.bytesToHex(value.getTxId()) + ":" + value.getVout();
            String address = value.getAddress();
            // 1. 存储单个UTXO（键：utxoKey，值：UTXO对象）
            byte[] utxoData = SerializeUtils.serialize(value);
            db.put(ColumnFamily.UTXO.handle, utxoKey.getBytes(), utxoData);
            //地址到UTXOID
            byte[] utxoSet = db.get(ColumnFamily.ADDRESS_UTXO.handle, value.getAddress().getBytes());
            HashSet<String> utxoHashSet;
            if (utxoSet == null) {
                utxoHashSet = new HashSet<>();
            }else {
                utxoHashSet = (HashSet<String>) SerializeUtils.deSerialize(utxoSet);
            }
            utxoHashSet.add(utxoKey);
            db.put(ColumnFamily.ADDRESS_UTXO.handle, value.getAddress().getBytes(), SerializeUtils.serialize(utxoHashSet));
        } catch (RocksDBException e) {
            log.error("保存UTXO信息失败", e);
            throw new RuntimeException("保存UTXO信息失败", e);
        } finally {
            rwLock.writeLock().unlock();
        }
    }


    public void addUtxos(List<UTXO> batch) {
        rwLock.writeLock().lock();
        try (WriteBatch writeBatch = new WriteBatch();
             WriteOptions writeOptions = new WriteOptions()) {

            // 用于收集每个地址对应的UTXO键集合
            Map<String, Set<String>> addressToUtxoKeys = new HashMap<>();

            // 遍历批量UTXO，准备写入操作
            for (UTXO value : batch) {
                String utxoKey = CryptoUtil.bytesToHex(value.getTxId()) + ":" + value.getVout();
                String address = value.getAddress();
                // 1. 序列化UTXO并添加到批量写入
                byte[] utxoData = SerializeUtils.serialize(value);
                writeBatch.put(ColumnFamily.UTXO.handle, utxoKey.getBytes(), utxoData);
                // 2. 收集地址到UTXO键的映射关系
                addressToUtxoKeys.computeIfAbsent(address, k -> new HashSet<>()).add(utxoKey);
            }

            // 3. 处理地址到UTXO键集合的批量更新
            for (Map.Entry<String, Set<String>> entry : addressToUtxoKeys.entrySet()) {
                String address = entry.getKey();
                Set<String> utxoKeys = entry.getValue();
                // 获取现有UTXO集合
                byte[] existingUtxoSetBytes = db.get(ColumnFamily.ADDRESS_UTXO.handle, address.getBytes());
                Set<String> existingUtxoSet = new HashSet<>();
                if (existingUtxoSetBytes != null) {
                    existingUtxoSet = (Set<String>) SerializeUtils.deSerialize(existingUtxoSetBytes);
                }
                // 添加新的UTXO键
                existingUtxoSet.addAll(utxoKeys);
                // 添加到批量写入
                writeBatch.put(ColumnFamily.ADDRESS_UTXO.handle, address.getBytes(),
                        SerializeUtils.serialize(existingUtxoSet));
            }
            // 执行批量写入
            db.write(writeOptions, writeBatch);
        } catch (RocksDBException e) {
            log.error("批量保存UTXO信息失败", e);
            throw new RuntimeException("批量保存UTXO信息失败", e);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    *//**
     * 删除单个UTXO
     *//*
    public void deleteUtxo(UTXO value) {
        rwLock.writeLock().lock();
        try {
            String utxoKey = CryptoUtil.bytesToHex(value.getTxId()) + ":" + value.getVout();
            String address = value.getAddress();

            // 1. 从UTXO表中删除
            db.delete(ColumnFamily.UTXO.handle, utxoKey.getBytes());

            // 2. 从地址-UTXO映射中删除
            byte[] utxoSetBytes = db.get(ColumnFamily.ADDRESS_UTXO.handle, address.getBytes());
            if (utxoSetBytes != null) {
                Set<String> utxoSet = (Set<String>) SerializeUtils.deSerialize(utxoSetBytes);
                if (utxoSet.remove(utxoKey)) {
                    if (utxoSet.isEmpty()) {
                        // 如果集合为空，删除整个键
                        db.delete(ColumnFamily.ADDRESS_UTXO.handle, address.getBytes());
                    } else {
                        // 否则更新集合
                        db.put(ColumnFamily.ADDRESS_UTXO.handle, address.getBytes(),
                                SerializeUtils.serialize(utxoSet));
                    }
                }
            }
        } catch (RocksDBException e) {
            log.error("删除UTXO失败", e);
            throw new RuntimeException("删除UTXO失败", e);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    *//**
     * 批量删除UTXO
     *//*
    public void deleteUtxoBatch(List<UTXO> batch) {
        rwLock.writeLock().lock();
        try (WriteBatch writeBatch = new WriteBatch();
             WriteOptions writeOptions = new WriteOptions()) {
            // 用于收集每个地址对应的UTXO键集合
            Map<String, Set<String>> addressToUtxoKeys = new HashMap<>();
            // 遍历批量UTXO，准备删除操作
            for (UTXO value : batch) {
                String utxoKey = CryptoUtil.bytesToHex(value.getTxId()) + ":" + value.getVout();
                String address = value.getAddress();
                // 1. 添加到UTXO表的删除操作
                writeBatch.delete(ColumnFamily.UTXO.handle, utxoKey.getBytes());
                // 2. 收集地址到UTXO键的映射关系
                addressToUtxoKeys.computeIfAbsent(address, k -> new HashSet<>()).add(utxoKey);
            }

            // 3. 处理地址到UTXO键集合的批量更新
            for (Map.Entry<String, Set<String>> entry : addressToUtxoKeys.entrySet()) {
                String address = entry.getKey();
                Set<String> utxoKeysToRemove = entry.getValue();
                // 获取现有UTXO集合
                byte[] existingUtxoSetBytes = db.get(ColumnFamily.ADDRESS_UTXO.handle, address.getBytes());
                if (existingUtxoSetBytes != null) {
                    Set<String> existingUtxoSet = (Set<String>) SerializeUtils.deSerialize(existingUtxoSetBytes);
                    // 移除要删除的UTXO键
                    boolean modified = existingUtxoSet.removeAll(utxoKeysToRemove);
                    if (modified) {
                        if (existingUtxoSet.isEmpty()) {
                            // 如果集合为空，从批量操作中删除整个键
                            writeBatch.delete(ColumnFamily.ADDRESS_UTXO.handle, address.getBytes());
                        } else {
                            // 否则更新集合
                            writeBatch.put(ColumnFamily.ADDRESS_UTXO.handle, address.getBytes(),
                                    SerializeUtils.serialize(existingUtxoSet));
                        }
                    }
                }
            }

            // 执行批量写入
            db.write(writeOptions, writeBatch);
        } catch (RocksDBException e) {
            log.error("批量删除UTXO失败", e);
            throw new RuntimeException("批量删除UTXO失败", e);
        } finally {
            rwLock.writeLock().unlock();
        }
    }


    *//**
     * 获取地址对应的所有UTXO
     *//*
    public List<UTXO> getUtxosByAddress(String address) {
        rwLock.readLock().lock();
        try {
            // 1. 获取地址对应的所有UTXO键
            byte[] utxoSetBytes = db.get(ColumnFamily.ADDRESS_UTXO.handle, address.getBytes());
            if (utxoSetBytes == null) {
                return Collections.emptyList();
            }

            Set<String> utxoKeys = (Set<String>) SerializeUtils.deSerialize(utxoSetBytes);
            if (utxoKeys.isEmpty()) {
                return Collections.emptyList();
            }

            // 2. 批量获取所有UTXO
            List<UTXO> result = new ArrayList<>(utxoKeys.size());
            try (ReadOptions readOptions = new ReadOptions()) {
                for (String utxoKey : utxoKeys) {
                    byte[] utxoData = db.get(ColumnFamily.UTXO.handle, utxoKey.getBytes());
                    if (utxoData != null) {
                        result.add((UTXO) SerializeUtils.deSerialize(utxoData));
                    } else {
                        // 处理不一致情况：地址索引中存在，但UTXO不存在
                        log.warn("Inconsistency detected: UTXO {} not found for address {}", utxoKey, address);
                    }
                }
            }
            return result;
        } catch (RocksDBException | ClassCastException | SerializationException e) {
            log.error("获取地址UTXO失败", e);
            throw new RuntimeException("获取地址UTXO失败", e);
        } finally {
            rwLock.readLock().unlock();
        }
    }
    *//**
     * 获取单个UTXO
     *//*
    public UTXO getUtxo(byte[] txId, int vout) {
        rwLock.readLock().lock();
        try {
            String utxoKey = CryptoUtil.bytesToHex(txId) + ":" + vout;
            byte[] valueBytes = db.get(ColumnFamily.UTXO.handle, utxoKey.getBytes());
            return valueBytes == null ? null : (UTXO) SerializeUtils.deSerialize(valueBytes);
        } catch (RocksDBException e) {
            log.error("获取UTXO失败", e);
            throw new RuntimeException("获取UTXO失败", e);
        } finally {
            rwLock.readLock().unlock();
        }
    }*/

}
