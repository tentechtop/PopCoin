package com.pop.popcoinsystem.data.transaction;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 交易信息
 *
 * 本地比特币客户端会把这条数据向外扩散，传播。让每一个比特币客户端都知晓我的这一笔交易，这样这笔交易才是有效的。他如何验证这笔交易的有效性：
 *
 *
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class Transaction {

    private static final int SUBSIDY = 10;

    /**
     * 唯一ID
     */
    private String id;

    /**
     * 交易的Hash
     */
    private String txId;

    /**
     * 交易版本
     */
    private long version;

    /**
     * 交易数据大小
     */
    private long size;

    /**
     * 权重
     */
    private long weight;

    /**
     * 时间戳
     */
    private long lockTime;


    /**
     * 交易输入
     */
    private List<TXInput> inputs;//交易的输入。可以有多个输入，每一个输入都说明了他是引用的哪一比交易的输出。这里可以理解为 我本次交易的钱是从哪来的。

    /**
     * 交易输出
     */
    private List<TXOutput> outputs;//交易的输出，可以有多个，本次交易的钱我可以转给多个不同的地址，包括给自己找零的钱。可以理解为 我本次交易的钱都给了哪些人。

}
