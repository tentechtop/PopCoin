package com.pop.popcoinsystem;

import com.pop.popcoinsystem.data.script.ScriptPubKey;
import com.pop.popcoinsystem.data.script.ScriptSig;
import com.pop.popcoinsystem.util.CryptoUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.FileOutputStream;
import java.io.IOException;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;

@Slf4j
public class testScript {
    public static void main(String[] args) {

        try {

            // 生成密钥对
            KeyPair keyPair = CryptoUtil.ECDSASigner.generateKeyPair();
            PrivateKey privateKey = keyPair.getPrivate();
            PublicKey publicKey = keyPair.getPublic();
            log.info("公钥: {}", CryptoUtil.bytesToHex(publicKey.getEncoded()));
            log.info("私钥: {}", CryptoUtil.bytesToHex(privateKey.getEncoded()));


            // 1. 安全存储私钥（加密存储）
            savePrivateKey(privateKey, "private_key.pem");
            // 2. 导出公钥（可公开）
            String publicKeyPEM = exportPublicKey(publicKey);
            System.out.println("Public Key (PEM):\n" + publicKeyPEM);
            // 3. 将公钥保存到文件
            savePublicKey(publicKey, "public_key.pem");
            //再取出来看看还能用吗



            // 创建一个交易的输出
            byte[] txToSign = new byte[32];
            Arrays.fill(txToSign, (byte)0x01);
            System.out.println("交易数据Hex: " + CryptoUtil.bytesToHex(txToSign));



            //为这个交易输出创建一个锁定脚本
            ScriptPubKey scriptPubKey = ScriptPubKey.createP2PKH(publicKey.getEncoded());
            System.out.println("P2PKH锁定脚本 (hex): " + CryptoUtil.bytesToHex(scriptPubKey.serialize()));
            System.out.println("脚本类型: " + scriptPubKey.getType());



            // 4. 创建解锁脚本 (ScriptSig)
            // 对交易进行签名
            byte[] signature = CryptoUtil.ECDSASigner.applySignature(privateKey, txToSign);
            System.out.println("签名Hex: " + CryptoUtil.bytesToHex(signature));


            // 创建解锁脚本
            ScriptSig scriptSig = new ScriptSig(signature, publicKey);
            // 5. 验证脚本
            boolean isValid = scriptPubKey.verify(scriptSig, txToSign, 0, false);
            System.out.println("脚本验证结果: " + (isValid ? "有效" : "无效"));


/*            // 6. 验证一个无效的签名
            byte[] invalidSignature = Arrays.copyOf(signature, signature.length);
            invalidSignature[0] ^= 0xFF; // 修改签名使其无效
            ScriptSig invalidScriptSig = new ScriptSig(invalidSignature, publicKey);//解锁脚本
            boolean isInvalid = scriptPubKey.verify(invalidScriptSig, txToSign, 0, false);
            System.out.println("无效脚本验证结果: " + (isInvalid ? "有效" : "无效"));*/













        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // 辅助方法：连接字节数组
    private static byte[] concatArrays(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }



    // 待签名的数据
/*            String message = "Hello, ECDSA!";
            byte[] data = message.getBytes();

            // 签名
            byte[] signature = CryptoUtil.ECDSASigner.applySignature(privateKey, data);

            // 验证签名
            boolean isValid =  CryptoUtil.ECDSASigner.verifySignature(publicKey, data, signature);
            System.out.println("签名验证结果: " + isValid);*/




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
