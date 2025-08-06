package com.pop.popcoinsystem.data.script;

import com.pop.popcoinsystem.data.transaction.Transaction;
import com.pop.popcoinsystem.util.CryptoUtil;
import com.pop.popcoinsystem.util.SerializeUtils;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.List;

/**
 * 解锁脚本 - 用于提供解锁输出所需的签名和公钥
 * 用于证明交易发起人有权使用特定的加密货币。它在以下关键场景中发挥作用：
 */
@Slf4j
@Data
public class ScriptSig extends Script implements Serializable {

    //创建
    public ScriptSig(byte[] embeddedMessage) {
        super();
        addData(embeddedMessage);
    }


    public ScriptSig(byte[] signature, PublicKey publicKey) {
        super();
        // 添加签名
        addData(signature);
        // 添加公钥
        if (publicKey != null){
            addData(publicKey.getEncoded());
        }
        
    }

    public ScriptSig(byte[] signature,byte[] publicKey) {
        super();
        // 添加签名
        addData(signature);
        // 添加公钥哈希
        addData(publicKey);
    }



    public ScriptSig(byte[] signature, PublicKey publicKey, byte[] embeddedMessage) {
        super();
        // 添加签名
        addData(signature);
        // 添加公钥
        addData(publicKey.getEncoded());
        // 添加嵌入的文本（如果有）
    }



    public static ScriptSig createTempByScriptPubKey(ScriptPubKey scriptPubKey) {
        ScriptSig scriptSig = new ScriptSig();
        List<ScriptElement> elements = scriptPubKey.getElements();
        for (ScriptElement element : elements) {
            if (element.isOpCode()) {
                scriptSig.addOpCode(element.getOpCode());
            } else {
                scriptSig.addData(element.getData());
            }
        }
        return scriptSig;
    }




    // 从私钥生成解锁脚本
    public static ScriptSig fromPrivateKey(PrivateKey privateKey, PublicKey publicKey, Transaction transaction) {
        //对交易进行hash
        byte[] serialize = SerializeUtils.serialize(transaction);
        byte[] bytes = CryptoUtil.applySHA256(serialize);//这笔交易的 hash
        //对交易进行签名
        byte[] signature = CryptoUtil.ECDSASigner.applySignature(privateKey, bytes);
        ScriptSig scriptSig = new ScriptSig(signature, publicKey);
        return scriptSig;
    }

    //创建P2PKH
    public static ScriptSig createP2PKH(PrivateKey privateKey, PublicKey publicKey, Transaction transaction) {
        //对交易进行hash
        byte[] serialize = SerializeUtils.serialize(transaction);
        byte[] bytes = CryptoUtil.applySHA256(serialize);//这笔交易的 hash
        //对交易进行签名
        byte[] signature = CryptoUtil.ECDSASigner.applySignature(privateKey, bytes);
        ScriptSig scriptSig = new ScriptSig(signature, publicKey);
        return scriptSig;
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
/*        List<ScriptElement> elements = redeemScript.getElements();
        //将元素都添加到解锁脚本
        for (ScriptElement element : elements) {
            if (element.isOpCode()) {
                script.addOpCode(element.getOpCode());
            } else {
                script.addData(element.getData());
            }
        }*/
        return script;
    }

    public static ScriptSig createP2WSH(List<byte[]> signatures, Script redeemScript) {
        ScriptSig script = new ScriptSig();
        // 添加OP_0（BIP62要求）
        script.addOpCode(OP_0);
        // 添加所有签名
        for (byte[] signature : signatures) {
            script.addData(signature);
        }
        // 添加赎回脚本
        script.addData(redeemScript.serialize());
/*        List<ScriptElement> elements = redeemScript.getElements();
        //将元素都添加到解锁脚本
        for (ScriptElement element : elements) {
            if (element.isOpCode()) {
                script.addOpCode(element.getOpCode());
            } else {
                script.addData(element.getData());
            }
        }*/
        return script;
    }

    // 创建P2WPKH解锁脚本
    public static ScriptSig createP2WPKH(byte[] signature, byte[] publicKey) {
        ScriptSig script = new ScriptSig();
        // 添加OP_0（BIP62要求）
        script.addOpCode(OP_0);
        // 添加签名
        script.addData(signature);
        // 添加公钥哈希
        script.addData(publicKey);
        return script;
    }



    // 创建P2WSH解锁脚本



    //提取赎回脚本
    public static Script getRedeemScript(Script script) {
        List<ScriptElement> elements = script.getElements(); // 获取脚本元素列表
        // 校验元素列表是否为空，且至少包含赎回脚本（最后一个元素）
        if (elements.isEmpty()) {
            return null;
        }

        //获取最后一个元素
        ScriptElement lastElement = elements.get(script.getElements().size() - 1);
        log.info("lastElement: {}", lastElement.getData());
        //提取赎回脚本
        if (lastElement.isOpCode()) { // 最后一个元素是操作码，不符合P2SH结构
            return null;
        }
        if (lastElement.getData() != null) {
            byte[] data = lastElement.getData();
            return Script.parse(data);
        }else {
            return null;
        }
    }

    // 私有构造函数
    public ScriptSig() {
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


    public ScriptSig copy() {
        ScriptSig scriptSig = new ScriptSig();
        List<ScriptElement> elements1 = this.getElements();
        for (ScriptElement element : elements1) {
            if (element.isOpCode()) {
                scriptSig.addOpCode(element.getOpCode());
            } else {
                scriptSig.addData(element.getData());
            }
        }
        return scriptSig;
    }
}