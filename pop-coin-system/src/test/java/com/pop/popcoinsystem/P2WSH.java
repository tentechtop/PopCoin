package com.pop.popcoinsystem;

import com.pop.popcoinsystem.data.script.Script;
import com.pop.popcoinsystem.data.script.ScriptPubKey;
import com.pop.popcoinsystem.data.script.ScriptSig;
import com.pop.popcoinsystem.data.transaction.*;
import com.pop.popcoinsystem.util.CryptoUtil;
import lombok.extern.slf4j.Slf4j;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

@Slf4j
public class P2WSH {
    public static void main(String[] args) {

        //P2WSH（Pay to Witness Script Hash）是比特币中的一种高级交易类型，属于隔离见证（SegWit）的一部分。它结合了脚本哈希和隔离见证的优势，提供更高的隐私性和更低的交易费用。
        //核心概念
        //Witness
        //交易的签名数据（见证数据）与交易本身分离，存储在区块链的不同部分，减少了基础交易的大小。
        //Script Hash
        //先将复杂的赎回脚本（Redeem Script）进行哈希运算，只在链上公开哈希值，而不是完整脚本，提高了隐私性。
        //工作流程
        //创建赎回脚本
        //设计一个满足特定条件的脚本（如多重签名、时间锁等）。
        //计算哈希值
        //对赎回脚本进行 SHA-256 哈希，得到 scriptHash。
        //生成地址
        //将 scriptHash 转换为 Bech32 格式的地址（以 bc1q 开头）。
        //花费资金
        //在交易中提供原始赎回脚本和签名数据（见证数据）。

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


        PublicKey pubKey1 = publicKey1; // 公钥1
        PublicKey pubKey2 = publicKey2; // 公钥2
        PublicKey pubKey3 = publicKey3; // 公钥3
        List<byte[]> pubKeys = Arrays.asList(
                pubKey1.getEncoded(),
                pubKey2.getEncoded(),
                pubKey3.getEncoded()
        );
        ScriptPubKey redeemScript = ScriptPubKey.createMultisig(2, pubKeys);//构造赎回脚本  （Redeem Script）：  OP_2 <公钥1> <公钥2> <公钥3> OP_3 OP_CHECKMULTISIG

        String P2WSHAddress = CryptoUtil.ECDSASigner.createP2WSHAddressByPK(redeemScript.serialize());

        log.info("脚本转地址: {}", P2WSHAddress);
        log.info("验证地址是否有效: {}", CryptoUtil.ECDSASigner.isValidP2WSHAddress(P2WSHAddress));
        log.info("地址转哈希: {}", CryptoUtil.bytesToHex(Objects.requireNonNull(CryptoUtil.ECDSASigner.addressToP2WSH(P2WSHAddress))));
        log.info("脚本转哈希: {}", CryptoUtil.bytesToHex(CryptoUtil.applySHA256(redeemScript.serialize())));
        TxSigType addressType = CryptoUtil.ECDSASigner.getAddressType(P2WSHAddress);
        log.info("地址类型: {}", addressType);



        //必须是对交易的关键部分进行的签名
        byte[] txToSign = new byte[32]; //待签名的交易哈希（需按比特币规则生成待签名数据）
        Arrays.fill(txToSign, (byte)0x01);
        byte[] sig1 = CryptoUtil.ECDSASigner.applySignature(privateKey1, txToSign);
        byte[] sig2 = CryptoUtil.ECDSASigner.applySignature(privateKey2, txToSign);
        byte[] sig3 = CryptoUtil.ECDSASigner.applySignature(privateKey3, txToSign);
        byte[] sig4 = CryptoUtil.ECDSASigner.applySignature(privateKey4, txToSign);
        List<byte[]> signatures = Arrays.asList(sig1, sig2);


        byte[] bytes = CryptoUtil.applySHA256(redeemScript.serialize());
        ScriptPubKey p2wshScriptPubKey = ScriptPubKey.createP2WSH(bytes);
        log.info("脚本格式:"+p2wshScriptPubKey.toScripString());


        //每个输入的签名都是独立计算的。
        //SIGHASH_ALL（最常用）：签名哈希包含所有输入和所有输出，意味着签名者同意交易的全部内容（所有输入的花费和所有输出的分配）。
        //SIGHASH_NONE：签名哈希不包含输出（仅包含当前输入和其他输入），允许后续修改输出（但实际中很少用）。


        // 创建Witness数据
        Witness witness = new Witness();
        witness.addItem(new byte[0]);
        witness.addSignature(sig1);
        witness.addSignature(sig2);
        witness.addSignature(sig3);
        witness.addSignature(sig4);
        witness.setRedeemScript(redeemScript);


        //获取锁定脚本中的脚本哈希

        //用户提供的赎回脚本
        Script redeemScript2 = redeemScript;


















    }





}
