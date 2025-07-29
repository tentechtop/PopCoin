package com.pop.popcoinsystem.data.transaction.dto;

import com.pop.popcoinsystem.data.script.ScriptPubKey;
import com.pop.popcoinsystem.util.CryptoUtil;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class UTXODTO {

    /**
     * 交易Id的hash值
     */
    private String txId;
    public void setTxId(byte[] txId) {
        this.txId  = CryptoUtil.bytesToHex(txId);
    }

    /**
     * 引用交易输出索引
     */
    private int vout;

    /**
     * 数值 支付的钱金额   //比特币的最小单位是 “聪（satoshi）”，1 比特币（BTC）= 1 亿聪（1 BTC = 100,000,000 satoshi）。
     */
    private long value;

    /**
     * 锁定脚本  谁能提供 签名和公钥 并通过验证就能使用这笔未花费   公钥hash就是比特币地址   或者公钥本身
     */
    private String scriptPubKey;  // 锁定脚本，定义资金使用条件
    public void setScriptPubKey(ScriptPubKey scriptPubKey) {
        if (scriptPubKey != null){
            this.scriptPubKey = scriptPubKey.toScripString();
        }
    }

}
