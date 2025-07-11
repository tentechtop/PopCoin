package com.pop.popcoinsystem.data.transaction;

import com.pop.popcoinsystem.data.script.ScriptPubKey;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.security.PublicKey;

/**
 * description：交易输出
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class TXOutput {



    /**
     * 交易输出唯一ID   内容摘要
     */
    private String txOutputHash;


    /**
     * 索引
     */
    private int index; //0 1  2 3


    /**
     * 父交易ID
     */
    private String txID;


    /**
     * 数值
     */
    private long value;  // 输出的比特币数量  我要支付的钱金额


    /**
     * 接收者公钥  新所有者
     */
    private PublicKey recipient;


    /**
     * 锁定脚本   谁能提供 签名和公钥 并通过验证就能使用这笔未花费   公钥hash就是比特币地址   或者公钥本身
     */
    private ScriptPubKey scriptPubKey;  // 锁定脚本，定义资金使用条件

}
