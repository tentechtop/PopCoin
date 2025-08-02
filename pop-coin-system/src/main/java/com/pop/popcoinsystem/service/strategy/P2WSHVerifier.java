package com.pop.popcoinsystem.service.strategy;

import com.pop.popcoinsystem.data.script.ScriptPubKey;
import com.pop.popcoinsystem.data.script.ScriptSig;
import com.pop.popcoinsystem.data.transaction.TXInput;
import com.pop.popcoinsystem.data.transaction.Transaction;
import com.pop.popcoinsystem.data.transaction.UTXO;
import com.pop.popcoinsystem.service.BlockChainServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class P2WSHVerifier implements ScriptVerificationStrategy{

    @Lazy
    @Autowired
    private BlockChainServiceImpl blockChainService;

    @Override
    public boolean verify(Transaction tx, TXInput input, int inputIndex, UTXO utxo) {
        log.info("普通交易 验证P2SH");
        ScriptSig scriptSig = input.getScriptSig();//[签名数据] [赎回脚本]
        // 赎回脚本为多重签名脚本 [公钥1] [公钥2] [公钥3] 3 OP_CHECKMULTISIG，
        ScriptPubKey scriptPubKey = utxo.getScriptPubKey(); //OP_HASH160 <ScriptHash> OP_EQUAL
        //   # 从解锁脚本中提取赎回脚本
        //   # 计算赎回脚本的哈希值
        return true;
    }
}
