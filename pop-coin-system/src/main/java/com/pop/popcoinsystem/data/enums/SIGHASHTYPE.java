package com.pop.popcoinsystem.data.enums;

/**
 * 签名哈希类型枚举
 */
public enum SIGHASHTYPE {
    // 基本类型
    ALL(0x01, "覆盖所有输入和输出，防止任何篡改"),
    NONE(0x02, "覆盖所有输入，但不包含任何输出，允许输出被修改"),
    SINGLE(0x03, "覆盖所有输入和对应索引的输出，确保特定输出不被篡改"),

    // 组合类型
    ANYONECANPAY(0x80, "允许其他输入被修改，常用于多方签名场景"),

    // 复合类型 (基本类型 + ANYONECANPAY)
    ALL_ANYONECANPAY(0x81, "覆盖所有输出，但允许其他输入被修改"),
    NONE_ANYONECANPAY(0x82, "不覆盖任何输出，且允许其他输入被修改"),
    SINGLE_ANYONECANPAY(0x83, "覆盖对应输出，且允许其他输入被修改");

    private final int value;
    private final String description;

    SIGHASHTYPE(int value, String description) {
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


    public static void main(String[] args) {
        System.out.println("SIGHASH_ALL: " + SIGHASHTYPE.ALL);
        System.out.println("SIGHASH_ALL 值: 0x" + Integer.toHexString(SIGHASHTYPE.ALL.getValue()));

        System.out.println("\nSIGHASH_SINGLE | ANYONECANPAY: " + SIGHASHTYPE.SINGLE_ANYONECANPAY);
        System.out.println("SIGHASH_SINGLE | ANYONECANPAY 值: 0x" +
                Integer.toHexString(SIGHASHTYPE.SINGLE_ANYONECANPAY.getValue()));
    }

}