package com.pop.popcoinsystem;

import com.pop.popcoinsystem.data.enums.SigHashType;
import com.pop.popcoinsystem.data.transaction.TxSigType;
import com.pop.popcoinsystem.util.CryptoUtil;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Arrays;

import static com.pop.popcoinsystem.util.SegWitUtils.createSigHashType;

public class TestWitness {
    public static void main(String[] args) {
        KeyPair keyPair = CryptoUtil.ECDSASigner.generateKeyPair();
        PrivateKey privateKey = keyPair.getPrivate();
        PublicKey publicKey = keyPair.getPublic();

        byte[] txToSign = new byte[32];
        Arrays.fill(txToSign, (byte)0x01);



        // 伪代码示例：签名生成时附加 sigHashType
        byte[] signature = CryptoUtil.ECDSASigner.applySignature(privateKey, txToSign);
        byte[] signatureWithHashType = createSigHashType(signature, SigHashType.NONE);


        SigHashType sigHashType = extractSigHashType(signatureWithHashType);
        System.out.println("SigHashType: " + sigHashType);

        byte[] originalSignature = extractOriginalSignature(signatureWithHashType);


        boolean b = CryptoUtil.ECDSASigner.verifySignature(publicKey, txToSign, originalSignature);
        System.out.println("signature: " + b);


    }



    public static SigHashType extractSigHashType(byte[] signature) {
        if (signature == null || signature.length == 0) {
            throw new IllegalArgumentException("无效的签名数据");
        }
        byte sigHashByte = signature[signature.length - 1];
        // 根据字节值映射到对应的 SigHashType 枚举
        for (SigHashType type : SigHashType.values()) {
            if (type.getValue() == sigHashByte) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知的 SigHashType: 0x" + String.format("%02X", sigHashByte));
    }


    /**
     * 从包含 SigHashType 的签名数据中提取原始签名部分
     * @param signatureWithHashType 包含 SigHashType 的完整签名数据
     * @return 原始签名数据（不包含最后的 SigHashType 字节）
     */
    public static byte[] extractOriginalSignature(byte[] signatureWithHashType) {
        if (signatureWithHashType == null || signatureWithHashType.length < 1) {
            throw new IllegalArgumentException("无效的签名数据：长度不足");
        }
        // 提取原始签名部分（排除最后一个字节的 SigHashType）
        return Arrays.copyOf(signatureWithHashType, signatureWithHashType.length - 1);
    }
}
