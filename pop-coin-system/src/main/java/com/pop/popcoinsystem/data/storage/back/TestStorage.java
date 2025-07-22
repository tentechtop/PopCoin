package com.pop.popcoinsystem.data.storage.back;

import com.pop.popcoinsystem.data.transaction.UTXO;
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

import static com.pop.popcoinsystem.util.CryptoUtil.POP_NET_VERSION;


@Slf4j
public class TestStorage {

    // 使用枚举管理列族
    private enum ColumnFamily {
        UTXO("CF_UTXO", "utxo",new ColumnFamilyOptions()
                .setTableFormatConfig(new BlockBasedTableConfig()
                        .setBlockCacheSize(64 * 1024 * 1024) // 64MB 块缓存
                        .setCacheIndexAndFilterBlocks(true)) ),
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
        private static final TestStorage INSTANCE = new TestStorage();
    }

    public static TestStorage getInstance() {
        return TestStorage.InstanceHolder.INSTANCE;
    }

    private TestStorage() {
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


    //UTXO.............................................................................................................
    public void addUtxo(UTXO utxo) {
        try {
            String utxoKey = CryptoUtil.bytesToHex(utxo.getTxId()) + ":" + utxo.getVout();
            byte[] valueBytes = SerializeUtils.serialize(utxo);
            db.put(ColumnFamily.UTXO.handle, utxoKey.getBytes(), valueBytes);
        } catch (RocksDBException e) {
            throw new RuntimeException("失败", e);
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