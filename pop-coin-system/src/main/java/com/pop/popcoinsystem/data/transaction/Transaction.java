package com.pop.popcoinsystem.data.transaction;

import com.pop.popcoinsystem.data.script.ScriptPubKey;
import com.pop.popcoinsystem.data.script.ScriptSig;
import com.pop.popcoinsystem.util.CryptoUtil;
import com.pop.popcoinsystem.util.SerializeUtils;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.commons.codec.digest.DigestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static com.pop.popcoinsystem.data.transaction.constant.SUBSIDY;
import static com.pop.popcoinsystem.data.transaction.constant.VERSION_1;

/**
 * 交易信息
 */
//隔离见证（SegWit） 通过将签名数据（见证数据）与交易的其他部分分离，解决了上述问题。交易被分为：
//
//非见证数据：包含版本号、输入输出结构、锁定时间等固定信息。
//见证数据：包含签名、公钥等证明交易合法性的信息。
@Data
@AllArgsConstructor
@NoArgsConstructor
public class Transaction {
    /**
     * 交易的Hash
     */
    private byte[] txId;

    /**
     * 交易版本
     */
    private long version = 1;

    /**
     * 交易数据大小
     */
    private long size;

    /**
     * 交易手续费
     */
    private long fee;

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
    private List<TXInput> inputs = new ArrayList<>();;//交易的输入。可以有多个输入，每一个输入都说明了他是引用的哪一比交易的输出。这里可以理解为 我本次交易的钱是从哪来的。

    /**
     * 交易输出
     */
    private List<TXOutput> outputs = new ArrayList<>();;//交易的输出，可以有多个，本次交易的钱我可以转给多个不同的地址，包括给自己找零的钱。可以理解为 我本次交易的钱都给了哪些人。

    /**
     * 是否隔离见证
     */
    private boolean segWit;
    /**
     * 见证数据
     */
    private List<Witness> witnesses = new ArrayList<>(); // 每个输入对应一个Witness


    /**
     * 获取这笔交易的hash
     * 计算交易哈希时，应排除txId字段，避免递归计算
     */
    public void setTxId() {
        // 先临时将txId设为null，避免递归计算
        this.txId = null;
        // 序列化交易数据并计算哈希
        this.txId = CryptoUtil.applySHA256(SerializeUtils.serialize(this));
    }

    /**
     * 计算交易权重
     * 权重 = 非见证数据大小 × 4 + 见证数据大小
     */
    public void calculateWeight() {
        // 计算非见证数据大小
        long baseSize = calculateBaseSize();

        // 计算见证数据大小（签名数据）
        long witnessSize = calculateWitnessSize();

        // 计算权重
        this.weight = baseSize * 4 + witnessSize;

        // 计算总大小（VBytes）
        this.size = (long) Math.ceil((double) this.weight / 4);
    }

    /**
     * 计算非见证数据大小
     */
    private long calculateBaseSize() {
        long size = 0;

        // 交易版本大小
        size += 4; // 32位整数

        // 输入数量大小（VarInt）
        size += getVarIntSize(inputs.size());

        // 每个输入的大小（不包括见证数据）
        for (TXInput input : inputs) {
            // txOutId (32字节)
            size += 32;
            // txOutIndex (4字节)
            size += 4;
            // scriptSig大小（VarInt + 脚本数据）
            if (input.getScriptSig() != null) {
     /*           size += getVarIntSize(input.getScriptSig().getData().length);
                size += input.getScriptSig().getData().length;*/
            } else {
                size += 1; // 空脚本
            }
            // sequence (4字节)
            size += 4;
        }

        // 输出数量大小（VarInt）
        size += getVarIntSize(outputs.size());

        // 每个输出的大小
        for (TXOutput output : outputs) {
            // value (8字节)
            size += 8;
            // scriptPubKey大小（VarInt + 脚本数据）
            if (output.getScriptPubKey() != null) {
       /*         size += getVarIntSize(output.getScriptPubKey().getData().length);
                size += output.getScriptPubKey().getData().length;*/
            } else {
                size += 1; // 空脚本
            }
        }

        // lockTime (4字节)
        size += 4;

        return size;
    }

    /**
     * 计算见证数据大小
     */
    private long calculateWitnessSize() {
        long size = 0;

        // 检查是否有隔离见证数据
        boolean hasWitness = false;
        for (TXInput input : inputs) {
/*            if (input.getScriptSig() != null && input.getScriptSig().isWitness()) {
                hasWitness = true;
                break;
            }*/
        }

        if (!hasWitness) {
            return 0;
        }

        // 隔离见证标记和标志（2字节）
        size += 2;

        // 每个输入的见证数据
        for (TXInput input : inputs) {
/*            if (input.getScriptSig() != null && input.getScriptSig().isWitness()) {
                // 见证数据项数量（VarInt）
                size += getVarIntSize(input.getScriptSig().getWitnessCount());

                // 每个见证数据项
                for (byte[] witness : input.getScriptSig().getWitnessData()) {
                    size += getVarIntSize(witness.length);
                    size += witness.length;
                }
            }*/
        }

        return size;
    }

    /**
     * 计算VarInt的大小（以字节为单位）
     */
    private long getVarIntSize(int value) {
        if (value < 0xfd) {
            return 1;
        } else if (value <= 0xffff) {
            return 3;
        } else if (value <= 0xffffffffL) {
            return 5;
        } else {
            return 9;
        }
    }


    public long getFeePerByte() {
        return fee / size;
    }



    //创建一笔CoinBase交易
    public static Transaction createCoinBaseTransaction(String to) {
        //判断地址是否合法类型


        return new Transaction();
    }

}
