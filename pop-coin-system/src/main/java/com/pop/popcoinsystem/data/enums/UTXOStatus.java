package com.pop.popcoinsystem.data.enums;

/**
 * 签名哈希类型枚举
 */
public enum UTXOStatus {
    // 基本类型
    NOSPENT(0, "未花费"),//不删除
    SPENT(1, "已经花费"),
    ALREADYSPENT(2, "待花费"),//选中就锁定
    ;

    private final int value;
    private final String description;

    UTXOStatus(int value, String description) {
        this.value = value;
        this.description = description;
    }

    public int getValue() {
        return value;
    }

    public String getDescription() {
        return description;
    }

    @Override
    public String toString() {
        return name() + "(" + String.format("0x%02X", value) + "): " + description;
    }



}