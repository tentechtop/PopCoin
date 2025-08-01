package com.pop.popcoinsystem.data.block;

import com.pop.popcoinsystem.data.transaction.Transaction;
import lombok.Data;

import java.util.List;

@Data
public class BlockBody {

    //表示该区块中包含的交易数量
    private int txCount;
    //区块中的交易 存储结构是分开存储的
    private List<Transaction> transactions;
}
