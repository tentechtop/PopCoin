package com.pop.popcoinsystem.service.strategy;

import com.pop.popcoinsystem.data.enums.SigHashType;
import com.pop.popcoinsystem.data.script.Script;
import com.pop.popcoinsystem.data.script.ScriptPubKey;
import com.pop.popcoinsystem.data.script.ScriptSig;
import com.pop.popcoinsystem.data.transaction.TXInput;
import com.pop.popcoinsystem.data.transaction.Transaction;
import com.pop.popcoinsystem.data.transaction.UTXO;
import com.pop.popcoinsystem.service.BlockChainService;
import com.pop.popcoinsystem.util.CryptoUtil;
import com.pop.popcoinsystem.util.SegWitUtils;
import com.pop.popcoinsystem.util.SerializeUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class P2PKHVerifier implements ScriptVerificationStrategy{

    @Autowired
    private BlockChainService blockChainService;

    @Override
    public boolean verify(Transaction tx, TXInput input, int inputIndex, UTXO utxo) {
        byte[] serialize = SerializeUtils.serialize(tx);
        log.info("验证时交易序列化数据: {}", CryptoUtil.bytesToHex(CryptoUtil.applySHA256(serialize)) );


        ScriptPubKey scriptPubKey = utxo.getScriptPubKey();
        log.info("验证P2PKH{}",scriptPubKey.toScripString());
        //从中获取 签名和公钥 witness  第一个数据是签名 第二个是公钥
        ScriptSig scriptSig = input.getScriptSig();//解锁脚本中有 签名和公钥  签名
        List<Script.ScriptElement> elements = scriptSig.getElements();
        byte[] signature = elements.get(0).getData();
        byte[] publicKey = elements.get(1).getData();

        SigHashType sigHashType = SegWitUtils.extractSigHashType(signature);//获取签名的SIGHASH类型
        log.info("签名SIGHASH类型:{}", sigHashType);
        byte[] realSignature = SegWitUtils.extractOriginalSignature(signature);
        log.info("原始签名数据:{}", CryptoUtil.bytesToHex(realSignature));
        //这里应该根据签名类型 构建对应的 交易签名数据

        byte[] txHash = blockChainService.createLegacySignatureHash(
                tx,
                inputIndex,
                utxo,
                sigHashType
        );
        log.info("验证时需要签名的交易数据:{}", CryptoUtil.bytesToHex(txHash));

        ScriptSig  realScriptSig = new ScriptSig(realSignature, publicKey);
        boolean verify = scriptPubKey.verify(realScriptSig, txHash, 0, false);
        if (!verify) {
            log.error("P2PKH输入的签名无效");
            return false;
        }
        return true;
    }
}
