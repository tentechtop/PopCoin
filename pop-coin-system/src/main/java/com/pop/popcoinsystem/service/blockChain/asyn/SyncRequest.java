package com.pop.popcoinsystem.service.blockChain.asyn;

import com.pop.popcoinsystem.network.common.ExternalNodeInfo;
import lombok.Data;

@Data
public class SyncRequest {

    //创世区块Hash
    private byte[] genesisBlockHash;
    //最新区块hash
    private byte[] latestBlockHash;
    //最新区块高度
    private long latestBlockHeight;
    //工作总量
    private byte[] chainWork;

}
