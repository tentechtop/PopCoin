package com.pop.popcoinsystem;



import java.math.BigInteger;
import java.util.Arrays;

public class DifficultyCalculator {
    // 字节数组与字节数组相加
    public static byte[] add(byte[] a, byte[] b) {
        BigInteger bigA = new BigInteger(1, a);
        BigInteger bigB = new BigInteger(1, b);
        return bigA.add(bigB).toByteArray();
    }

    // 字节数组与long相加
    public static byte[] add(byte[] a, long b) {
        BigInteger bigA = new BigInteger(1, a);
        BigInteger bigB = BigInteger.valueOf(b);
        return bigA.add(bigB).toByteArray();
    }

    // 比较两个难度值的大小
    public static int compare(byte[] a, byte[] b) {
        BigInteger bigA = new BigInteger(1, a);
        BigInteger bigB = new BigInteger(1, b);
        return bigA.compareTo(bigB);
    }

    // 将字节数组转换为十六进制字符串以便打印
    public static String toHexString(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }

    // 将字节数组转换为long（仅适用于长度<=8的数组）
    public static long bytesToLong(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return 0;
        }
        if (bytes.length > 8) {
            throw new IllegalArgumentException("Byte array too large to fit into a long: " + bytes.length);
        }
        long result = 0;
        for (byte b : bytes) {
            result = (result << 8) | (b & 0xFF);
        }
        return result;
    }

    public static void main(String[] args) {
        // 示例1：byte[] + byte[]
        byte[] difficultyA = new byte[]{0x01, 0x00}; // 256
        byte[] difficultyB = new byte[]{0x02, 0x00}; // 512
        byte[] sumBytes = add(difficultyA, difficultyB);
        System.out.println("Bytes + Bytes = " + toHexString(sumBytes)); // 输出 0300 (768)

        // 示例2：byte[] + long
        long value = 1000L;
        byte[] sumWithLong = add(difficultyA, value);
        System.out.println("Bytes + Long = " + toHexString(sumWithLong)); // 输出 04e8 (1256)

        // 示例3：比较运算
        int cmpResult = compare(difficultyA, difficultyB);
        System.out.println("A 与 B 比较结果: " + cmpResult); // 输出 -1 (A < B)

        // 示例4：字节数组转long
        try {
            System.out.println("A 转 long = " + bytesToLong(difficultyA)); // 输出 256
            System.out.println("B 转 long = " + bytesToLong(difficultyB)); // 输出 512
        } catch (IllegalArgumentException e) {
            System.out.println(e.getMessage());
        }

        // 示例5：处理大数（超过long范围）
        byte[] largeNum = new byte[32];
        Arrays.fill(largeNum, (byte) 0xFF); // 创建一个全FF的32字节数组
        byte[] increment = new byte[]{0x01}; // 数值1
        byte[] result = add(largeNum, increment);
        System.out.println("大数加1后的结果长度: " + result.length);

        // 尝试将超过long范围的数组转换为long（会抛出异常）
        try {
            System.out.println("大数转long = " + bytesToLong(largeNum));
        } catch (IllegalArgumentException e) {
            System.out.println("错误: " + e.getMessage());
        }
    }
}