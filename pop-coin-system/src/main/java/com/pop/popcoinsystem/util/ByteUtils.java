package com.pop.popcoinsystem.util;

import org.apache.commons.lang3.ArrayUtils;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.stream.Stream;

/**
 * 字节数组工具类
 */
public class ByteUtils {
    /**
     * 将多个字节数组合并成一个字节数组
     *
     * @param bytes
     * @return
     */
    public static byte[] merge(byte[]... bytes) {
        Stream<Byte> stream=Stream.of();
        for (byte[] b : bytes) {
            stream=Stream.concat(stream, Arrays.stream(ArrayUtils.toObject(b)));
        }
        return ArrayUtils.toPrimitive(stream.toArray(Byte[]::new));
    }


    /**
     * 两个byte[]数组相加
     *
     * @param data1
     * @param data2
     * @return
     */
    public static byte[] add(byte[] data1, byte[] data2)
    {
        byte[] result = new byte[data1.length + data2.length];
        System.arraycopy(data1, 0, result, 0, data1.length);
        System.arraycopy(data2, 0, result, data1.length, data2.length);
        return result;
    }

    /**
     * long 类型转 byte[]
     *
     * @param val
     * @return
     */
    public static byte[] toBytes(long val) {
        return ByteBuffer.allocate(Long.BYTES).putLong(val).array();
    }

    /**
     * byte[] to LONG
     */
    public static long bytesToLong(byte[] bytes) {
        return ByteBuffer.wrap(bytes).getLong();
    }

    //测试
    public static void main(String[] args) {
        long myLong = 123456789L;
        byte[] bytes = ByteUtils.toBytes(myLong);
        System.out.println(Arrays.toString( ByteUtils.toBytes(myLong)) );
        System.out.println(ByteUtils.bytesToLong(bytes));
    }




    /**
     * 字节数组转十六进制字符串
     */
    public static String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }

    /**
     * 十六进制字符串转字节数组
     */
    public static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i+1), 16));
        }
        return data;
    }












    //int
    // 将 int 转换为 4 字节数组（大端序）
    // 将 int 转换为 4 字节数组（大端序）
    // 将int转换为4字节数组（大端序）
    public static byte[] intToBytes(int value) {
        return ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array();
    }

    // 将4字节数组转换为int（大端序）
    public static int fromBytesToInt(byte[] bytes) {
        if (bytes == null) {
            throw new IllegalArgumentException("字节数组不能为 null");
        }
        if (bytes.length != 4) {
            throw new IllegalArgumentException("转换 int 类型时，字节数组长度必须为 4，实际为：" + bytes.length);
        }
        return ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).getInt();
    }

    // 若存在toBytes方法，确保其处理int时调用intToBytes（可选）
    public static byte[] toBytes(int value) {
        return intToBytes(value); // 保证int转换为4字节
    }


    /**
     * 将两个字节数组合并成一个新的字节数组
     * @param currentHash 第一个字节数组（放在前面）
     * @param pathHash 第二个字节数组（放在后面）
     * @return 合并后的新字节数组
     */
    public static byte[] concat(byte[] currentHash, byte[] pathHash) {
        // 处理空数组情况
        if (currentHash == null && pathHash == null) {
            return new byte[0];
        }
        if (currentHash == null) {
            return pathHash.clone();
        }
        if (pathHash == null) {
            return currentHash.clone();
        }

        // 创建新数组，长度为两个输入数组长度之和
        byte[] result = new byte[currentHash.length + pathHash.length];

        // 复制第一个数组
        System.arraycopy(currentHash, 0, result, 0, currentHash.length);

        // 复制第二个数组到第一个数组后面
        System.arraycopy(pathHash, 0, result, currentHash.length, pathHash.length);

        return result;
    }
}
