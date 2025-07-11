package com.pop.popcoinsystem.data.blockChain;

import com.pop.popcoinsystem.data.block.Block;
import lombok.Data;

/**
 * 这是区块链数据
 */
@Data
public class BlockChain {

    private Block genesis;
    private Block last;
    private int length;


}
