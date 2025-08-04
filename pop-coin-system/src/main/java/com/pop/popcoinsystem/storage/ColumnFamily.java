package com.pop.popcoinsystem.storage;

import lombok.Getter;
import lombok.Setter;
import org.rocksdb.BlockBasedTableConfig;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;

public enum ColumnFamily {
    //hash->区块头
    BLOCK("CF_BLOCK", "block",new ColumnFamilyOptions()),
    //hash - >区块体
    BLOCK_BODY("CF_BLOCK_BODY", "blockBody",new ColumnFamilyOptions()),
    //hash -> 区块高度
    BLOCK_HASH_HEIGHT("CF_BLOCK_HASH_HEIGHT", "blockHashHeight",new ColumnFamilyOptions()),
    BLOCK_HASH_CHAIN_WORK("CF_BLOCK_HASH_CHAIN_WORK", "blockHashChainWork",new ColumnFamilyOptions()),


    //交易到区块的索引 Map<String, byte[]>   一笔交易只可能存在于一个区块
    TRANSACTION_INDEX("CF_TRANSACTION_INDEX", "transactionIndex", new ColumnFamilyOptions()),
    //主链索引 高度到区块哈希
    MAIN_BLOCK_CHAIN_INDEX("CF_MAIN_BLOCK_CHAIN_INDEX", "mainBlockChainIndex",new ColumnFamilyOptions()),
    // 备选链存储，用于处理分叉 Map<Long, Set<byte[]>>  高度到 多个区块hash
    ALT_BLOCK_CHAIN_INDEX("CF_ALT_BLOCK_CHAIN_INDEX", "altBlockChainIndex",new ColumnFamilyOptions()),


    //区块链信息  存储一切和区块链有关的信息
    BLOCK_CHAIN("CF_BLOCK_CHAIN", "blockChain",new ColumnFamilyOptions()),

    //UTXO 基础索引
    UTXO("CF_UTXO", "utxo",new ColumnFamilyOptions()
            .setTableFormatConfig(new BlockBasedTableConfig()
                    .setBlockCacheSize(128 * 1024 * 1024) // 64MB 块缓存
                    .setCacheIndexAndFilterBlocks(true)) ),

    //脚本hash_utxoKey - > 金额索引
    SCRIPT_UTXO("CF_SCRIPT_UTXO", "scriptUtxo",new ColumnFamilyOptions()),

    NODE_INFO("CF_NODE_INFO", "nodeInfo",new ColumnFamilyOptions()),

    ROUTING_TABLE("CF_BLOCK_CHAIN", "RoutingTable",new ColumnFamilyOptions()),

    MINER_INFO("CF_MINER_INFO", "minerInfo",new ColumnFamilyOptions()),


    SYNC_TASK("CF_SYNC_TASK", "syncTask",new ColumnFamilyOptions()),

    SYNC_PROGRESS("CF_SYNC_PROGRESS", "syncProgress",new ColumnFamilyOptions()),
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

