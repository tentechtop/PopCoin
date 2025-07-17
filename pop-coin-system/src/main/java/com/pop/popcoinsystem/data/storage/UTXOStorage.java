package com.pop.popcoinsystem.data.storage;

import static com.pop.popcoinsystem.util.CryptoUtil.POP_NET_VERSION;

/**
 * 公钥哈希和未花费
 * 能确保数据一致 和交易速度
 * 用桶存储未花费id 按照金额设置桶
 */
public class UTXOStorage {
    // 数据库存储路径
    private static final String DB_PATH = "rocksDb/popCoin.db/blockChain"+POP_NET_VERSION+".db/utxo";






}
