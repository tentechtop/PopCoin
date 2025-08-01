package com.pop.popcoinsystem.network.protocol.message.content;

import com.pop.popcoinsystem.network.common.ExternalNodeInfo;
import lombok.Data;

import java.io.Serializable;

@Data
public class Handshake implements Serializable {

    private ExternalNodeInfo externalNodeInfo;

    //创世区块Hash
    private byte[] genesisBlockHash;

    //最新区块hash
    private byte[] latestBlockHash;
    //最新区块高度
    private long latestBlockHeight;

    //工作总量
    private byte[] chainWork;





}
