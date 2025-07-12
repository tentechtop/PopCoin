package com.pop.popcoinsystem.data.script;

import com.pop.popcoinsystem.data.transaction.Transaction;
import com.pop.popcoinsystem.util.CryptoUtil;
import com.pop.popcoinsystem.util.SerializeUtils;
import lombok.Data;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.List;

/**
 * 解锁脚本 - 用于提供解锁输出所需的签名和公钥
 * 用于证明交易发起人有权使用特定的加密货币。它在以下关键场景中发挥作用：
 */
@Data
public class ScriptSig extends Script {

    //输入脚本（ScriptSig）的构成要素有数字签名和公钥
    //谁能提供签名和公钥并通过验证 谁就能拥有未花费输出


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
    public static ScriptSig fromPrivateKey(PrivateKey privateKey, PublicKey publicKey, Transaction transaction) {
        //对交易进行hash
        byte[] serialize = SerializeUtils.serialize(transaction);
        byte[] bytes = CryptoUtil.applySHA256(serialize);//这笔交易的 hash
        //对交易进行签名
        byte[] signature = CryptoUtil.ECDSASigner.applySignature(privateKey, bytes);
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