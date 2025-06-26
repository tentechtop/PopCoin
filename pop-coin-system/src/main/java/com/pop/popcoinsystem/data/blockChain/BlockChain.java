package com.pop.popcoinsystem.data.blockChain;

import com.pop.popcoinsystem.data.block.Block;
import lombok.Data;

@Data
public class BlockChain {

    private String name;
    private String description;
    private Block myBlock;
}
