package com.pop.popcoinsystem;

import com.pop.popcoinsystem.util.CryptoUtil;
import lombok.extern.slf4j.Slf4j;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
@Slf4j
public class Base58Test {
    public static void main(String[] args) {


        KeyPair keyPair = CryptoUtil.ECDSASigner.generateKeyPair();
        PrivateKey privateKey = keyPair.getPrivate();
        PublicKey publicKey = keyPair.getPublic();
        log.info("公钥: {}", CryptoUtil.bytesToHex(publicKey.getEncoded()));
        String s1 = CryptoUtil.ECDSASigner.publicKeyHash256And160String(publicKey);
        log.info("公钥hash: {}", s1);
        String s = CryptoUtil.ECDSASigner.publicKeyToAddress(publicKey);
        log.info("公钥转地址: {}", s);
        log.info("验证地址是否有效: {}", CryptoUtil.ECDSASigner.isValidAddress(s));
        log.info("地址转公钥哈希: {}", CryptoUtil.ECDSASigner.addressToPublicKeyHash(s));


        //[main] INFO com.pop.popcoinsystem.Base58Test - 公钥: 3056301006072a8648ce3d020106052b8104000a03420004b20cbb7f7fad643b4e693af67ec5a9470342b0c3e1c3a1cc198240d4e641078f2132b7be4439c50e322d063f4aa6df5a6788a828529f8dc2d38d4aa85ab99eee
        //[main] INFO com.pop.popcoinsystem.Base58Test - 公钥hash: c2194ef1727bcd7086c457c2bfcc93d89686ddb1
        //[main] INFO com.pop.popcoinsystem.Base58Test - 公钥转地址转地址: 1JhJQfxF85gRr7n9efqCLaF8x43YBZyiXe
        //[main] INFO com.pop.popcoinsystem.Base58Test - 验证地址是否有效: true
        //[main] INFO com.pop.popcoinsystem.Base58Test - 地址转公钥哈希: c2194ef1727bcd7086c457c2bfcc93d89686ddb1

        log.info("地址转公钥哈希: {}", CryptoUtil.ECDSASigner.addressToPublicKeyHash(s));


        //直接提供公钥哈希


    }
}
