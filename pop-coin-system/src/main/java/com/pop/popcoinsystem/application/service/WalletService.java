package com.pop.popcoinsystem.application.service;

import com.pop.popcoinsystem.data.transaction.*;
import com.pop.popcoinsystem.service.UTXOService;
import com.pop.popcoinsystem.util.CryptoUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class WalletService {

    /**
     * 系统有UTXO集合 key:交易ID + 索引  value: UTXO
     * 用户单签钱包中的UTXO  key:公钥哈希 或者 赎回脚本哈希  value:UTXO
     * 多钱钱包 会有多个公钥   需要其中两个才能验证
     *
     */

    /**
     * 目前先完成P2PKH 和 P2WPKH
     */



    @Resource
    private UTXOService utxoService;

    //模拟用户构建交易  已经知道 A用户的公钥和私钥  对 b地址进行转账交易
    //将构建的交易提交到网络  并验证  验证通过后  提交到交易池子
    //默认已经知道了用户的公钥

    public static Wallet walleta;
    public static Wallet walletb;

    static {
        log.info("钱包初始化 WalletService init");
        //获取公钥和私钥  初始化两个钱包 A B
        //先从数据库中获取公钥和私钥
        TemplateStorage instance = TemplateStorage.getInstance();
        walleta = instance.getWallet("wallet-a");
        log.info("钱包A"+walleta);
        if (walleta == null){
            KeyPair keyPairA = CryptoUtil.ECDSASigner.generateKeyPair();
            PrivateKey privateKey = keyPairA.getPrivate();
            PublicKey publicKey = keyPairA.getPublic();
            walleta = new Wallet();
            walleta.setName("wallet-a");
            walleta.setPublicKeyHex(CryptoUtil.bytesToHex(publicKey.getEncoded()));
            walleta.setPrivateKeyHex(CryptoUtil.bytesToHex(privateKey.getEncoded()));
            instance.addWallet(walleta);
        }
        walletb = instance.getWallet("wallet-b");
        log.info("钱包B"+walletb);
        if (walletb == null){
            KeyPair keyPairB = CryptoUtil.ECDSASigner.generateKeyPair();
            PrivateKey privateKey = keyPairB.getPrivate();
            PublicKey publicKey = keyPairB.getPublic();
            walletb = new Wallet();
            walletb.setName("wallet-b");
            walletb.setPublicKeyHex(CryptoUtil.bytesToHex(publicKey.getEncoded()));
            walletb.setPrivateKeyHex(CryptoUtil.bytesToHex(privateKey.getEncoded()));
            instance.addWallet(walletb);
        }
    }









}
