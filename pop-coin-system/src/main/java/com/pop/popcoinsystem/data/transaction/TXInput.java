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
     * 交易Id的hash值 前序交易的的ID
     */
    private byte[] txId;

    /**
     * 引用交易输出索引 前序交易的输出索引
     */
    private int vout;

    /**
     * 解锁脚本  是否允许使用这笔花费   满足条件可使用   签名就是对交易签名 （SegWit中通常为空）
     */
    private ScriptSig scriptSig; // 解锁脚本，证明有权使用该输出


    /** 隔离见证数据（每个输入的见证栈） */
    private Witness witness; // 引用之前实现的Witness类


    /** 序列号（用于时间锁定，默认0xFFFFFFFF） */
    private long sequence = 0xFFFFFFFFL;

}
