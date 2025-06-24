package com.pop.popcoinsystem.util;


import java.security.*;
import java.security.spec.ECGenParameterSpec;


/**
 * 加密工具类 - 提供哈希、签名和密钥管理功能
 */

public class CryptoUtil {

    /**
     * 椭圆曲线
     */
    public static class ECDSASigner {
        // 静态初始化Bouncy Castle提供者
        static {
            Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
        }
        /**
         * 生成ECDSA密钥对（secp256k1曲线）
         */
        public static KeyPair generateKeyPair() {
            try {
                KeyPairGenerator keyGen = KeyPairGenerator.getInstance("EC", "BC");
                SecureRandom random = SecureRandom.getInstanceStrong();
                ECGenParameterSpec ecSpec = new ECGenParameterSpec("secp256k1");
                keyGen.initialize(ecSpec, random);
                return keyGen.generateKeyPair();
            } catch (Exception e) {
                throw new RuntimeException("生成密钥对失败", e);
            }
        }
        /**
         * 应用ECDSA签名 对数据签名
         */
        public static byte[] applySignature(PrivateKey privateKey, byte[] data) {
            try {
                // 显式指定BC提供者
                Signature dsa = Signature.getInstance("SHA256withECDSA", "BC");
                dsa.initSign(privateKey);
                dsa.update(data); // 传入原始数据
                return dsa.sign();
            } catch (Exception e) {
                throw new RuntimeException("应用签名失败", e);
            }
        }
        /**
         * 验证ECDSA签名 对签名的数据进行验证
         */
        public static boolean verifySignature(PublicKey publicKey, byte[] data, byte[] signature) {
            try {
                // 显式指定BC提供者
                Signature dsa = Signature.getInstance("SHA256withECDSA", "BC");
                dsa.initVerify(publicKey);
                dsa.update(data);//明文
                return dsa.verify(signature);
            } catch (Exception e) {
                throw new RuntimeException("验证签名失败", e);
            }
        }

        /**
         * 公钥转地址 RIPEMD-160哈希 较短计算较快
         */
        public static byte[] publicKeyToAddress(PublicKey publicKey) {
            return applyRIPEMD160(applySHA256(publicKey.getEncoded()));
        }
    }



    /**
     * 应用SHA256哈希
     * 主要用于区块的哈希计算
     */
    public static byte[] applySHA256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA256算法不可用", e);
        }
    }

    /**
     * 应用RIPEMD160哈希
     * 主要用于地址的计算
     */
    public static byte[] applyRIPEMD160(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("RIPEMD160");
            return md.digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("RIPEMD160算法不可用", e);
        }
    }












}