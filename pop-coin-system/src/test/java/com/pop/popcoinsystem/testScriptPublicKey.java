package com.pop.popcoinsystem;

import com.pop.popcoinsystem.util.CryptoUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

@Slf4j
public class testScriptPublicKey {
    public static void main(String[] args) {
        try {

            //签名, 公钥, OP_DUP, OP_HASH160, <pubKeyHash>, OP_EQUALVERIFY, OP_CHECKSIG

            KeyPair keyPair = CryptoUtil.ECDSASigner.generateKeyPair();
            PrivateKey privateKey = keyPair.getPrivate();
            PublicKey publicKey = keyPair.getPublic();
            log.info("公钥: {}", CryptoUtil.bytesToHex(publicKey.getEncoded()));
            log.info("私钥: {}", CryptoUtil.bytesToHex(privateKey.getEncoded()));

            String s = CryptoUtil.bytesToHex(publicKey.getEncoded());

            savePrivateKey(privateKey, "private_key.pem");
            savePublicKey(publicKey, "public_key.pem");



            // 从文件读取密钥对
            PrivateKey loadedPrivateKey = loadPrivateKey("private_key.pem");
            PublicKey loadedPublicKey = loadPublicKey("public_key.pem");
            log.info("公钥: {}", CryptoUtil.bytesToHex(loadedPublicKey.getEncoded()));
            log.info("私钥: {}", CryptoUtil.bytesToHex(loadedPrivateKey.getEncoded()));

            // 验证读取的密钥对是否有效
          /*  verifyKeyPair(originalKeyPair, loadedPrivateKey, loadedPublicKey);*/


        } catch (Exception e) {
            e.printStackTrace();
        }
    }





    // 生成并保存密钥对
    private static KeyPair generateAndSaveKeyPair() throws Exception {
        // 生成 ECDSA 密钥对
        KeyPair keyPair = CryptoUtil.ECDSASigner.generateKeyPair();
        PrivateKey privateKey = keyPair.getPrivate();
        PublicKey publicKey = keyPair.getPublic();

        // 保存私钥到文件（以 PKCS#8 格式）
        PKCS8EncodedKeySpec privateKeySpec = new PKCS8EncodedKeySpec(privateKey.getEncoded());
        try (FileOutputStream fos = new FileOutputStream("private_key.pem")) {
            fos.write(privateKeySpec.getEncoded());
        }
        System.out.println("Private key saved to: private_key.pem");

        // 保存公钥到文件（以 X.509 格式）
        X509EncodedKeySpec publicKeySpec = new X509EncodedKeySpec(publicKey.getEncoded());
        try (FileOutputStream fos = new FileOutputStream("public_key.pem")) {
            fos.write(publicKeySpec.getEncoded());
        }
        System.out.println("Public key saved to: public_key.pem");

        return keyPair;
    }

    // 从文件加载私钥
    private static PrivateKey loadPrivateKey(String filename) throws Exception {
        // 读取私钥文件内容
        byte[] keyBytes;
        try (FileInputStream fis = new FileInputStream(filename);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                bos.write(buffer, 0, bytesRead);
            }
            keyBytes = bos.toByteArray();
        }

        // 恢复私钥
        KeyFactory keyFactory = KeyFactory.getInstance("EC");
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(keyBytes);
        return keyFactory.generatePrivate(keySpec);
    }

    // 从文件加载公钥
    private static PublicKey loadPublicKey(String filename) throws Exception {
        // 读取公钥文件内容
        byte[] keyBytes;
        try (FileInputStream fis = new FileInputStream(filename);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                bos.write(buffer, 0, bytesRead);
            }
            keyBytes = bos.toByteArray();
        }

        // 恢复公钥
        KeyFactory keyFactory = KeyFactory.getInstance("EC");
        X509EncodedKeySpec keySpec = new X509EncodedKeySpec(keyBytes);
        return keyFactory.generatePublic(keySpec);
    }

    // 验证密钥对的有效性
    private static void verifyKeyPair(KeyPair originalKeyPair, PrivateKey loadedPrivateKey, PublicKey loadedPublicKey) throws Exception {
        // 原始密钥对签名
        String message = "Hello, this is a test message!";
        byte[] signature = signMessage(originalKeyPair.getPrivate(), message);

        // 使用加载的公钥验证签名
        boolean originalKeyValid = verifySignature(originalKeyPair.getPublic(), message, signature);
        boolean loadedKeyValid = verifySignature(loadedPublicKey, message, signature);

        // 使用加载的私钥生成新签名
        byte[] newSignature = signMessage(loadedPrivateKey, message);
        boolean newSignatureValid = verifySignature(loadedPublicKey, message, newSignature);

        System.out.println("\n=== 密钥对验证结果 ===");
        System.out.println("原始公钥验证原始签名: " + originalKeyValid);
        System.out.println("加载公钥验证原始签名: " + loadedKeyValid);
        System.out.println("加载公钥验证加载私钥生成的签名: " + newSignatureValid);
    }

    // 使用私钥签名消息
    private static byte[] signMessage(PrivateKey privateKey, String message) throws Exception {
        Signature ecdsaSign = Signature.getInstance("SHA256withECDSA");
        ecdsaSign.initSign(privateKey);
        ecdsaSign.update(message.getBytes());
        return ecdsaSign.sign();
    }

    // 使用公钥验证签名
    private static boolean verifySignature(PublicKey publicKey, String message, byte[] signature) throws Exception {
        Signature ecdsaVerify = Signature.getInstance("SHA256withECDSA");
        ecdsaVerify.initVerify(publicKey);
        ecdsaVerify.update(message.getBytes());
        return ecdsaVerify.verify(signature);
    }









    // 保存私钥到文件（以 PKCS#8 格式）
    private static void savePrivateKey(PrivateKey privateKey, String filename) throws IOException {
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(privateKey.getEncoded());
        try (FileOutputStream fos = new FileOutputStream(filename)) {
            fos.write(keySpec.getEncoded());
        }
        System.out.println("Private key saved to: " + filename);
    }

    // 导出公钥为 PEM 格式字符串
    private static String exportPublicKey(PublicKey publicKey) {
        X509EncodedKeySpec keySpec = new X509EncodedKeySpec(publicKey.getEncoded());
        return Base64.getEncoder().encodeToString(keySpec.getEncoded());
    }

    // 保存公钥到文件（以 X.509 格式）
    private static void savePublicKey(PublicKey publicKey, String filename) throws IOException {
        X509EncodedKeySpec keySpec = new X509EncodedKeySpec(publicKey.getEncoded());
        try (FileOutputStream fos = new FileOutputStream(filename)) {
            fos.write(keySpec.getEncoded());
        }
        System.out.println("PublicKey saved to: " + filename);
    }





}
