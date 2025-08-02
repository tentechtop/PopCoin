package com.pop.popcoinsystem.service;

import com.pop.popcoinsystem.application.service.wallet.Wallet;
import com.pop.popcoinsystem.application.service.wallet.WalletStorage;
import com.pop.popcoinsystem.data.miner.Miner;
import com.pop.popcoinsystem.data.vo.result.Result;
import com.pop.popcoinsystem.storage.StorageService;
import com.pop.popcoinsystem.util.CryptoUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;

@Slf4j
@Service
public class MiningService implements ApplicationRunner {
    @Autowired
    private Mining miningService;
    @Autowired
    private StorageService storageService;

    /**
     * 设置节点矿工信息
     */
    public Result<String> setMinerInfo(Miner miner){
        try {
            storageService.addOrUpdateMiner(miner);
            return Result.ok();
        }catch (Exception e){
            return Result.error(e.getMessage());
        }
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        Miner miner = storageService.getMiner();

        WalletStorage walletStorage = WalletStorage.getInstance();
        Wallet walleta = walletStorage.getWallet("btcminer");
        if (walleta == null){
            KeyPair keyPairA = CryptoUtil.ECDSASigner.generateKeyPair();
            PublicKey publicKey = keyPairA.getPublic();
            PrivateKey privateKey = keyPairA.getPrivate();

            KeyPair keyPairA1 = CryptoUtil.ECDSASigner.generateKeyPair();
            PublicKey publicKey1 = keyPairA1.getPublic();
            PrivateKey privateKey1 = keyPairA1.getPrivate();

            KeyPair keyPairA2 = CryptoUtil.ECDSASigner.generateKeyPair();
            PublicKey publicKey2 = keyPairA2.getPublic();
            PrivateKey privateKey2 = keyPairA2.getPrivate();

            walleta = new Wallet();
            walleta.setName("btcminer");
            walleta.setPublicKeyHex(CryptoUtil.bytesToHex(publicKey.getEncoded()));
            walleta.setPrivateKeyHex(CryptoUtil.bytesToHex(privateKey.getEncoded()));

            walleta.setPublicKeyHex1(CryptoUtil.bytesToHex(publicKey1.getEncoded()));
            walleta.setPrivateKeyHex1(CryptoUtil.bytesToHex(privateKey1.getEncoded()));

            walleta.setPublicKeyHex2(CryptoUtil.bytesToHex(publicKey2.getEncoded()));
            walleta.setPrivateKeyHex2(CryptoUtil.bytesToHex(privateKey2.getEncoded()));

            walletStorage.addWallet(walleta);
        }
        byte[] bytesMiner = CryptoUtil.hexToBytes(walleta.getPublicKeyHex());
        String p2PKHAddressMiner = CryptoUtil.ECDSASigner.createP2PKHAddressByPK(bytesMiner);
        String p2WPKHAddressMiner = CryptoUtil.ECDSASigner.createP2WPKHAddressByPK(bytesMiner);
        log.info("矿工钱包地址P2PKH：{}",p2PKHAddressMiner);
        log.info("矿工钱包地址P2WPKH：{}",p2WPKHAddressMiner);
        /*新增测试钱包*/
        Wallet wallettest = walletStorage.getWallet("wallettest");
        log.info("wallettest: "+wallettest);
        if (wallettest == null){
            KeyPair keyPairA = CryptoUtil.ECDSASigner.generateKeyPair();
            PublicKey publicKey = keyPairA.getPublic();
            PrivateKey privateKey = keyPairA.getPrivate();

            KeyPair keyPairA1 = CryptoUtil.ECDSASigner.generateKeyPair();
            PublicKey publicKey1 = keyPairA1.getPublic();
            PrivateKey privateKey1 = keyPairA1.getPrivate();

            KeyPair keyPairA2 = CryptoUtil.ECDSASigner.generateKeyPair();
            PublicKey publicKey2 = keyPairA2.getPublic();
            PrivateKey privateKey2 = keyPairA2.getPrivate();

            wallettest = new Wallet();
            wallettest.setName("wallettest");
            wallettest.setPublicKeyHex(CryptoUtil.bytesToHex(publicKey.getEncoded()));
            wallettest.setPrivateKeyHex(CryptoUtil.bytesToHex(privateKey.getEncoded()));

            wallettest.setPublicKeyHex1(CryptoUtil.bytesToHex(publicKey1.getEncoded()));
            wallettest.setPrivateKeyHex1(CryptoUtil.bytesToHex(privateKey1.getEncoded()));

            wallettest.setPublicKeyHex2(CryptoUtil.bytesToHex(publicKey2.getEncoded()));
            wallettest.setPrivateKeyHex2(CryptoUtil.bytesToHex(privateKey2.getEncoded()));
            walletStorage.addWallet(wallettest);
        }
        String publicKeyHexTest = wallettest.getPublicKeyHex();
        byte[] bytesTest = CryptoUtil.hexToBytes(publicKeyHexTest);
        String p2PKHAddressByPK = CryptoUtil.ECDSASigner.createP2PKHAddressByPK(bytesTest);
        String p2WPKHAddressByPK = CryptoUtil.ECDSASigner.createP2WPKHAddressByPK(bytesTest);
        log.info("p2PKH Test 测试钱包地址: {}", p2PKHAddressByPK);
        log.info("p2WPKH Test 测试钱包地址: {}", p2WPKHAddressByPK);

        miner = new Miner();
        miner.setAddress(p2WPKHAddressMiner);
        miner.setName("btcminer");
        setMinerInfo(miner);

        //if (miner == null) return;
        // 等 Spring 容器完全初始化后再启动挖矿
    /*    miningService.startMining();*/
    }

    public Result<String> startMining(Miner miner) throws Exception {
        return miningService.startMining();
    }

    public Result<String> stopMining() throws Exception {
        return miningService.stopMining();
    }
}
