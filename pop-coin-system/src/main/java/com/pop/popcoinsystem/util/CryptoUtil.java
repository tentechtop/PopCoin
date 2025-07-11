package com.pop.popcoinsystem.util;

import com.pop.popcoinsystem.data.script.ScriptPubKey;
import lombok.Data;
import org.springframework.util.DigestUtils;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.UUID;
import java.security.*;
import java.security.spec.ECGenParameterSpec;


/**
 * 加密工具类 - 提供哈希、签名和密钥管理功能
 */

public class CryptoUtil {


    public static String publicKeyToAddress(PublicKey publicKey) {
        return bytesToHex(publicKey.getEncoded());
    }

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
         * 应用ECDSA签名 - 对原始数据进行签名
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
         * 验证ECDSA签名
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


        // 公钥转地址（简化版）
        public static String publicKeyToAddress(PublicKey publicKey) {
            // 实际中需要进行SHA-256和RIPEMD-160哈希
            return bytesToHex(publicKey.getEncoded());
        }
    }





    /**
     * 应用SHA256哈希
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
     * 应用RIPEMD160哈希  HASH160
     */
    public static byte[] applyRIPEMD160(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("RIPEMD160");
            return md.digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("RIPEMD160算法不可用", e);
        }
    }

    /**
     * 字节数组转十六进制字符串
     */
    public static String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }

    /**
     * 十六进制字符串转字节数组
     */
    public static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i+1), 16));
        }
        return data;
    }








}