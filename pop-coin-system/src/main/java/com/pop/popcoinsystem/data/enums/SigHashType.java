package com.pop.popcoinsystem.data.enums;

import com.pop.popcoinsystem.util.CryptoUtil;

/**
 * 签名哈希类型枚举
 *
 * 基本类型
 * 这些定义了对交易输入和输出的基本签名覆盖范围：
 *
 * ALL(0x01)
 * 覆盖范围：所有输入 + 所有输出
 * 效果：签名者承诺不会修改任何输入或输出
 * 用途：标准交易，防止交易被第三方篡改
 * NONE(0x02)
 * 覆盖范围：所有输入
 * 效果：
 * 输出可以被修改（金额、接收地址等）
 * 除当前输入外，其他输入的 sequence 会被设为 0（允许替换）
 * 用途：协作交易，如多方签名中的一方先签署，允许其他方调整输出
 * SINGLE(0x03)
 * 覆盖范围：所有输入 + 相同索引的输出
 * 效果：
 * 只保护与输入索引相同的那个输出（如 input [0] 对应 output [0]）
 * 其他输出会被替换为 "空白"（金额 - 1，脚本长度 0）
 * 除当前输入外，其他输入的 sequence 会被设为 0
 * 用途：多方交易中，各方只关心自己的那部分输出
 * 组合类型
 * 这是一个特殊标志，可以与基本类型组合：
 *
 * ANYONECANPAY(0x80)
 * 效果：
 * 只包含当前输入，忽略其他输入
 * 其他输入可以被添加、删除或修改
 * 用途：
 * 多方签名场景，允许其他签名者添加自己的输入
 * 例如支付协议中，接收方先签署，发送方再添加自己的输入
 * 复合类型
 * 这些是基本类型与 ANYONECANPAY 的组合：
 *
 * ALL_ANYONECANPAY(0x81)
 * 覆盖范围：当前输入 + 所有输出
 * 效果：
 * 允许修改其他输入
 * 但输出不能被修改
 * 用途：支付协议中，发送方添加输入但不能修改接收方设置的输出
 * NONE_ANYONECANPAY(0x82)
 * 覆盖范围：当前输入
 * 效果：
 * 允许修改其他输入
 * 允许修改所有输出
 * 用途：高度灵活的多方协作，如众筹，每个人只对自己投入的部分负责
 * SINGLE_ANYONECANPAY(0x83)
 * 覆盖范围：当前输入 + 相同索引的输出
 * 效果：
 * 允许修改其他输入
 * 只保护特定索引的输出
 * 用途：复杂的多方交易，各方只对自己的输入和对应的输出负责
 *
 *
 */
public enum SigHashType {
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

    private final byte value; // 修正为单个字节
    private final String description;

    SigHashType(int value, String description) { // 构造函数使用 int，自动转换为 byte
        this.value = (byte) value;
        this.description = description;
    }

    public byte getValue() { // 返回类型修正为 byte
        return value;
    }

    public String getDescription() {
        return description;
    }

    @Override
    public String toString() {
        return name() + "(" + String.format("0x%02X", value & 0xFF) + "): " + description;
    }

    public static void main(String[] args) {
        System.out.println("SIGHASH_ALL: " + SigHashType.ALL);
        System.out.println("SIGHASH_ALL 值: 0x" + CryptoUtil.bytesToHex(new byte[]{SigHashType.ALL.getValue()}));

        System.out.println("\nSIGHASH_SINGLE | ANYONECANPAY: " + SigHashType.SINGLE_ANYONECANPAY);
        System.out.println("SIGHASH_SINGLE | ANYONECANPAY 值: 0x" +
                CryptoUtil.bytesToHex(new byte[]{SigHashType.SINGLE_ANYONECANPAY.getValue()}));
    }
}