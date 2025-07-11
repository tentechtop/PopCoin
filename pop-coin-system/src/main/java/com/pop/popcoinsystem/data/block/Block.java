package com.pop.popcoinsystem.data.block;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.BigInteger;

@Data
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class Block implements Serializable {

    //表示该区块在区块链中的高度，即它是第几个区块。这里的高度为 1，表示它是区块链中的第一个区块（创世块是高度 0）。
    private BigInteger  height;//区块高度 0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18.........................
    //唯一的标识
    private String hash;
    //前一个区块的哈希值
    private String previousHash;
    //确认区块数量
    private BigInteger confirmations;
    //版本号
    private int version;
    //版本16进制
    private String versionHex;
    // 表示该区块中所有交易的默克尔树根哈希值。它用于快速验证区块中的交易是否被篡改。
    private String merkleRoot;//默克尔根
    //表示该区块的时间戳，以秒为单位的 Unix 时间戳。
    private long time;
    //表示该区块之前 11 个区块的中位时间。它用于一些时间敏感的计算。
    private long medianTime;

    //表示该区块之前的区块链总工作量，以十六进制表示。它反映了整个区块链的挖矿工作量。
    private String chainWork;
    //表示该区块的挖矿难度。它反映了挖矿的难度程度，即找到一个有效区块哈希的难度。
    private BigInteger difficulty;
    //表示该区块的难度目标的十六进制表示。目标阈值。目标阈值：难度目标本质上是一个极大的数值（256 位整数）。矿工需要找到一个区块哈希值，使得该哈希值小于这个目标阈值。
    private String difficultyHex;
    //表示该区块的难度目标，以紧凑格式表示。它用于挖矿过程中的工作量证明计算。
    private String difficultyBits;
    //表示该区块的随机数，用于挖矿过程中的工作量证明计算。
    private String nonce;


    //表示该区块中包含的交易数量
    private int txCount;

    //表示该区块的见证数据大小（以字节为单位）。
    private int strippedSize;
    //表示该区块的大小（以字节为单位），包括见证数据。
    private int size;
    //表示该区块的权重，用于比特币的区块大小限制计算。
    private int weight;






}
