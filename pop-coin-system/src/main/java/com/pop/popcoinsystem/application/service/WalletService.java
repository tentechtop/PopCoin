package com.pop.popcoinsystem.application.service;

import com.pop.popcoinsystem.data.transaction.UTXO;
import com.pop.popcoinsystem.service.UTXOService;
import com.pop.popcoinsystem.util.CryptoUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class WalletService {

    @Resource
    private UTXOService utxoService;


    //模拟用户构建交易  已经知道 A用户的公钥和私钥  对 b地址进行转账交易
    //将构建的交易提交到网络  并验证  验证通过后  提交到交易池子




    /**
     * 转账
     * @param from  发送方地址
     * 在比特币转账过程中，发送方需要提供的核心信息是证明其有权使用特定资金的签名和公钥。结合你的代码框架，以下是转账时发送方必须提供的关键信息及其作用：
     * 创建一笔交易让系统验证
     */
    public void send(String from, String to, double amount) throws Exception {
        //查询 from 的地址中的UTXO



    }








    /**
     * 查询地址中的UTXO
     */
    public List<UTXO> queryAddressUTXO(String  address) {
        ArrayList<UTXO> utxoArrayList = new ArrayList<>();

        return utxoArrayList;
    }


    /**
     * 判断地址中的UTXO是否够支付
     */
    public boolean isEnoughUTXO(String address, long amount) {
        byte[] addressHash = CryptoUtil.ECDSASigner.getAddressHash(address);


        return true;
    }






}
