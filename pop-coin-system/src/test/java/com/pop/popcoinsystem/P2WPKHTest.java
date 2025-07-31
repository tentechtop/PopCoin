package com.pop.popcoinsystem;

import com.pop.popcoinsystem.data.script.AddressType;
import com.pop.popcoinsystem.util.CryptoUtil;
import lombok.extern.slf4j.Slf4j;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Objects;

@Slf4j
public class P2WPKHTest {
    public static void main(String[] args) {


        KeyPair keyPair1 = CryptoUtil.ECDSASigner.generateKeyPair();
        PrivateKey privateKey1 = keyPair1.getPrivate();
        PublicKey publicKey1 = keyPair1.getPublic();
        log.info("公钥1: {}", CryptoUtil.bytesToHex(publicKey1.getEncoded()));

        KeyPair keyPair2 = CryptoUtil.ECDSASigner.generateKeyPair();
        PrivateKey privateKey2 = keyPair2.getPrivate();
        PublicKey publicKey2 = keyPair2.getPublic();
        log.info("公钥2: {}", CryptoUtil.bytesToHex(publicKey2.getEncoded()));

        KeyPair keyPair3 = CryptoUtil.ECDSASigner.generateKeyPair();
        PrivateKey privateKey3 = keyPair3.getPrivate();
        PublicKey publicKey3 = keyPair3.getPublic();
        log.info("公钥3: {}", CryptoUtil.bytesToHex(publicKey3.getEncoded()));

        KeyPair keyPair4 = CryptoUtil.ECDSASigner.generateKeyPair();
        PrivateKey privateKey4 = keyPair4.getPrivate();
        PublicKey publicKey4 = keyPair4.getPublic();



        String P2WPKHAddress = CryptoUtil.ECDSASigner.createP2WPKHAddressByPK(publicKey1.getEncoded());
        log.info("公钥转地址: {}", P2WPKHAddress);
        log.info("验证地址是否有效: {}", CryptoUtil.ECDSASigner.isValidP2WPKHAddress(P2WPKHAddress));
        log.info("地址转哈希: {}", CryptoUtil.bytesToHex(Objects.requireNonNull(CryptoUtil.ECDSASigner.addressToP2WPKH(P2WPKHAddress))));
        log.info("公钥转哈希: {}", CryptoUtil.bytesToHex(CryptoUtil.ECDSASigner.createP2PKHByPK(publicKey1.getEncoded())));
        AddressType addressType = CryptoUtil.ECDSASigner.getAddressType(P2WPKHAddress);
        log.info("地址类型: {}", addressType);



















    }
}
