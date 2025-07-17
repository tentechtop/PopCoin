package com.pop.popcoinsystem;

import com.pop.popcoinsystem.util.CryptoUtil;
import lombok.extern.slf4j.Slf4j;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
@Slf4j
public class testSig {
    public static void main(String[] args) {
        KeyPair keyPair = CryptoUtil.ECDSASigner.generateKeyPair();
        PrivateKey privateKey = keyPair.getPrivate();
        PublicKey publicKey = keyPair.getPublic();


/*
        String s = CryptoUtil.ECDSASigner.createP2SHAddressByPK(publicKey.getEncoded());
        log.info("公钥转地址: {}", s);
        log.info("真正的地址: 38FpgNpgMQEmhBZAwXpfYhfnLE85PW7Poo");
        //验证是否正确
        log.info("验证地址是否正确: {}", CryptoUtil.ECDSASigner.isValidP2SHAddress(s));
*/


        String s = CryptoUtil.ECDSASigner.createP2WSHAddressByPK(publicKey.getEncoded());
        log.info("公钥转地址: {}", s);
        log.info("真正的地址: bc1q5md6u65fvd0a4su53xtkj0qmt5p4gyvl8gnyg6gyc7d8fck69scqh9caqx");
        //验证是否正确
        log.info("验证地址是否正确: {}", CryptoUtil.ECDSASigner.isValidP2WSHAddress(s));


        String s1 = CryptoUtil.ECDSASigner.createP2WPKHAddressByPK(publicKey.getEncoded());
        log.info("公钥转地址: {}", s1);
        log.info("真正的地址: bc1q5md6u65fvd0a4su53xtkj0qmt5p4gyvl8gnyg6gyc7d8fck69scqh9caqx");
        //验证是否正确
        log.info("验证地址是否正确: {}", CryptoUtil.ECDSASigner.isValidP2WPKHAddress(s1));












    }
}
