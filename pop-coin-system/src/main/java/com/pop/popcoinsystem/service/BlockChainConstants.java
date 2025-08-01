package com.pop.popcoinsystem.service;

import com.pop.popcoinsystem.util.CryptoUtil;

public class BlockChainConstants {
    public static final long MIN_TRANSACTION_OUTPUT_AMOUNT = 10000;

    public static final int MAX_BLOCK_WEIGHT = 4000000;

    public static final int COINBASE_MATURITY = 100;// CoinBase交易成熟度要求

    public static final int CONFIRMATIONS = 6;// 转账交易成熟度要求

    public static final String GENESIS_BLOCK_HASH_HEX = "000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f";

    public static final byte[] GENESIS_BLOCK_HASH = CryptoUtil.hexToBytes(GENESIS_BLOCK_HASH_HEX);


}
