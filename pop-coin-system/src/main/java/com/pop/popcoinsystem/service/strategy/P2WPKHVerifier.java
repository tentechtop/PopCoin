package com.pop.popcoinsystem.service.strategy;

import com.pop.popcoinsystem.data.enums.SigHashType;
import com.pop.popcoinsystem.data.script.ScriptPubKey;
import com.pop.popcoinsystem.data.script.ScriptSig;
import com.pop.popcoinsystem.data.transaction.TXInput;
import com.pop.popcoinsystem.data.transaction.Transaction;
import com.pop.popcoinsystem.data.transaction.UTXO;
import com.pop.popcoinsystem.data.transaction.Witness;
import com.pop.popcoinsystem.service.BlockChainServiceImpl;
import com.pop.popcoinsystem.util.CryptoUtil;
import com.pop.popcoinsystem.util.SegWitUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.IntStream;

@Slf4j
@Service
public class P2WPKHVerifier implements ScriptVerificationStrategy{
    @Lazy
    @Autowired
    private BlockChainServiceImpl blockChainService;

    @Override
    public boolean verify(Transaction tx, TXInput input, int inputIndex, UTXO utxo) {
        // 1. 验证见证数据结构：P2WPKH见证应包含2个元素（签名 + 公钥）
        List<TXInput> inputs = tx.getInputs();//顺序不变获取 隔离见证输入
        List<Witness> witnesses = tx.getWitnesses();
        List<TXInput> segWitInputs = inputs.stream()
                .filter(data -> data.getScriptSig() == null)  // 核心条件：scriptSig 为空
                .toList();

        int realIndex = IntStream.range(0, segWitInputs.size())
                .filter(index -> {
                    TXInput current = segWitInputs.get(index);
                    // 比较txId和vout（假设TXInput有getTxId()和getVout()方法）
                    return current.getTxId().equals(input.getTxId())
                            && current.getVout() == input.getVout();
                })
                .findFirst() // 找到第一个匹配的索引（唯一，因为输入唯一）
                .orElse(-1);// 未找到返回-1（理论上不会出现，因为input来自segWitInputs的源列表）

        log.info("真实见证位置:{}", realIndex);

        Witness witness = witnesses.get(realIndex);
        if (witness.getSize() != 2) {
            log.error("P2WPKH见证数据元素数量错误，预期2个，实际{}个", witness.getSize());
            return false;
        }
        // 2. 提取见证数据中的签名和公钥
        byte[] signature = witness.getItem(0); // 第一个元素为签名（包含SIGHASH类型）
        byte[] publicKey = witness.getItem(1); // 第二个元素为公钥Hash

        SigHashType sigHashType = SegWitUtils.extractSigHashType(signature);//获取签名的SIGHASH类型
        log.info("P2WPKH签名SIGHASH类型:{}", sigHashType);
        byte[] realSignature = SegWitUtils.extractOriginalSignature(signature);
        log.info("P2WPKH签名数据:{}", CryptoUtil.bytesToHex(realSignature));
        //这里应该根据签名类型 构建对应的 交易签名数据
        byte[] txHash = blockChainService.createWitnessSignatureHash(
                tx,
                inputIndex,
                utxo.getValue(),
                sigHashType
        );
        log.info("P2WPKH验证时需要签名的交易数据:{}", CryptoUtil.bytesToHex(txHash));


        if (signature.length == 0) {
            log.error("P2WPKH签名数据为空");
            return false;
        }
        if (publicKey == null || publicKey.length == 0) {
            log.error("P2WPKH公钥数据为空");
            return false;
        }
        ScriptPubKey scriptPubKey = utxo.getScriptPubKey();

        // 3. 解析P2WPKH锁定脚本：格式为 [OP_0 (0x00) + 20字节公钥哈希]
        byte[] scriptBytes = scriptPubKey.getScriptBytes();
        if (scriptBytes.length != 22) { // 1字节OP_0 + 20字节哈希 + 1字节结尾？需根据实际脚本结构调整
            log.error("P2WPKH锁定脚本长度错误，预期22字节，实际{}字节", scriptBytes.length);
            throw new IllegalArgumentException("P2WPKH脚本长度必须为22字节，实际为" + scriptBytes.length);
        }
        ScriptSig scriptSig = new ScriptSig(signature, publicKey);
        boolean verify = scriptPubKey.verify(scriptSig, txHash, input.getVout(), false);
        if (!verify) {
            log.error("P2WPKH输入的签名无效");
            return false;
        }
        log.info("P2WPKH脚本验证成功");
        return true;

    }
}
