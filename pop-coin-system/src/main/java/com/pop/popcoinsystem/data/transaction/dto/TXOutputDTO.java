package com.pop.popcoinsystem.data.transaction.dto;

import com.pop.popcoinsystem.data.script.ScriptPubKey;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class TXOutputDTO {
    /**
     * 数值 支付的钱金额
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
