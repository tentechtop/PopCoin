package com.pop.popcoinsystem;

import java.math.BigInteger;

public class TestMy {
    // 最小目标值
    private static final BigInteger MIN_TARGET = new BigInteger(
            "00000000FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF", 16);

    public static void main(String[] args) {
        String compactTarget = "1d00ffff";

        // 解析Compact格式
        int exponent = Integer.parseInt(compactTarget.substring(0, 2), 16);
        BigInteger coefficient = new BigInteger(compactTarget.substring(2), 16);

        // 计算实际目标值
        BigInteger powerOf2 = BigInteger.valueOf(2).pow(8 * (exponent - 3));
        BigInteger actualTarget = coefficient.multiply(powerOf2);

        // 计算难度
        BigInteger difficulty = MIN_TARGET.divide(actualTarget);

        System.out.println("Compact目标: " + compactTarget);
        System.out.println("指数部分: " + exponent);
        System.out.println("系数部分: " + coefficient);
        System.out.println("实际目标值: " + actualTarget.toString(16));
        System.out.println("难度值: " + difficulty);
    }
}
