package com.pop.popcoinsystem.data.block;

import com.pop.popcoinsystem.data.transaction.Transaction;
import lombok.Data;

import java.util.List;

@Data
public class BlockHeader {

    /*核心字段*/
    private int version;
    private byte[] previousHash;//前序hash
    private byte[] merkleRoot;//默克尔树 验证交易
    private long time;//时间
    private byte[] difficultyTarget;//难度目标
    private int nonce;//随机数


    private long height;
    private byte[] hash;


    private long medianTime;
    private byte[] chainWork;
    private long difficulty;
    private int txCount;
    private long witnessSize;
    private long size;
    private long weight;


}
