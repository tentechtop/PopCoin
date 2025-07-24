package com.pop.popcoinsystem.data.storage;

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

    //主链索引 高度到区块哈希 Map<Long, byte[]>  height -> hash
    MAIN_BLOCK_INDEX("CF_BMAIN_BLOCK_INDEX", "mainBlockIndex",new ColumnFamilyOptions()),

    // 备选链存储，用于处理分叉 Map<Long, List<byte[]>>
    ALT_BLOCK_INDEX("CF_ALT_BLOCK_INDEX", "altBlockIndex",new ColumnFamilyOptions()),

    //冲突高度  哪些高度冲突了  直接存高度即可 key是高度 value是高度  用迭代器迭代所有的冲突高度
    BLOCK_HEIGHT_CLASH("CF_BLOCK_HEIGHT_CLASH", "blockHeightClash",new ColumnFamilyOptions()),





    //区块链信息  存储一切和区块链有关的信息
    BLOCK_CHAIN("CF_BLOCK_CHAIN", "blockChain",new ColumnFamilyOptions()),

    //UTXO 基础索引
    UTXO("CF_UTXO", "utxo",new ColumnFamilyOptions()
            .setTableFormatConfig(new BlockBasedTableConfig()
                    .setBlockCacheSize(128 * 1024 * 1024) // 64MB 块缓存
                    .setCacheIndexAndFilterBlocks(true)) ),


    //交易到区块的索引 Map<String, byte[]>   一笔交易只可能存在于一个区块
    TRANSACTION_INDEX("CF_TRANSACTION_INDEX", "transactionIndex", new ColumnFamilyOptions()),



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

