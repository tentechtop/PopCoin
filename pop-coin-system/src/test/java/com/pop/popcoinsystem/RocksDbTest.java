package com.pop.popcoinsystem;

import com.pop.popcoinsystem.data.block.Block;
import com.pop.popcoinsystem.data.storage.TransactionBlockChainStorage;

import java.util.List;

public class RocksDbTest {


    public static void main(String[] args) {
/*        BlockChainRocksDBStorageBack1 instance = BlockChainRocksDBStorageBack1.getInstance();
        Block block = new Block();
        block.setHash("123456");
        instance.putBlock(block);

        Block block1 = instance.getBlock("123456");
        System.out.println("查询的区块"+block1);*/


        Block block = new Block();
        block.setHash("123456");

        Block block2 = new Block();
        block2.setHash("1234567");
        block2.setPreviousHash("123456");

        TransactionBlockChainStorage instance = TransactionBlockChainStorage.getInstance();
        instance.putBlock(block);
        instance.putBlock(block2);

        List<Block> previousBlocks = instance.getPreviousBlocksAndSelf("1234567",100);
        System.out.println("查询的区块"+previousBlocks);







    }
}
