package com.pop.popcoinsystem;

import com.pop.popcoinsystem.data.script.Script;
import com.pop.popcoinsystem.data.script.ScriptPubKey;
import com.pop.popcoinsystem.data.script.ScriptSig;

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
public class mtTx {
    public static void main(String[] args) {

        KeyPair keyPair1 = CryptoUtil.ECDSASigner.generateKeyPair();
        PrivateKey privateKey1 = keyPair1.getPrivate();
        PublicKey publicKey1 = keyPair1.getPublic();
        log.info("公钥1: {}", CryptoUtil.bytesToHex(publicKey1.getEncoded()));

        KeyPair keyPair2 = CryptoUtil.ECDSASigner.generateKeyPair();
        PrivateKey privateKey2 = keyPair2.getPrivate();
        PublicKey publicKey2 = keyPair2.getPublic();
        log.info("公钥2: {}", CryptoUtil.bytesToHex(publicKey2.getEncoded()));

        KeyPair keyPair3 = CryptoUtil.ECDSASigner.generateKeyPair();
        PrivateKey privateKey3 = keyPair3.getPrivate();
        PublicKey publicKey3 = keyPair3.getPublic();
        log.info("公钥3: {}", CryptoUtil.bytesToHex(publicKey3.getEncoded()));

        KeyPair keyPair4 = CryptoUtil.ECDSASigner.generateKeyPair();
        PrivateKey privateKey4 = keyPair4.getPrivate();
        PublicKey publicKey4 = keyPair4.getPublic();


        byte[] txToSign = new byte[32]; //待签名的交易哈希（需按比特币规则生成待签名数据）
        Arrays.fill(txToSign, (byte)0x01);


        //转P2SH
/*
        String P2SHAddress = CryptoUtil.ECDSASigner.createP2SHAddressByPK(publicKey1.getEncoded());
        log.info("公钥转地址: {}", P2SHAddress);
        log.info("验证地址是否有效: {}", CryptoUtil.ECDSASigner.isValidP2SHAddress(P2SHAddress));
        log.info("公钥转hash: {}", CryptoUtil.bytesToHex(CryptoUtil.ECDSASigner.createP2SHByPK(publicKey1.getEncoded())));
        log.info("地址转哈希: {}", CryptoUtil.bytesToHex(Objects.requireNonNull(CryptoUtil.ECDSASigner.addressToP2SH(P2SHAddress))));
        TxSigType addressType = CryptoUtil.ECDSASigner.getAddressType(P2SHAddress);
        log.info("地址类型: {}", addressType);

        PublicKey pubKey1 = publicKey1; // 公钥1
        PublicKey pubKey2 = publicKey2; // 公钥2
        PublicKey pubKey3 = publicKey3; // 公钥3
        List<byte[]> pubKeys = Arrays.asList(
                pubKey1.getEncoded(),
                pubKey2.getEncoded(),
                pubKey3.getEncoded()
        );

        // 2. 创建2-of-3多重签名赎回脚本（需2个签名）
        Script redeemScript = ScriptPubKey.createMultisig(2, pubKeys);
        //打印赎回脚本
        log.info("赎回脚本: {}", redeemScript);


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


        byte[] sig1 = CryptoUtil.ECDSASigner.applySignature(privateKey1, txToSign);
        log.info("签名1: {}", CryptoUtil.bytesToHex(sig1));
        byte[] sig2 = CryptoUtil.ECDSASigner.applySignature(privateKey2, txToSign);
        log.info("签名2: {}", CryptoUtil.bytesToHex(sig2));
        List<byte[]> signatures = Arrays.asList(sig1, sig2);

        ScriptSig p2shScriptSig = ScriptSig.createP2SH(signatures, redeemScript);

        // 1. 组合解锁脚本和锁定脚本，执行验证
        boolean isValid1 = p2shScriptPubKey.verify(p2shScriptSig, txToSign, 0, false);

        // 2. 验证结果
        if (isValid1) {
            log.info("P2SH脚本验证通过，可以赎回脚本");
        } else {
            log.info("P2SH脚本验证失败");
        }

        if (isValid1) {
            // 从ScriptSig中提取赎回脚本（通常是最后一个元素）
            Script redeemScript2 = ScriptSig.getRedeemScript(p2shScriptSig);
            log.info("赎回脚本: {}", redeemScript2);


            // 3. 第二阶段：执行赎回脚本（验证多重签名等逻辑）
            // 准备初始栈：P2SH解锁脚本中除了赎回脚本外的其他元素（OP_0、签名）
            List<byte[]> initialStack = new ArrayList<>();
            for (int i = 0; i < Objects.requireNonNull(redeemScript2).getElements().size() - 1; i++) {
                Script.ScriptElement element = redeemScript2.getElements().get(i);
                if (!element.isOpCode()) { // 签名是数据元素，压入栈
                    initialStack.add(element.getData());
                }
            }

            // 执行赎回脚本（例如多重签名的OP_CHECKMULTISIG）
            boolean redeemValid = redeemScript2.execute(initialStack, txToSign, 0, false);
            if (redeemValid) {
                System.out.println("P2SH完整验证通过，资金可花费");
            } else {
                System.out.println("赎回脚本验证失败");
            }


            // 创建合并后的脚本：ScriptSig（不含赎回脚本） + 赎回脚本
            */
/*Script combinedScript = Script.combine(p2shScriptSig, redeemScript2);*//*


            // 执行合并后的脚本验证
       */
/*     multisigValid = combinedScript.execute(txToSign);*//*

        }
*/






    }
}
