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
     * 验证交易
     */
    public boolean verifyTransaction(Transaction transaction) {
        //检查这些被引用的 UTXO 是否存在于当前的 UTXO 集中（即未被花费）；
        //验证发送方对这些 UTXO 的所有权（通过签名验证，证明发送方拥有对应私钥）。
        //验证后 如果节点在挖矿则提交到交易池 如歌不在则广播
        //保存交易中的UTXO集合





        return true;
    }


    /**
     * 验证区块
     */
















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
