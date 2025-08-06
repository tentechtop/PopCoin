package com.pop.popcoinsystem.data.block;

import com.pop.popcoinsystem.data.transaction.Transaction;
import lombok.Data;

import java.util.List;

import static com.pop.popcoinsystem.data.block.Block.computeBlockHeaderHash;
import static com.pop.popcoinsystem.data.block.Block.validateBlockHeaderPoW;
import static com.pop.popcoinsystem.util.TypeUtils.reverseBytes;

@Data
public class BlockHeader {

    /*核心字段*/
    private int version;
    private byte[] previousHash;//前序hash
    private byte[] merkleRoot;//默克尔树 验证交易
    private long time;//时间
    private byte[] difficultyTarget;//难度目标
    private int nonce;//随机数



    //非核心字段
/*    private long height;
    private byte[] hash;
    private long medianTime;
    private byte[] chainWork;
    private long difficulty;
    private int txCount;
    private long witnessSize;
    private long size;
    private long weight;*/

    /**
     * 计算区块哈希（统一入口）
     * 基于区块头信息计算双SHA-256哈希
     */
    public byte[] computeHash() {
        return computeBlockHeaderHash(this);
    }


    /**
     * 验证区块POW（统一入口）
     * 检查区块哈希是否满足难度目标要求
     */
    public boolean validatePoW() {
        return validateBlockHeaderPoW(this);
    }


    public byte[] getHash() {
        return computeBlockHeaderHash(this);
    }


    public BlockHeader clone() {
        BlockHeader blockHeaderCopy = new BlockHeader();
        blockHeaderCopy.setTime(this.getTime() );
        blockHeaderCopy.setPreviousHash(this.getPreviousHash());
        blockHeaderCopy.setMerkleRoot(this.getMerkleRoot());
        blockHeaderCopy.setNonce(this.getNonce());
        blockHeaderCopy.setDifficultyTarget(this.getDifficultyTarget());
        blockHeaderCopy.setVersion(this.getVersion());
        return blockHeaderCopy;
    }

}
