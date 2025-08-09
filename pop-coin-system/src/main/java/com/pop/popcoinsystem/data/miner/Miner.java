package com.pop.popcoinsystem.data.miner;

import lombok.Data;

import java.util.List;

/**
 * 矿工信息
 */
@Data
public class Miner {

    //名称
    private String name;

    //奖励接收地址
    private List<String> coinBaseAddress;

    //手续费接收地址
    private List<String> feeAddress;

}
