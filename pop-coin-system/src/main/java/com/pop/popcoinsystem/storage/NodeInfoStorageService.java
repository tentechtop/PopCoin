package com.pop.popcoinsystem.storage;

import com.pop.popcoinsystem.network.common.ExternalNodeInfo;
import com.pop.popcoinsystem.network.common.NodeSettings;
import com.pop.popcoinsystem.util.CryptoUtil;
import com.pop.popcoinsystem.util.SerializeUtils;
import lombok.Getter;
import lombok.Setter;
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
public class NodeInfoStorageService {



    private static final byte[] KEY_NODE_SETTING = "key_node_setting".getBytes();

    private static final byte[] KEY_SELF_NODE_INFO = "key_self_node_info".getBytes();



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

    public void addOrUpdateSelfNode(ExternalNodeInfo value) {
        try {
            byte[] valueBytes = SerializeUtils.serialize(value);
            db.put(ColumnFamily.NODE_INFO.getHandle(), KEY_SELF_NODE_INFO, valueBytes);
        } catch (RocksDBException e) {
            log.error("节点状态失败: key={}", KEY_SELF_NODE_INFO, e);
            throw new RuntimeException("节点状态失败", e);
        }
    }

    //获取本节点的设置信息
    public ExternalNodeInfo getNodeSelfNode() {
        try {
            byte[] valueBytes = db.get(ColumnFamily.NODE_INFO.getHandle(), KEY_SELF_NODE_INFO);
            if (valueBytes == null) {
                return null; // 不存在返回null，避免抛出异常
            }
            return (ExternalNodeInfo)SerializeUtils.deSerialize(valueBytes);
        } catch (RocksDBException e) {
            log.error("获取节点状态失败: key={}", KEY_SELF_NODE_INFO, e);
            throw new RuntimeException("获取节点状态失败", e);
        }
    }


    //..................................................................................................................
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
    private static String DB_PATH = storagePath+"/node/network" + NET_VERSION + ".db/";

    private final ReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final RocksDB db;

    private static class InstanceHolder {
        private static final NodeInfoStorageService INSTANCE = new NodeInfoStorageService();
    }
    public static NodeInfoStorageService getInstance() {
        return NodeInfoStorageService.InstanceHolder.INSTANCE;
    }

    private NodeInfoStorageService() {
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

    enum ColumnFamily {
        NODE_INFO("CF_NODE_INFO", "nodeInfo",new ColumnFamilyOptions()),

        ROUTING_TABLE("CF_BLOCK_CHAIN", "RoutingTable",new ColumnFamilyOptions()),

        ;
        final String logicalName;
        final String actualName;
        final ColumnFamilyOptions options;
        ColumnFamily(String logicalName, String actualName, ColumnFamilyOptions options) {
            this.logicalName = logicalName;
            this.actualName = actualName;
            this.options = options;
        }
        @Setter
        @Getter
        private ColumnFamilyHandle handle;
    }

}
