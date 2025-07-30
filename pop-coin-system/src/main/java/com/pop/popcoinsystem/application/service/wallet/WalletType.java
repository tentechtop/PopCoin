package com.pop.popcoinsystem.application.service.wallet;


public enum WalletType {
    // 基本类型
    COMMON(1, "普通钱包"),
    PASSWORD(2, "密码钱包"),
    MNEMONIC(3, "助记词钱包"),
    PASSWORD_MNEMONIC(4, "密码助记词钱包");

    private final int value;
    private final String description;

    WalletType(int value, String description) {
        this.value = value;
        this.description = description;
    }

    public int getValue() {
        return value;
    }

    public String getDescription() {
        return description;
    }
}