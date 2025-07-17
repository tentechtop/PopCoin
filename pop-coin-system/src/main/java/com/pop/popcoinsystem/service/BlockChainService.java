package com.pop.popcoinsystem.service;

import com.pop.popcoinsystem.data.block.Block;
import com.pop.popcoinsystem.data.blockChain.BlockChain;
import com.pop.popcoinsystem.data.storage.test.BlockChainRocksDBStorageBack;
import com.pop.popcoinsystem.data.transaction.TXOutput;
import com.pop.popcoinsystem.data.transaction.Transaction;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class BlockChainService {

    /**
     * <p> 创建区块 </p>
     * @param address 在比特币网络里，矿工在挖矿过程中，当成功挖到一个新区块时，会在区块的 coinbase 交易里提供公钥哈希（对应地址），
     *                而非直接提供公钥。下面详细剖析这一过程：   用户提供地址  address是一个钱包地址
     * @return
     */
    public static BlockChain createBlock(String address) {
        String lastBlockHash = BlockChainRocksDBStorageBack.getInstance().getLastBlockHash();//最新的区块的哈希
        if (StringUtils.isBlank(lastBlockHash)) {


        }
        return new BlockChain();
    }


    /**
     * 转账
     * @param from  发送方地址
     * 在比特币转账过程中，发送方需要提供的核心信息是证明其有权使用特定资金的签名和公钥。结合你的代码框架，以下是转账时发送方必须提供的关键信息及其作用：
     */
    public void send(String from, String to, double amount) throws Exception {

    }



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
     * <p> 添加区块  </p>
     *
     * @param block
     */
    public void addBlock(Block block,List<Transaction> transaction) {
        //添加区块
        //更新最新的区块信息  如 区块哈希、区块高度、区块时间


    }






    /**
     * 查找钱包地址对应的所有未花费的交易
     *
     * @param address 钱包地址
     * @return
     */
    public List<TXOutput> findUnspentTransactions(String address) {
        ArrayList<TXOutput> utxoList = new ArrayList<>();



        return utxoList;
    }



    /**
     * 寻找能够花费的交易
     *
     * @param address 公钥哈希
     * @param amount  花费金额
     */






}
