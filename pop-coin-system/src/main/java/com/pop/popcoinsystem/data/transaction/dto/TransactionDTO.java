package com.pop.popcoinsystem.data.transaction.dto;

import com.pop.popcoinsystem.data.transaction.TXInput;
import com.pop.popcoinsystem.data.transaction.TXOutput;
import com.pop.popcoinsystem.data.transaction.Witness;
import com.pop.popcoinsystem.util.CryptoUtil;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class TransactionDTO {
    /**
     * 交易的Hash
     */
    private String txId;
    public void setTxId(byte[] txId){
        this.txId = CryptoUtil.bytesToHex(txId);
    }

    private String wtxId;
    public void setWtxId(byte[] wtxId){
        this.wtxId = CryptoUtil.bytesToHex(wtxId);
    }

    /**
     * 交易版本
     */
    private long version = 1;

    /**
     * 交易数据大小
     */
    private long size;

    /**
     * 权重
     */
    private long weight;

    /**
     * 锁定时间
     */
    private long lockTime  = 0;

    /**
     * 交易创建时间
     */
    private long time = System.currentTimeMillis();

    /**
     * 交易输入
     */
    private List<TXInputDTO> inputs = new ArrayList<>();;//交易的输入。可以有多个输入，每一个输入都说明了他是引用的哪一比交易的输出。这里可以理解为 我本次交易的钱是从哪来的。

    /**
     * 交易输出
     */
    private List<TXOutputDTO> outputs = new ArrayList<>();;//交易的输出，可以有多个，本次交易的钱我可以转给多个不同的地址，包括给自己找零的钱。可以理解为 我本次交易的钱都给了哪些人。
    /**
     * 见证数据
     */
    private List<WitnessDTO> witnesses = new ArrayList<>(); // 每个输入对应一个Witness
}
