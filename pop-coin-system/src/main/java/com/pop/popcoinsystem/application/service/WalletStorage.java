package com.pop.popcoinsystem.application.service;

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
import java.io.Serializable;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.pop.popcoinsystem.util.CryptoUtil.POP_NET_VERSION;


@Slf4j
public class WalletStorage {
    // 数据库存储路径
    private static final String DB_PATH = "rocksDb/popCoin.db/wallet/blockChain" + POP_NET_VERSION + ".db";
    private static final String LOG_PATH = DB_PATH + "rocksdb_logs/"; // 单独目录存放 RocksDB 日志

    private static final String KEY_BTC_MINER_WALLET = "btcminer";//矿工钱包
    private static final String KEY_BTC_WALLET_A = "walleta";//钱包A
    private static final String KEY_BTC_WALLET_B = "walletb";

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



    public void saveWalletUTXOs(String walletName, CopyOnWriteArraySet<String> walletUTXOs) {
        log.info("保存钱包 UTXO 集合: {}", walletName);
        try {
            this.rwLock.writeLock().lock();
            this.db.put(ColumnFamily.BTC_Miner_UTXO.handle, walletName.getBytes(), SerializeUtils.serialize(walletUTXOs));
        } catch (RocksDBException e) {
            log.error("保存钱包 UTXO 失败", e);
            throw new RuntimeException("保存钱包 UTXO 失败", e);
        } finally {
            this.rwLock.writeLock().unlock();
        }
    }

    //获取钱包的UTXO集合
    public CopyOnWriteArraySet<String> getWalletUTXOs(String walletName) {
        try {
            this.rwLock.readLock().lock();
            byte[] bytes = this.db.get(ColumnFamily.BTC_Miner_UTXO.handle, walletName.getBytes());
            if (bytes == null) {
                return new CopyOnWriteArraySet<String>();
            }
            return (CopyOnWriteArraySet<String>) SerializeUtils.deSerialize(bytes);
        } catch (RocksDBException e) {
            log.error("获取钱包 UTXO 集合失败", e);
            throw new RuntimeException("获取钱包 UTXO 集合失败", e);
        }
    }





    //.................................................................................................................
    // 使用枚举管理列族
    private enum ColumnFamily {
        WALLET("CF_WALLET", "wallet",new ColumnFamilyOptions()),
        BTC_Miner_UTXO("CF_BTC_Miner_UTXO", "btcMinerUTXO",new ColumnFamilyOptions()),//矿工钱包

        WALLET_A("CF_BTC_Miner_UTXO", "btcMinerUTXO",new ColumnFamilyOptions()),//矿工钱包



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
        String logDir = LOG_PATH; // 单独目录存放 RocksDB 日志
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
