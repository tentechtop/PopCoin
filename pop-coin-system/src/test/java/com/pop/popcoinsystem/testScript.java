package com.pop.popcoinsystem;

import com.pop.popcoinsystem.data.script.ScriptPubKey;
import com.pop.popcoinsystem.data.script.ScriptSig;
import com.pop.popcoinsystem.util.CryptoUtil;
import lombok.extern.slf4j.Slf4j;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Arrays;

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


            // 创建一个交易的输出
            byte[] txToSign = new byte[32];
            Arrays.fill(txToSign, (byte)0x01);
            System.out.println("交易数据Hex: " + CryptoUtil.bytesToHex(txToSign));
            //为这个交易输出创建一个锁定脚本
            ScriptPubKey scriptPubKey = ScriptPubKey.createP2PKH(publicKey.getEncoded());
            System.out.println("P2PKH锁定脚本 (hex): " + scriptPubKey.getHex());
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


            // 6. 验证一个无效的签名
            byte[] invalidSignature = Arrays.copyOf(signature, signature.length);
            invalidSignature[0] ^= 0xFF; // 修改签名使其无效

            ScriptSig invalidScriptSig = new ScriptSig(invalidSignature, publicKey);
            boolean isInvalid = scriptPubKey.verify(invalidScriptSig, txToSign, 0, false);
            System.out.println("无效脚本验证结果: " + (isInvalid ? "有效" : "无效"));





















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


}
