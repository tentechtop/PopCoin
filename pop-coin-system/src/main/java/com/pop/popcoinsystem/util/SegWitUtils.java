package com.pop.popcoinsystem.util;

import com.pop.popcoinsystem.data.enums.SigHashType;

import java.util.Arrays;

public class SegWitUtils {



    /**
     * 创建包含SigHashType的签名数据
     * @param signature 原始签名数据
     * @param sigHashType SigHashType
     * @return 包含SigHashType的签名数据
     */
    public static byte[] createSigHashType(byte[] signature, SigHashType sigHashType) {
        if (signature == null || signature.length == 0) {
            throw new IllegalArgumentException("无效的签名数据");
        }
        // 创建一个新的字节数组，将原始签名数据复制到新数组中
        byte[] signatureWithHashType = Arrays.copyOf(signature, signature.length + 1);
        // 将SigHashType的值添加到新数组的最后一个字节中
        signatureWithHashType[signatureWithHashType.length - 1] = sigHashType.getValue();
        return signatureWithHashType;
    }


    /**
     * 从签名中获取SigHashType
     * @param signature
     * @return
     */
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
