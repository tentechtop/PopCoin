package com.pop.popcoinsystem;

import com.pop.popcoinsystem.data.script.ScriptPubKey;
import com.pop.popcoinsystem.data.script.ScriptSig;
import com.pop.popcoinsystem.data.transaction.TXInput;
import com.pop.popcoinsystem.data.transaction.TXOutput;
import com.pop.popcoinsystem.data.transaction.Transaction;
import com.pop.popcoinsystem.util.CryptoUtil;
import lombok.extern.slf4j.Slf4j;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Arrays;

@Slf4j
public class TestTx {
    public static void main(String[] args) {
        KeyPair keyPair = CryptoUtil.ECDSASigner.generateKeyPair();
        PrivateKey privateKey = keyPair.getPrivate();
        PublicKey publicKey = keyPair.getPublic();
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


        String s = CryptoUtil.ECDSASigner.publicKeyToAddress(publicKey);
        log.info("公钥转地址: {}", s);
        log.info("验证地址是否有效: {}", CryptoUtil.ECDSASigner.isValidAddress(s));
        byte[] bytes1 = CryptoUtil.ECDSASigner.addressToPublicKeyHashByte(s);

        ScriptPubKey scriptPubKey = ScriptPubKey.createP2PKHByPublicKeyHash(bytes1);
        byte[] txToSign = new byte[32];
        Arrays.fill(txToSign, (byte)0x01);
        byte[] signature = CryptoUtil.ECDSASigner.applySignature(privateKey, txToSign);

        ScriptSig scriptSig = new ScriptSig(signature, publicKey);

        boolean isValid = scriptPubKey.verify(scriptSig, txToSign, 0, false);
        System.out.println("脚本验证结果: " + (isValid ? "有效" : "无效"));


    }
}
