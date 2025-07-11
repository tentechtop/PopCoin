package com.pop.popcoinsystem.data.transaction;

import com.pop.popcoinsystem.data.script.ScriptSig;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;


/**
 * description：交易输入
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class TXInput {

    /**
     * 引用交易输出索引  排名
     */
    private int txOutputIndex;


    /**
     * 引用交易输出ID  hash
     */
    private String txOutputId;


    /**
     * 交易Id的hash值  归属交易ID
     */
    private String txId;  //引用的交易id.这里及指明了我的钱是从这笔交易来的

    /**
     * 未花费 输出
     */
    private TXOutput txOutput;



    /**
     * 解锁脚本  是否允许使用这笔花费   满足条件可使用   签名就是对交易签名
     */
    private ScriptSig pubKey; // 解锁脚本，证明有权使用该输出

}
