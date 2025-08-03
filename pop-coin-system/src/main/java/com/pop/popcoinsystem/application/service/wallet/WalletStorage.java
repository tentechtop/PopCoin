package com.pop.popcoinsystem.application.service.wallet;

import com.pop.popcoinsystem.data.transaction.UTXO;
import com.pop.popcoinsystem.data.vo.result.ListPageResult;
import com.pop.popcoinsystem.util.SerializeUtils;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.rocksdb.*;

import java.io.File;
import java.util.*;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static com.pop.popcoinsystem.constant.BlockChainConstants.STORAGE_PATH;
import static com.pop.popcoinsystem.util.YamlReaderUtils.getNestedValue;
import static com.pop.popcoinsystem.util.YamlReaderUtils.loadYaml;


@Slf4j
public class WalletStorage {
    private static String storagePath = STORAGE_PATH;
    static {
        Map<String, Object> config = loadYaml("application.yml");
        if (config != null) {
            storagePath = (String) getNestedValue(config, "system.storagePath");
            log.info("读取存储路径: " + storagePath);
        }
    }


    // 数据库存储路径
    private static String DB_PATH = storagePath+"/wallet"+ ".db/";

    // UTXO键前缀分隔符
    private static final String UTXO_KEY_SEPARATOR = "_UTXO_";
    // ------------------------------ 数据操作 ------------------------------
    /**
     * 添加钱包
     */
    public void addWallet(Wallet wallet) {
        try {
            this.rwLock.writeLock().lock();
            this.db.put(ColumnFamily.WALLET.handle, wallet.getName().getBytes(), SerializeUtils.serialize(wallet));
        } catch (RocksDBException e) {
            log.error("添加钱包失败", e);
            throw new RuntimeException("添加钱包失败", e);
        } finally {
            this.rwLock.writeLock().unlock();
        }
    }


    /**
     * 获取钱包
     */
    public Wallet getWallet(String name) {
        try {
            this.rwLock.readLock().lock();
            byte[] bytes = this.db.get(ColumnFamily.WALLET.handle, name.getBytes());
            if (bytes == null) {
                return null;
            }
            return (Wallet) SerializeUtils.deSerialize(bytes);
        } catch (RocksDBException e) {
            log.error("获取钱包失败", e);
            throw new RuntimeException("获取钱包失败", e);
        } finally {
            this.rwLock.readLock().unlock();
        }
    }


    /**
     * 保存钱包的单个UTXO
     * @param walletName
     * @param utxoKey
     */
    public void saveWalletUTXO(String walletName, String utxoKey) {
        log.info("保存钱包 {} 的 UTXO: {}", walletName, utxoKey);
        try {
            this.rwLock.writeLock().lock();
            String key = buildUtxoKey(walletName, utxoKey);
            this.db.put(ColumnFamily.WALLET_UTXO.handle, key.getBytes(), new byte[0]); // 值可以为空，我们只关心键
        } catch (RocksDBException e) {
            log.error("保存钱包 UTXO 失败", e);
            throw new RuntimeException("保存钱包 UTXO 失败", e);
        } finally {
            this.rwLock.writeLock().unlock();
        }
    }
    /**
     * 批量保存钱包UTXO
     */
    public void saveWalletUTXOBatch(String walletName, List<String> utxoKeyList) {
        log.info("批量保存钱包 {} 的 UTXO，数量: {}", walletName, utxoKeyList.size());
        rwLock.writeLock().lock();
        WriteBatch writeBatch = null;
        try {
            writeBatch = new WriteBatch();
            WriteOptions writeOptions = new WriteOptions();
            for (String utxoKey : utxoKeyList) {
                String key = buildUtxoKey(walletName, utxoKey);
                // 添加到批量写（值为空字节数组，仅保存键）
                writeBatch.put(ColumnFamily.WALLET_UTXO.handle, key.getBytes(), new byte[0]);
            }
            // 执行批量写入
            db.write(writeOptions, writeBatch);
        } catch (RocksDBException e) {
            log.error("批量保存钱包UTXO失败", e);
            throw new RuntimeException("批量保存钱包UTXO失败", e);
        } finally {
            // 确保资源释放
            if (writeBatch != null) {
                writeBatch.close();
            }
            rwLock.writeLock().unlock();
        }
    }
    public void saveWalletUTXOBatch(String walletName, CopyOnWriteArraySet<String> utxoKeyList) {
        log.info("批量保存钱包 {} 的 UTXO，数量: {}", walletName, utxoKeyList.size());
        rwLock.writeLock().lock();
        WriteBatch writeBatch = null;
        try {
            writeBatch = new WriteBatch();
            WriteOptions writeOptions = new WriteOptions();
            for (String utxoKey : utxoKeyList) {
                String key = buildUtxoKey(walletName, utxoKey);
                // 添加到批量写（值为空字节数组，仅保存键）
                writeBatch.put(ColumnFamily.WALLET_UTXO.handle, key.getBytes(), new byte[0]);
            }
            // 执行批量写入
            db.write(writeOptions, writeBatch);
        } catch (RocksDBException e) {
            log.error("批量保存钱包UTXO失败", e);
            throw new RuntimeException("批量保存钱包UTXO失败", e);
        } finally {
            // 确保资源释放
            if (writeBatch != null) {
                writeBatch.close();
            }
            rwLock.writeLock().unlock();
        }
    }
    public void saveWalletUTXOBatch(String walletName, Set<String> utxoKeyList) {
        log.info("批量保存钱包 {} 的 UTXO，数量: {}", walletName, utxoKeyList.size());
        rwLock.writeLock().lock();
        WriteBatch writeBatch = null;
        try {
            writeBatch = new WriteBatch();
            WriteOptions writeOptions = new WriteOptions();
            for (String utxoKey : utxoKeyList) {
                String key = buildUtxoKey(walletName, utxoKey);
                // 添加到批量写（值为空字节数组，仅保存键）
                writeBatch.put(ColumnFamily.WALLET_UTXO.handle, key.getBytes(), new byte[0]);
            }
            // 执行批量写入
            db.write(writeOptions, writeBatch);
        } catch (RocksDBException e) {
            log.error("批量保存钱包UTXO失败", e);
            throw new RuntimeException("批量保存钱包UTXO失败", e);
        } finally {
            // 确保资源释放
            if (writeBatch != null) {
                writeBatch.close();
            }
            rwLock.writeLock().unlock();
        }
    }





    /**
     * 删除单个UTXO
     */
    public void removeWalletUTXO(String walletName, String utxoKey) {
        log.info("删除钱包 {} 的 UTXO: {}", walletName, utxoKey);
        try {
            this.rwLock.writeLock().lock();
            String key = buildUtxoKey(walletName, utxoKey);
            this.db.delete(ColumnFamily.WALLET_UTXO.handle, key.getBytes());
        } catch (RocksDBException e) {
            log.error("删除钱包 UTXO 失败", e);
            throw new RuntimeException("删除钱包 UTXO 失败", e);
        } finally {
            this.rwLock.writeLock().unlock();
        }
    }
    /**
     * 批量删除钱包UTXO
     */
    public void removeWalletUTXOBatch(String walletName, List<String> utxoKeyList) {
        log.info("批量删除钱包 {} 的 UTXO，数量: {}", walletName, utxoKeyList.size());
        rwLock.writeLock().lock();
        WriteBatch writeBatch = null;
        try {
            writeBatch = new WriteBatch();
            WriteOptions writeOptions = new WriteOptions();
            for (String utxoKey : utxoKeyList) {
                String key = buildUtxoKey(walletName, utxoKey);
                // 添加到批量删除
                writeBatch.delete(ColumnFamily.WALLET_UTXO.handle, key.getBytes());
            }
            // 执行批量删除
            db.write(writeOptions, writeBatch);
        } catch (RocksDBException e) {
            log.error("批量删除钱包UTXO失败", e);
            throw new RuntimeException("批量删除钱包UTXO失败", e);
        } finally {
            // 确保资源释放
            if (writeBatch != null) {
                writeBatch.close();
            }
            rwLock.writeLock().unlock();
        }
    }

    public void removeWalletUTXOBatch(String walletName, CopyOnWriteArraySet<String> utxoKeyList) {
        log.info("批量删除钱包 {} 的 UTXO，数量: {}", walletName, utxoKeyList.size());
        rwLock.writeLock().lock();
        WriteBatch writeBatch = null;
        try {
            writeBatch = new WriteBatch();
            WriteOptions writeOptions = new WriteOptions();
            for (String utxoKey : utxoKeyList) {
                String key = buildUtxoKey(walletName, utxoKey);
                // 添加到批量删除
                writeBatch.delete(ColumnFamily.WALLET_UTXO.handle, key.getBytes());
            }
            // 执行批量删除
            db.write(writeOptions, writeBatch);
        } catch (RocksDBException e) {
            log.error("批量删除钱包UTXO失败", e);
            throw new RuntimeException("批量删除钱包UTXO失败", e);
        } finally {
            // 确保资源释放
            if (writeBatch != null) {
                writeBatch.close();
            }
            rwLock.writeLock().unlock();
        }
    }
    public void removeWalletUTXOBatch(String walletName, HashSet<String> utxoKeyList) {
        log.info("批量删除钱包 {} 的 UTXO，数量: {}", walletName, utxoKeyList.size());
        rwLock.writeLock().lock();
        WriteBatch writeBatch = null;
        try {
            writeBatch = new WriteBatch();
            WriteOptions writeOptions = new WriteOptions();
            for (String utxoKey : utxoKeyList) {
                String key = buildUtxoKey(walletName, utxoKey);
                // 添加到批量删除
                writeBatch.delete(ColumnFamily.WALLET_UTXO.handle, key.getBytes());
            }
            // 执行批量删除
            db.write(writeOptions, writeBatch);
        } catch (RocksDBException e) {
            log.error("批量删除钱包UTXO失败", e);
            throw new RuntimeException("批量删除钱包UTXO失败", e);
        } finally {
            // 确保资源释放
            if (writeBatch != null) {
                writeBatch.close();
            }
            rwLock.writeLock().unlock();
        }
    }

    /**
     * 获取钱包的UTXO集合
     */
    public Set<String> getWalletUTXOs(String walletName) {
        log.info("获取钱包 {} 的 UTXO 集合", walletName);
        Set<String> result = new HashSet<>();
        RocksIterator iterator = null;
        try {
            this.rwLock.readLock().lock();
            // 创建前缀迭代器
            byte[] prefix = buildUtxoKeyPrefix(walletName);
            ReadOptions readOptions = new ReadOptions();
            readOptions.setPrefixSameAsStart(true);
            readOptions.setTotalOrderSeek(false);
            iterator = this.db.newIterator(ColumnFamily.WALLET_UTXO.handle, readOptions);
            iterator.seek(prefix);
            while (iterator.isValid() && startsWith(iterator.key(), prefix)) {
                String key = new String(iterator.key());
                String utxoId = extractUtxoId(key);
                result.add(utxoId);
                iterator.next();
            }
        } catch (Exception e) {
            log.error("获取钱包 UTXO 集合失败", e);
            throw new RuntimeException("获取钱包 UTXO 集合失败", e);
        } finally {
            if (iterator != null) {
                iterator.close();
            }
            this.rwLock.readLock().unlock();
        }
        return result;
    }


    /**
     * 分页获取钱包的UTXO对象集合
     * @param walletName 钱包名称
     * @param pageSize 每页大小，范围 1-5000
     * @param lastUtxoKey 上一页的最后一个UTXO键，首次查询传入 null
     * @return 包含UTXO集合和分页信息的结果对象
     */
    public ListPageResult<UTXO> getWalletUTXOsPaginated(String walletName, int pageSize, String lastUtxoKey) {
        // 校验 pageSize 范围
        if (pageSize <= 0 || pageSize > 5000) {
            throw new IllegalArgumentException("每页数量必须在 1-5000 之间");
        }
        rwLock.readLock().lock();
        RocksIterator iterator = null;
        ReadOptions readOptions = null;
        try {
            // 创建前缀迭代器配置
            byte[] prefix = buildUtxoKeyPrefix(walletName);
            readOptions = new ReadOptions();
            readOptions.setPrefixSameAsStart(true);
            readOptions.setTotalOrderSeek(false);
            // 获取 UTXO 列族的迭代器
            iterator = db.newIterator(ColumnFamily.WALLET_UTXO.getHandle(), readOptions);
            List<UTXO> utxoList = new ArrayList<>(pageSize);
            String currentLastKey = null;
            int count = 0;
            // 定位迭代器起始位置
            if (lastUtxoKey != null && !lastUtxoKey.isEmpty()) {
                byte[] lastKeyBytes = buildUtxoKey(walletName, lastUtxoKey).getBytes();
                iterator.seek(lastKeyBytes);
                // 如果定位到的键正好是 lastUtxoKey 对应的键，则跳过它
                if (iterator.isValid() && lastUtxoKey.equals(extractUtxoId(new String(iterator.key())))) {
                    iterator.next();
                }
            } else {
                iterator.seek(prefix); // 第一页，从前缀开始
            }
            // 遍历获取 pageSize 个 UTXO
            while (iterator.isValid() && startsWith(iterator.key(), prefix) && count < pageSize) {
                byte[] keyBytes = iterator.key();
                byte[] valueBytes = iterator.value();

                // 反序列化 UTXO
                UTXO utxo = (UTXO) SerializeUtils.deSerialize(valueBytes);
                utxoList.add(utxo);
                // 记录当前键（作为下一页的 lastUtxoKey）
                currentLastKey = extractUtxoId(new String(keyBytes));
                iterator.next();
                count++;
            }
            // 判断是否为最后一页：如果实际获取数量小于请求数量，说明没有更多数据
            boolean isLastPage = count < pageSize;
            return new ListPageResult<>(utxoList, currentLastKey, isLastPage);
        } catch (Exception e) {
            log.error("分页获取钱包 UTXO 失败", e);
            throw new RuntimeException("分页获取钱包 UTXO 失败", e);
        } finally {
            if (iterator != null) {
                iterator.close(); // 关闭迭代器
            }
            if (readOptions != null) {
                readOptions.close(); // 关闭 ReadOptions
            }
            rwLock.readLock().unlock();
        }
    }




    // 构建UTXO键
    private String buildUtxoKey(String walletName, String utxoId) {
        return walletName + UTXO_KEY_SEPARATOR + utxoId;
    }
    // 构建UTXO键前缀
    private byte[] buildUtxoKeyPrefix(String walletName) {
        return (walletName + UTXO_KEY_SEPARATOR).getBytes();
    }
    // 从完整键中提取UTXO ID
    private String extractUtxoId(String key) {
        int index = key.indexOf(UTXO_KEY_SEPARATOR);
        if (index != -1) {
            return key.substring(index + UTXO_KEY_SEPARATOR.length());
        }
        return key; // 理论上不会执行到这里
    }
    // 检查字节数组是否以指定前缀开头
    private boolean startsWith(byte[] array, byte[] prefix) {
        if (array.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (array[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }







    //.................................................................................................................
    // 使用枚举管理列族
    private enum ColumnFamily {
        WALLET("CF_WALLET", "wallet",new ColumnFamilyOptions()),
        WALLET_UTXO("CF_BTC_Miner_UTXO", "btcMinerUTXO",new ColumnFamilyOptions()),//钱包对应的UTXO





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
    private final ReadWriteLock rwLock = new ReentrantReadWriteLock();

    private static class InstanceHolder {
        private static final WalletStorage INSTANCE = new WalletStorage();
    }

    public static WalletStorage getInstance() {
        return WalletStorage.InstanceHolder.INSTANCE;
    }

    private WalletStorage() {
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

}
