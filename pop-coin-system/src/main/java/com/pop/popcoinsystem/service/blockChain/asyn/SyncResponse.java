package com.pop.popcoinsystem.service.blockChain.asyn;

import lombok.Data;

@Data
public class SyncResponse {
    //是否允许同步
    private boolean allowSync; // false不允许 true允许
    //拒绝原因
    private String rejectReason;// 允许是为null
    //创世区块Hash
    private byte[] genesisBlockHash;
    //最新区块hash
    private byte[] latestBlockHash;
    //最新区块高度
    private long latestBlockHeight;
    //工作总量
    private byte[] chainWork;
}
