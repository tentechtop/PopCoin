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

    // 算力（如140）
    private Double hashRate;







    // 枚举定义
    public enum MinerType {
        INDIVIDUAL, MINING_POOL, NODE_MINER
    }

    public enum MinerStatus {
        ACTIVE, OFFLINE, PAUSED, BANNED
    }

    public enum HashRateUnit {
        H_PER_SEC, TH_PER_SEC, PH_PER_SEC
    }

    public enum VerificationStatus {
        UNVERIFIED, PENDING, VERIFIED, REJECTED
    }
}
