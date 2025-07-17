package com.pop.popcoinsystem;

import com.pop.popcoinsystem.data.script.Script;
import com.pop.popcoinsystem.data.script.ScriptPubKey;
import com.pop.popcoinsystem.data.script.ScriptSig;
import com.pop.popcoinsystem.data.transaction.TXInput;
import com.pop.popcoinsystem.data.transaction.TXOutput;
import com.pop.popcoinsystem.data.transaction.Transaction;
import com.pop.popcoinsystem.data.transaction.TxSigType;
import com.pop.popcoinsystem.util.CryptoUtil;
import lombok.extern.slf4j.Slf4j;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

@Slf4j
public class TestTx {
    public static void main(String[] args) {
        KeyPair keyPair = CryptoUtil.ECDSASigner.generateKeyPair();
        PrivateKey privateKey = keyPair.getPrivate();
        PublicKey publicKey = keyPair.getPublic();

        KeyPair keyPair1 = CryptoUtil.ECDSASigner.generateKeyPair();
        PrivateKey privateKey1 = keyPair1.getPrivate();
        PublicKey publicKey1 = keyPair1.getPublic();

        KeyPair keyPair2 = CryptoUtil.ECDSASigner.generateKeyPair();
        PrivateKey privateKey2 = keyPair2.getPrivate();
        PublicKey publicKey2 = keyPair2.getPublic();

/*        Transaction transaction = new Transaction();
        transaction.setVersion(1);
        transaction.setSize(1);
        transaction.setWeight(1);
        transaction.setLockTime(System.currentTimeMillis());
        ArrayList<TXInput> txInputs = new ArrayList<>();
        TXInput txInput = new TXInput();
        txInputs.add(txInput);
        ArrayList<TXOutput> txOutputs = new ArrayList<>();
        TXOutput txOutput = new TXOutput();
        txOutputs.add(txOutput);
        transaction.setInputs(txInputs);
        transaction.setTxId();*/



/*        log.info("公钥转地址: {}", s);
        log.info("验证地址是否有效: {}", CryptoUtil.ECDSASigner.isValidP2PKHAddress(s));*/




        byte[] txToSign = new byte[32]; //待签名的交易哈希（需按比特币规则生成待签名数据）
        Arrays.fill(txToSign, (byte)0x01);

/*        byte[] signature = CryptoUtil.ECDSASigner.applySignature(privateKey, txToSign);
        ScriptSig scriptSig = new ScriptSig(signature, publicKey);
        boolean isValid = scriptPubKey.verify(scriptSig, txToSign, 0, false);
        System.out.println("脚本验证结果: " + (isValid ? "有效" : "无效"));*/


        //转P2SH
 /*       String P2SHAddress = CryptoUtil.ECDSASigner.createP2SHAddressByPK(publicKey.getEncoded());
        log.info("公钥转地址: {}", P2SHAddress);
        log.info("验证地址是否有效: {}", CryptoUtil.ECDSASigner.isValidP2SHAddress(P2SHAddress));
        log.info("公钥转hash: {}", CryptoUtil.bytesToHex(CryptoUtil.ECDSASigner.createP2SHByPK(publicKey.getEncoded())));
        log.info("地址转哈希: {}", CryptoUtil.bytesToHex(Objects.requireNonNull(CryptoUtil.ECDSASigner.addressToP2SH(P2SHAddress))));
        TxSigType addressType = CryptoUtil.ECDSASigner.getAddressType(P2SHAddress);
        log.info("地址类型: {}", addressType);
        //要使用 P2SH（Pay-to-Script-Hash，支付到脚本哈希）类型的脚本，需遵循 “创建赎回脚本→生成 P2SH 锁定脚本→解锁时提供签名和赎回脚本” 的流程。
        //核心概念
        //赎回脚本（Redeem Script）：实际定义 “如何花费资金” 的逻辑（如多重签名、条件支付等），是 P2SH 的核心逻辑脚本。
        //P2SH 锁定脚本（ScriptPubKey）：由赎回脚本的哈希构成，格式为 OP_HASH160 <赎回脚本哈希> OP_EQUAL，用于锁定资金。
        //P2SH 解锁脚本（ScriptSig）：花费时需提供 “满足赎回脚本的签名” 和 “赎回脚本本身”，用于解锁资金。

        //步骤 1：创建赎回脚本（Redeem Script）//赎回脚本是定义资金花费条件的核心脚本（如多重签名、时间锁定等）。以 “2-of-3 多重签名” 为例（需要 3 个公钥中的 2 个签名才能花费）：

        PublicKey pubKey1 = publicKey; // 公钥1
        PublicKey pubKey2 = publicKey1; // 公钥2
        PublicKey pubKey3 = publicKey2; // 公钥3
        List<byte[]> pubKeys = Arrays.asList(
                pubKey1.getEncoded(),
                pubKey2.getEncoded(),
                pubKey3.getEncoded()
        );

        // 2. 创建2-of-3多重签名赎回脚本（需2个签名）
        Script redeemScript = ScriptPubKey.createMultisig(2, pubKeys);
        // 脚本结构：OP_2 <pubKey1> <pubKey2> <pubKey3> OP_3 OP_CHECKMULTISIG


        //步骤 2：生成 P2SH 锁定脚本（ScriptPubKey）
        //P2SH 锁定脚本通过赎回脚本的哈希锁定资金，步骤如下：
        // 1. 计算赎回脚本的哈希（先序列化赎回脚本，再做SHA-256+RIPEMD160）
        byte[] redeemScriptBytes = redeemScript.serialize(); // 序列化赎回脚本
        byte[] scriptHash = CryptoUtil.ECDSASigner.publicKeyHash256And160Byte(redeemScriptBytes);
        // 等价于：RIPEMD160(SHA256(赎回脚本字节))
        // 2. 创建P2SH锁定脚本（ScriptPubKey）
        ScriptPubKey p2shScriptPubKey = ScriptPubKey.createP2SH(scriptHash);
        // 脚本结构：OP_HASH160 <scriptHash> OP_EQUAL

        //步骤 3：生成 P2SH 解锁脚本（ScriptSig）
        //当需要花费 P2SH 锁定的资金时，需提供满足赎回脚本的签名和赎回脚本本身，生成解锁脚本：






        byte[] sig1 = CryptoUtil.ECDSASigner.applySignature(privateKey, txToSign);
        byte[] sig2 = CryptoUtil.ECDSASigner.applySignature(privateKey1, txToSign);
        List<byte[]> signatures = Arrays.asList(sig1, sig2);

        ScriptSig p2shScriptSig = ScriptSig.createP2SH(signatures, redeemScript);

        // 1. 组合解锁脚本和锁定脚本，执行验证
        boolean isValid1 = p2shScriptPubKey.verify(p2shScriptSig, txToSign, 0, false);

        // 2. 验证结果
        if (isValid1) {
            System.out.println("P2SH脚本验证通过，资金可花费");
        } else {
            System.out.println("P2SH脚本验证失败");
        }
*/






        //转P2WPKH
/*        String P2WPKHAddress = CryptoUtil.ECDSASigner.createP2WPKHAddressByPK(publicKey.getEncoded());
        log.info("公钥转地址: {}", P2WPKHAddress);
        log.info("验证地址是否有效: {}", CryptoUtil.ECDSASigner.isValidP2WPKHAddress(P2WPKHAddress));
        log.info("公钥转hash: {}", CryptoUtil.bytesToHex(CryptoUtil.ECDSASigner.createP2WPKHByPK(publicKey.getEncoded())));
        log.info("地址转哈希: {}", CryptoUtil.bytesToHex(Objects.requireNonNull(CryptoUtil.ECDSASigner.addressToP2WPKH(P2WPKHAddress))));*/

        //转P2WSH
/*        String P2WSHAddress = CryptoUtil.ECDSASigner.createP2WSHAddressByPK(publicKey.getEncoded());
        log.info("公钥转地址: {}", P2WSHAddress);
        log.info("验证地址是否有效: {}", CryptoUtil.ECDSASigner.isValidP2WSHAddress(P2WSHAddress));
        log.info("公钥转hash: {}", CryptoUtil.bytesToHex(CryptoUtil.ECDSASigner.createP2WSHByPK(publicKey.getEncoded())));
        log.info("地址转哈希: {}", CryptoUtil.bytesToHex(Objects.requireNonNull(CryptoUtil.ECDSASigner.addressToP2WSH(P2WSHAddress))));*/








    }
}
