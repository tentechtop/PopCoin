package com.pop.popcoinsystem.application.service;

import com.pop.popcoinsystem.util.SerializeUtils;
import lombok.extern.slf4j.Slf4j;
import org.rocksdb.*;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static com.pop.popcoinsystem.util.CryptoUtil.POP_NET_VERSION;

@Slf4j
public class TemplateStorage {
    // 数据库存储路径
    private static final String DB_PATH = "rocksDb/popCoin.db/blockChain"+POP_NET_VERSION+".db/template";

    private static final String CF_WALLET = "wallet"; // 存储链信息

    private final RocksDB db;

    private final ReadWriteLock rwLock = new ReentrantReadWriteLock();


    private ColumnFamilyHandle cfWallet;

    private static class InstanceHolder {
        private static final TemplateStorage INSTANCE = new TemplateStorage();
    }

    public static TemplateStorage getInstance() {
        return TemplateStorage.InstanceHolder.INSTANCE;
    }

    private TemplateStorage() {
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
        for (String cfName : new String[]{CF_WALLET}) {
            if (!existingCfNames.contains(cfName)) {
                cfDescriptors.add(new ColumnFamilyDescriptor(cfName.getBytes(), new ColumnFamilyOptions()));
            } else {
                cfDescriptors.add(new ColumnFamilyDescriptor(cfName.getBytes(), new ColumnFamilyOptions()));
            }
        }

        // 配置事务数据库

        // 4. 打开数据库并获取列族句柄
        DBOptions options = new DBOptions()
                .setCreateIfMissing(true)// 保持原有的"如果不存在则创建"行为
                .setCreateMissingColumnFamilies(true)
                .setInfoLogLevel(InfoLogLevel.ERROR_LEVEL)//禁用INFO日志（LOG文件）
                .setMaxLogFileSize(1024 * 1024)//限制日志文件大小和保留数量（避免无限增长）
                .setKeepLogFileNum(2);// 最多保留 2 个日志文件

        //配置日志
        String logDir = DB_PATH+"rocksdb_logs/"; // 单独目录存放 RocksDB 日志
        new File(logDir).mkdirs(); // 确保目录存在
        options.setDbLogDir(logDir);


        RocksDB db = RocksDB.open(options, DB_PATH, cfDescriptors, cfHandles);

        // 5. 绑定列族句柄（索引对应cfDescriptors顺序）
        this.cfWallet = cfHandles.get(1);         // 对应CF_BLOCKS
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
                if (cfWallet != null) cfWallet.close();
                // 关闭数据库
                if (db != null) db.close();
            } catch (Exception e) {
                log.error("数据库关闭失败", e);
            }
        }));
    }


    /**
     * 添加钱包
     */
    public void addWallet(Wallet wallet) {
        try {
            this.rwLock.writeLock().lock();
            this.db.put(this.cfWallet, wallet.getName().getBytes(), SerializeUtils.serialize(wallet));
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
            byte[] bytes = this.db.get(this.cfWallet, name.getBytes());
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



    //找到全节点所有的UTXO  构建钱包地址和UTXO的映射



















}
