package com.pop.popcoinsystem.data.block;

import com.pop.popcoinsystem.data.transaction.Transaction;
import lombok.Data;

import java.util.List;

@Data
public class BlockHeader {

    private int version;
    private byte[] previousHash;
    private byte[] merkleRoot;
    private long time;
    private byte[] difficultyTarget;
    private int nonce;


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
