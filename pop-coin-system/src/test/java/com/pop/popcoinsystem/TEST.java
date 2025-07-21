package com.pop.popcoinsystem;

import com.pop.popcoinsystem.data.storage.POPStorage;
import com.pop.popcoinsystem.data.transaction.UTXO;
import com.pop.popcoinsystem.util.CryptoUtil;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Arrays;

@Slf4j
public class TEST {
    public static void main(String[] args) {

        KeyPair keyPairA = CryptoUtil.ECDSASigner.generateKeyPair();
        PrivateKey privateKey = keyPairA.getPrivate();
        PublicKey publicKey = keyPairA.getPublic();
        byte[] txToSign = new byte[32]; //待签名的交易哈希（需按比特币规则生成待签名数据）
        Arrays.fill(txToSign, (byte)0x01);


        String s = CryptoUtil.ECDSASigner.createP2PKHAddressByPK(publicKey.getEncoded());
        UTXO utxo = new UTXO();
        utxo.setAddress(s);
        utxo.setValue(BigDecimal.valueOf(1000));
        utxo.setTxId(txToSign);
        utxo.setVout(0);

        POPStorage instance = POPStorage.getInstance();
        instance.addUtxo(utxo);

        instance.getUtxosByAddress(s);
        log.info("{}", instance.getUtxosByAddress(s));

        instance.getUtxo(txToSign, 0);
        log.info("{}", instance.getUtxo(txToSign, 0));


        instance.getUtxoByKey("16906ae9b07db4392948a973a5a4daaf4d26f859a672fe003250abbb4abcc033:5");





    }
}
