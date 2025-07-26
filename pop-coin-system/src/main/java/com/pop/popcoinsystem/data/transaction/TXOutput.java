package com.pop.popcoinsystem.data.transaction;

import com.pop.popcoinsystem.data.script.ScriptPubKey;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.security.PublicKey;

/**
 * description：交易输出
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class TXOutput {

    /**
     * 数值 支付的钱金额
     */
    private long value;

    /**
     * 锁定脚本  谁能提供 签名和公钥 并通过验证就能使用这笔未花费   公钥hash就是比特币地址   或者公钥本身
     */
    private ScriptPubKey scriptPubKey;  // 锁定脚本，定义资金使用条件


    // 从scriptPubKey推导地址（如果脚本类型支持）
    public String getAddress() {
        if (scriptPubKey == null) return null;
        // 根据脚本类型解析地址（示例逻辑，需根据实际ScriptPubKey实现）


        return null;
    }

    private String extractP2PKHAddress(ScriptPubKey script) {
        // 从P2PKH脚本中提取公钥哈希并转换为地址

        return null;
    }
}
