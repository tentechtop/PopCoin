package com.pop.popcoinsystem.data.block;

import com.pop.popcoinsystem.data.transaction.Transaction;
import com.pop.popcoinsystem.data.transaction.dto.TransactionDTO;
import com.pop.popcoinsystem.util.CryptoUtil;
import lombok.Data;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

@Data
public class BlockDTO {
    //表示该区块在区块链中的高度，即它是第几个区块。这里的高度为 1，表示它是区块链中的第一个区块（创世块是高度 0）。
    private long height;//区块高度 0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18.........................
    //唯一的标识
    private String hash;
    public void setHash(byte[] hash){
        this.hash = CryptoUtil.bytesToHex(hash);
    }
    private String previousHash;
    public void setPreviousHash(byte[] previousHash){
        this.previousHash = CryptoUtil.bytesToHex(previousHash);
    }
    //版本号
    private int version;
    // 表示该区块中所有交易的默克尔树根哈希值。它用于快速验证区块中的交易是否被篡改。
    private String merkleRoot;//默克尔根
    public void setMerkleRoot(byte[] merkleRoot){
        this.merkleRoot = CryptoUtil.bytesToHex(merkleRoot);
    }

    //表示该区块的时间戳，以秒为单位的 Unix 时间戳。
    private long time;
    //表示该区块之前 11 个区块的中位时间。它用于一些时间敏感的计算。
    private long medianTime;
    //表示该区块之前的区块链总工作量，以十六进制表示。它反映了整个区块链的挖矿工作量。
    private String chainWork;
    public void setChainWork(byte[] chainWork){
        this.chainWork = CryptoUtil.bytesToHex(chainWork);
    }
    //表示该区块的挖矿难度。它反映了挖矿的难度程度，即找到一个有效区块哈希的难度。
    //. 挖矿难度（Difficulty）
    //本质：一个 相对数值，表示当前难度相对于创世区块难度的倍数
    //作用：作为人类可读的难度指标，方便比较不同时期的挖矿难度
    //创世区块难度：固定为 1.0
    //比特币难度值目前约在 4e12 以内，远小于 2^31-1
    private long difficulty;
    //表示该区块的难度目标，以紧凑格式表示。它用于挖矿过程中的工作量证明计算。
    //1. 难度目标（Difficulty Target）
    //本质：一个 256 位的数值（通常以十六进制表示）
    //作用：直接作为矿工挖矿的目标阈值
    //规则：矿工需要找到一个区块哈希值，使得该哈希值 小于 难度目标
    //表示形式：
    //压缩格式（Difficulty Bits）：如 0x1d00ffff
    //完整格式（Target）：如 0x00000000FFFF0000...（前 32 位为 0，后 224 位为 F）
    private String difficultyTarget;
    public void setDifficultyTarget(byte[] difficultyTarget){
        this.difficultyTarget = CryptoUtil.bytesToHex(difficultyTarget);
    }
    //表示该区块的随机数，用于挖矿过程中的工作量证明计算。
    private int nonce;
    //表示该区块中包含的交易数量
    private int txCount;
    //表示该区块的见证数据大小（以字节为单位）。
    private long witnessSize;
    //表示该区块的大小（以字节为单位），包括见证数据。
    private long size;
    //表示该区块的权重，用于比特币的区块大小限制计算。
    private long weight;

    private List<TransactionDTO> transactions = new ArrayList<>();

}
