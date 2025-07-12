package com.pop.popcoinsystem.data.blockChain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.math.BigInteger;

/**
 * 区块链
 * 区块链本质上是一种有序、反向链接链表的数据结构。这意味着，
 * block按照插入的顺序存放，同时每个block都保存指向上一个
 * block的链接。这种结构保证可以快速获取最新插入的block同
 * 时获取它的hash值。这种结构保证可以快速获取最新插入的block
 * 同时（高效地）获取它的hash值。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Slf4j
public class BlockChain {

    //网络 链
    private String chain;

    //当前网络区块数量
    private int chainLength;

    //表示已知的区块头数量。通常情况下，这个值与 blocks 相同，表示节点已经完全同步了区块链。
    private int knownHeaderCount;

    //最新区块hash  表示当前区块链中最顶端区块（最新区块）的哈希值。它是区块链的最新状态的标识。
    private String latestBlockHash;

    //表示当前的挖矿难度。它反映了挖矿的难度程度，即找到一个有效区块哈希的难度。
    private BigInteger difficulty;

    //表示当前的挖矿难度目标的十六进制表示。它与 bits 有关，用于计算有效的区块哈希。
    private String difficultyHex;

    //表示当前的挖矿难度目标，以紧凑格式表示。它用于挖矿过程中的工作量证明计算。
    private String difficultyBits;

    //表示最新区块的时间戳，以秒为单位的 Unix 时间戳。
    private long time;

    //表示区块链中前 11 个区块的中位时间。它用于一些时间敏感的计算。
    private long medianTime;

    //表示区块链同步的进度。这里的值为 1，表示区块链已经完全同步。
    private int syncProgress;

    //表示节点是否仍在进行初始区块链下载。值为 false，表示下载已完成。
    private boolean initialBlockDownload;

    //表示区块链的总工作量，以十六进制表示。它反映了整个区块链的挖矿工作量
    private String chainWork;

    //表示区块链数据在磁盘上占用的大小，以字节为单位。
    private long sizeOnDisk;

    //表示区块链是否被修剪。修剪是一种减少区块链数据占用空间的方法，值为 false 表示未修剪。
    private boolean pruned;

    //表示区块链的警告信息。通常为空，表示没有警告。如果有 警告，则该字段将包含警告信息。 格式为 警告1(原因,地址),警告2
    private String warnings;






}
