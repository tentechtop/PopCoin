package com.pop.popcoinsystem.network.protocol.messageData;

import com.pop.popcoinsystem.network.common.ExternalNodeInfo;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@AllArgsConstructor
@NoArgsConstructor
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
