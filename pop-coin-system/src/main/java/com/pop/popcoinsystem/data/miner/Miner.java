package com.pop.popcoinsystem.data.miner;

import lombok.Data;

/**
 * 节点挖矿信息
 */
@Data
public class Miner {

    //奖励接收地址
    private String address;

    //名称
    private String name;

    //创建区块数量
    private int blockCount = 0;

    //线程数量
    private int threadCount = Runtime.getRuntime().availableProcessors();;

}
