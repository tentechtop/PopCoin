package com.pop.popcoinsystem;

import com.pop.popcoinsystem.data.script.Script;
import com.pop.popcoinsystem.data.script.ScriptPubKey;
import com.pop.popcoinsystem.data.script.ScriptSig;
import com.pop.popcoinsystem.data.script.AddressType;
import com.pop.popcoinsystem.util.CryptoUtil;
import lombok.extern.slf4j.Slf4j;

import java.net.SocketException;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

@Slf4j
public class P2SHTEST {
    public static void main(String[] args) throws SocketException {
        //接收方先创建赎回脚本（例如多重签名脚本、时间锁脚本等）；

        KeyPair keyPair1 = CryptoUtil.ECDSASigner.generateKeyPair();
        PrivateKey privateKey1 = keyPair1.getPrivate();
        PublicKey publicKey1 = keyPair1.getPublic();
        log.info("公钥1: {}", CryptoUtil.bytesToHex(publicKey1.getEncoded()));
        log.info("公钥1地址: {}", CryptoUtil.ECDSASigner.createP2PKHAddressByPK(publicKey1.getEncoded()));


        log.info("隔离见证地址P2WSH: {}", CryptoUtil.ECDSASigner.createP2WSHAddressByPK(publicKey1.getEncoded()));
        log.info("隔离见证地址P2WPKH: {}", CryptoUtil.ECDSASigner.createP2WPKHAddressByPK(publicKey1.getEncoded()));

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


        //创建赎回脚本
        PublicKey pubKey1 = publicKey1; // 公钥1
        PublicKey pubKey2 = publicKey2; // 公钥2
        PublicKey pubKey3 = publicKey3; // 公钥3
        PublicKey pubKey4 = publicKey4; // 公钥3
        List<byte[]> pubKeys = Arrays.asList(
                pubKey1.getEncoded(),
                pubKey2.getEncoded(),
                pubKey3.getEncoded(),
                pubKey4.getEncoded()
        );
        ScriptPubKey multisig = ScriptPubKey.createMultisig(4, pubKeys);//构造赎回脚本  （Redeem Script）：  OP_2 <公钥1> <公钥2> <公钥3> OP_3 OP_CHECKMULTISIG


        log.info("脚本格式:"+multisig.toScripString());


        byte[] bytes = CryptoUtil.applyRIPEMD160(CryptoUtil.applySHA256(multisig.serialize()));



        String P2SHAddress = CryptoUtil.ECDSASigner.createP2SHAddressByPK(multisig.serialize());
        log.info("脚本转地址: {}", P2SHAddress);
        log.info("验证地址是否有效: {}", CryptoUtil.ECDSASigner.isValidP2SHAddress(P2SHAddress));
        log.info("地址转哈希: {}", CryptoUtil.bytesToHex(Objects.requireNonNull(CryptoUtil.ECDSASigner.addressToP2SH(P2SHAddress))));
        log.info("脚本转哈希: {}", CryptoUtil.bytesToHex(bytes));
        AddressType addressType = CryptoUtil.ECDSASigner.getAddressType(P2SHAddress);
        log.info("地址类型: {}", addressType);

        byte[] txToSign = new byte[32]; //待签名的交易哈希（需按比特币规则生成待签名数据）
        Arrays.fill(txToSign, (byte)0x01);
        byte[] sig1 = CryptoUtil.ECDSASigner.applySignature(privateKey1, txToSign);
        byte[] sig2 = CryptoUtil.ECDSASigner.applySignature(privateKey2, txToSign);
        log.info("签名1: {}", CryptoUtil.bytesToHex(sig1));
        byte[] sig3 = CryptoUtil.ECDSASigner.applySignature(privateKey3, txToSign);
        byte[] sig4 = CryptoUtil.ECDSASigner.applySignature(privateKey4, txToSign);
        log.info("签名2: {}", CryptoUtil.bytesToHex(sig2));
        List<byte[]> signatures = Arrays.asList(sig1, sig2,sig3,sig4);


        //转账到 P2SH 地址  生成锁定脚本   由赎回脚本生成的锁定脚本
        ScriptPubKey p2shScriptPubKey = ScriptPubKey.createP2SH(bytes);//OP_HASH160 <脚本哈希> OP_EQUAL
        log.info("脚本格式:"+p2shScriptPubKey.toScripString());

        //花费 P2SH 地址中的资金
        //5. 花费 P2SH 地址中的资金
        //接收方（收款人）要花费这笔资金时，需提供以下内容并通过验证：
        //
        //赎回脚本：即步骤 1 中创建的原脚本（证明其哈希与锁定时的脚本哈希一致）；
        //满足赎回脚本条件的数据：例如多签场景中对应的私钥签名（证明有资格花费）。
        //
        //验证流程：
        //① 区块链节点先计算 “提供的赎回脚本” 的哈希，检查是否与锁定时的 “脚本哈希” 一致（确保脚本未被篡改）；
        //② 执行赎回脚本，检查提供的签名等数据是否满足其定义的条件（例如多签中签名数量是否达标）；
        //③ 若两步均通过，资金可被成功花费，交易生效。
        //接收方花费时 创建赎回脚本  multisig

        //验证你提供的赎回脚本是否和锁定的脚本一致


        ScriptPubKey myRedeem = multisig; //这是我提供的赎回脚本
        log.info("我提供的赎回脚本格式:"+myRedeem.toScripString());


        //由赎回脚本生成地址
        //由赎回脚本生成锁定脚本
        //由锁定脚本验证后 执行赎回脚本


        //创建解锁脚本
        ScriptSig p2shScriptSig = ScriptSig.createP2SH(signatures, myRedeem); //解锁脚本格式  OP_0 <签名1> <签名2> <赎回脚本>
        log.info("最终解锁脚本:"+p2shScriptSig.toScripString());
        boolean verify = p2shScriptPubKey.verify(p2shScriptSig, txToSign, 0, false);//和之前的锁定脚本一起验证
        if (verify) {
            log.info("P2SH脚本验证通过");
            //执行赎回脚本
            // 从解锁脚本（ScriptSig）中获取最后一个元素（赎回脚本的序列化数据）
            Script redeemScript2 = ScriptSig.getRedeemScript(p2shScriptSig);
            log.info("赎回脚本hash: {}", CryptoUtil.bytesToHex(CryptoUtil.applyRIPEMD160(CryptoUtil.applySHA256(redeemScript2.serialize()))));

            //由签名和赎回脚本生成解锁脚本

            log.info("执行的赎回脚本格式:"+redeemScript2.toScripString());
            boolean verify1 = myRedeem.verifyP2SH(signatures, txToSign, 0, false);
            if (verify1) {
                log.info("赎回脚本验证通过");
            } else {
                log.info("赎回脚本验证失败");
            }


        } else {
            log.info("P2SH脚本验证失败");
        }
        //执行赎回脚本

    }
}
