package com.pop.popcoinsystem.data.transaction;

import com.pop.popcoinsystem.data.script.ScriptPubKey;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class UTXO {

    /**
     * 交易ID和输出索引的结合 用于唯一标识一个UTXO
     */
    private String utxoId;        // UTXO 唯一标识

    /**
     * 交易Id的hash值
     */
    private byte[] txId;

    /**
     * 引用交易输出索引
     */
    private int vout;

    /**
     * 接收者 地址
     */
    private String address;

    /**
     * 数值 支付的钱金额
     */
    private BigDecimal value;

    /**
     * 锁定脚本  谁能提供 签名和公钥 并通过验证就能使用这笔未花费   公钥hash就是比特币地址   或者公钥本身
     */
    private ScriptPubKey scriptPubKey;  // 锁定脚本，定义资金使用条件


}
