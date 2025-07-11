package com.pop.popcoinsystem.data.script;

import com.pop.popcoinsystem.util.CryptoUtil;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.List;

/**
 * 解锁脚本 - 用于提供解锁输出所需的签名和公钥
 */
public class ScriptSig extends Script {

    // 公钥
    private PublicKey publicKey;
    // 签名
    private byte[] signature;

    // 标准构造函数
    public ScriptSig(byte[] signature, PublicKey publicKey) {
        super();
        this.signature = signature;
        this.publicKey = publicKey;
        // 添加签名
        addData(signature);
        // 添加公钥
        addData(publicKey.getEncoded());
    }

    // 从私钥生成解锁脚本
    public static ScriptSig fromPrivateKey(PrivateKey privateKey, PublicKey publicKey, byte[] txToSign, int inputIndex, ScriptPubKey scriptPubKey) {
        // 创建要签名的交易副本
        byte[] txCopy = Arrays.copyOf(txToSign, txToSign.length);

        // 修改交易的输入脚本
        // 这里需要根据scriptPubKey的类型来处理
        // 简化实现，实际中需要更复杂的处理

        // 对交易进行哈希
        byte[] hashToSign = new byte[32];
        Arrays.fill(hashToSign, (byte)0x01); // 简化实现，实际中需要正确计算哈希

        // 添加SIGHASH_ALL标记
        byte[] sigHashType = new byte[]{(byte)0x01};

        // 生成签名
        byte[] signature = CryptoUtil.ECDSASigner.applySignature(privateKey, concatArrays(hashToSign, sigHashType));

        // 添加SIGHASH_ALL标记到签名末尾
        signature = concatArrays(signature, sigHashType);

        return new ScriptSig(signature, publicKey);
    }

    // 创建P2SH解锁脚本
    public static ScriptSig createP2SH(List<byte[]> signatures, Script redeemScript) {
        ScriptSig script = new ScriptSig();

        // 添加OP_0（BIP62要求）
        script.addOpCode(OP_0);

        // 添加所有签名
        for (byte[] signature : signatures) {
            script.addData(signature);
        }

        // 添加赎回脚本
        script.addData(redeemScript.serialize());

        return script;
    }

    // 私有构造函数
    private ScriptSig() {
        super();
    }

    // 执行脚本验证
    public boolean evaluate(ScriptPubKey scriptPubKey, byte[] txToSign, int inputIndex, boolean isGenesisBlock) {
        // 组合解锁脚本和锁定脚本并执行
        return scriptPubKey.verify(this, txToSign, inputIndex, isGenesisBlock);
    }

    // 辅助方法：连接字节数组
    static byte[] concatArrays(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return Arrays.toString(result).getBytes();
    }





}