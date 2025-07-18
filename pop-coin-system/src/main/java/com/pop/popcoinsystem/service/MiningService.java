package com.pop.popcoinsystem.service;

import com.pop.popcoinsystem.data.block.Block;
import com.pop.popcoinsystem.data.transaction.Transaction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 打包交易服务
 */
@Slf4j
@Service
public class MiningService {

    //服务是否启动
    private boolean isStart = false;



    /**
     * 打包交易，进行挖矿
     * @param transactions
     */
    public void mineBlock(List<Transaction> transactions) throws Exception {
        //获取最新的区块哈希
        String lastBlockHash = "213";
        if (lastBlockHash == null || lastBlockHash.isEmpty()) {
            throw new Exception("ERROR: Fail to get last block hash ! ");
        }
        Block block = new Block();
        block.setPreviousHash(lastBlockHash);
        this.addBlock(block,transactions);
    }


    /**
     *  添加区块
     * @param block
     */
    public void addBlock(Block block,List<Transaction> transaction) {
        //添加区块
        //更新最新的区块信息  如 区块哈希、区块高度、区块时间


    }


}
