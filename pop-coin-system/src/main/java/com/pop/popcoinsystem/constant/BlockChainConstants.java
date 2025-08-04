package com.pop.popcoinsystem.constant;

import com.pop.popcoinsystem.util.CryptoUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static com.pop.popcoinsystem.util.YamlReaderUtils.getNestedValue;
import static com.pop.popcoinsystem.util.YamlReaderUtils.loadYaml;

@Slf4j
public class BlockChainConstants {
    // 改为静态变量，所有实例共享一个计数器

    //RPC_TIMEOUT
    public static final int RPC_TIMEOUT = 3000;
    public static final AtomicLong messageIdGenerator = new AtomicLong(0);
    public static int NET_VERSION = 1;
    public static void setNetVersion(int netVersion) {
        NET_VERSION = netVersion;
        log.info("设置网络版本为：{}", netVersion);
    }

    //存储路径
    public static String STORAGE_PATH = "db/";

    public static void setStoragePath(String storagePath) {
        STORAGE_PATH = storagePath;
    }


    // 交易版本 1基础交易版本
    public static final int TRANSACTION_VERSION_1 = 1;

    public static final long MIN_TRANSACTION_OUTPUT_AMOUNT = 10000;

    public static final int MAX_BLOCK_WEIGHT = 4000000;

    // CoinBase交易成熟度要求
    public static final int COINBASE_MATURITY = 100;

    // 转账交易成熟度要求
    public static final int CONFIRMATIONS = 6;

    public static final String GENESIS_BLOCK_HASH_HEX = "000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f";

    //创世区块前序hash不是NUll
    public static final byte[] GENESIS_PREV_BLOCK_HASH = new byte[32];


    //难度目标 0000ffffffff0000000000000000000000000000000000000000000000000000
    public static final String INIT_DIFFICULTY_TARGET_HEX = "0000ffffffff0000000000000000000000000000000000000000000000000000";

    public static final byte[] GENESIS_BLOCK_HASH = CryptoUtil.hexToBytes(GENESIS_BLOCK_HASH_HEX);

    //初始区块奖励
    public static final long INITIAL_REWARD = 50;

    //单位 1e8
    public static final long BLOCK_REWARD_UNIT = 100000000;

    //难度调整周期的区块数量
    public static final int DIFFICULTY_ADJUSTMENT_INTERVAL = 2016;

    //货币总供应量
    public static final long MONEY_SUPPLY = 2100000000;

    //区块生成时间
    public static final long BLOCK_GENERATION_TIME = 20; //600是600秒 10分钟

    //减半周期
    public static final int HALVING_PERIOD = 21000000;

    //时间窗口大小
    public static final int TIME_WINDOW_SIZE = 11;

    //交易列表大小 1M
    public static final int MAX_TRANSACTION_SIZE = 1024 * 1024;

    //交易池  最大300M
    public static final long MAX_SIZE_BYTES = 300 * 1024 * 1024;

    //共识版本
    public static final int CONSENSUS_VERSION = 1;


}
