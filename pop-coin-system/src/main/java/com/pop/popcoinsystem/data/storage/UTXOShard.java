package com.pop.popcoinsystem.data.storage;

import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.util.Set;

@Slf4j
public class UTXOShard implements Serializable {
    private static final long serialVersionUID = 1L;

    private int utxoCount;       // 分片UTXO数量
    private Set<String> utxoKey;  //集合中保存了UTXO的key

}
