package com.pop.popcoinsystem.util;

import io.netty.buffer.ByteBuf;

import java.io.IOException;
import java.math.BigInteger;

/**
 * 类型转换工具类
 * 提供字节数组、数值类型、字节缓冲区之间的格式转换功能，支持大端序和小端序解析
 */
public class TypeUtils {

    /**
     * Parse 2 bytes from the byte array (starting at the offset) as unsigned 16-bit integer in little endian format.
     *
     * @param bytes
     * @param offset
     * @return
     */
    /**
     * 从字节数组中按小端序解析2字节无符号16位整数
     * （小端序：低位字节存于低地址，高位字节存于高地址）
     *
     * @param bytes 源字节数组
     * @param offset 解析起始偏移量
     * @return 解析后的16位无符号整数值（int类型存储）
     */
    public static int readUint16(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
    }

    /**
     * Parse 4 bytes from the byte array (starting at the offset) as unsigned 32-bit integer in little endian format.
     *
     * @param bytes
     * @param offset
     * @return
     */
    /**
     * 从字节数组中按小端序解析4字节无符号32位整数
     *
     * @param bytes 源字节数组
     * @param offset 解析起始偏移量
     * @return 解析后的32位无符号整数值（long类型存储）
     */
    public static long readUint32(byte[] bytes, int offset) {
        return (bytes[offset] & 0xffl) | ((bytes[offset + 1] & 0xffl) << 8) | ((bytes[offset + 2] & 0xffl) << 16) | (
                (bytes[offset + 3] & 0xffl) << 24);
    }

    /**
     * Parse 8 bytes from the byte array (starting at the offset) as signed 64-bit integer in little endian format.
     *
     * @param bytes
     * @param offset
     * @return
     */
    /**
     * 从字节数组中按小端序解析8字节有符号64位整数
     *
     * @param bytes 源字节数组
     * @param offset 解析起始偏移量
     * @return 解析后的64位有符号整数值
     */
    public static long readInt64(byte[] bytes, int offset) {
        return (bytes[offset] & 0xffl) | ((bytes[offset + 1] & 0xffl) << 8) | ((bytes[offset + 2] & 0xffl) << 16) | (
                (bytes[offset + 3] & 0xffl) << 24) | ((bytes[offset + 4] & 0xffl) << 32) | ((bytes[offset + 5] & 0xffl)
                << 40) | ((bytes[offset + 6] & 0xffl) << 48) | ((bytes[offset + 7] & 0xffl) << 56);
    }

    /**
     * Parse 2 bytes from the byte array (starting at the offset) as unsigned 16-bit integer in big endian format.
     *
     * @param bytes
     * @param offset
     * @return
     */
    /**
     * 从字节数组中按大端序解析2字节无符号16位整数
     * （大端序：高位字节存于低地址，低位字节存于高地址）
     *
     * @param bytes 源字节数组
     * @param offset 解析起始偏移量
     * @return 解析后的16位无符号整数值
     */
    public static int readUint16BE(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 8) | (bytes[offset + 1] & 0xff);
    }


    /**
     * Write 4 bytes to the byte array (starting at the offset) as unsigned 32-bit integer in little endian format.
     *
     * @param val
     * @param out
     * @param offset
     */
    /**
     * 将32位无符号整数按小端序写入字节数组
     *
     * @param val 待写入的无符号整数值
     * @param out 目标字节数组
     * @param offset 写入起始偏移量
     */
    public static void uint32ToByteArrayLE(long val, byte[] out, int offset) {
        out[offset] = (byte) (0xFF & val);
        out[offset + 1] = (byte) (0xFF & (val >> 8));
        out[offset + 2] = (byte) (0xFF & (val >> 16));
        out[offset + 3] = (byte) (0xFF & (val >> 24));
    }

    /**
     * Write 4 bytes to the byte array (starting at the offset) as unsigned 32-bit integer in big endian format.
     *
     * @param val
     * @param out
     * @param offset
     */
    /**
     * 将32位无符号整数按大端序写入字节数组
     *
     * @param val 待写入的无符号整数值
     * @param out 目标字节数组
     * @param offset 写入起始偏移量
     */
    public static void uint32ToByteArrayBE(long val, byte[] out, int offset) {
        out[offset] = (byte) (0xFF & (val >> 24));
        out[offset + 1] = (byte) (0xFF & (val >> 16));
        out[offset + 2] = (byte) (0xFF & (val >> 8));
        out[offset + 3] = (byte) (0xFF & val);
    }

    /**
     * Write 4 bytes to the output stream as unsigned 32-bit integer in little endian format.
     * (逆序写入)
     *
     * @param val
     * @param stream
     * @throws IOException
     */
    /**
     * 将64位有符号整数按小端序写入字节缓冲区
     *
     * @param val 待写入的有符号整数值
     * @param stream Netty字节缓冲区
     * @throws IOException 写入异常
     */
    public static void uint32ToByteBufLE(long val, ByteBuf stream) {
        stream.writeInt((int) (0xFF & val));
        stream.writeInt((int) (0xFF & (val >> 8)));
        stream.writeInt((int) (0xFF & (val >> 16)));
        stream.writeInt((int) (0xFF & (val >> 24)));
    }


    /**
     * Write 8 bytes to the output stream as signed 64-bit integer in little endian format.
     *
     * @param val
     * @param stream
     * @throws IOException
     */
    public static void int64ToByteBufLE(long val, ByteBuf stream) {
        stream.writeInt((int) (0xFF & val));
        stream.writeInt((int) (0xFF & (val >> 8)));
        stream.writeInt((int) (0xFF & (val >> 16)));
        stream.writeInt((int) (0xFF & (val >> 24)));
        stream.writeInt((int) (0xFF & (val >> 32)));
        stream.writeInt((int) (0xFF & (val >> 40)));
        stream.writeInt((int) (0xFF & (val >> 48)));
        stream.writeInt((int) (0xFF & (val >> 56)));
    }


    /**
     * 将64位无符号整数转为二进制，同时用0进行补全
     *
     * @param val
     * @param stream
     * @throws IOException
     */
    /**
     * 将64位无符号大整数转为二进制字节流，不足8字节时用0补全
     *
     * @param val 待转换的BigInteger类型无符号整数
     * @param stream Netty字节缓冲区
     * @throws RuntimeException 输入值超过64位时抛出异常
     */
    public static void uint64ToByteBufLE(BigInteger val, ByteBuf stream) {
        byte[] bytes = val.toByteArray();
        if (bytes.length > 8) {
            throw new RuntimeException("Input too large to encode into a uint64");
        }
        bytes = reverseBytes(bytes);
        stream.writeBytes(bytes);
        if (bytes.length < 8) {
            for (int i = 0; i < 8 - bytes.length; i++)
                stream.writeByte(0);
        }
    }

    /**
     * Write 2 bytes to the output stream as unsigned 16-bit integer in big endian format.
     *
     * @param val
     * @param stream
     * @throws IOException
     */
    /**
     * 将16位无符号整数按大端序写入字节流
     *
     * @param val 待写入的无符号整数值
     * @param stream Netty字节缓冲区
     * @throws IOException 写入异常
     */
    public static void uint16ToByteStreamBE(int val, ByteBuf stream) {
        stream.writeInt((int) (0xFF & (val >> 8)));
        stream.writeByte((int) (0xFF & val));
    }

    /**
     * 对输入R数组进行逆反操作,eturns a copy of the given byte array in reverse order.
     *
     * @param bytes
     * @return
     */
    /**
     * 反转字节数组的顺序（如[1,2,3]转为[3,2,1]）
     *
     * @param bytes 源字节数组
     * @return 反转后的新字节数组
     */
    public static byte[] reverseBytes(byte[] bytes) {
        // We could use the XOR trick here but it's easier to understand if we don't. If we find this is really a
        // performance issue the matter can be revisited.
        // 采用循环复制方式反转，未使用XOR技巧以保证代码可读性
        byte[] buf = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++)
            buf[i] = bytes[bytes.length - 1 - i];
        return buf;
    }

    /**
     * Write 2 bytes to the byte array (starting at the offset) as unsigned 16-bit integer in little endian format.
     *
     * @param val
     * @param out
     * @param offset
     */
    /**
     * 将16位无符号整数按小端序写入字节数组
     *
     * @param val 待写入的无符号整数值
     * @param out 目标字节数组
     * @param offset 写入起始偏移量
     */
    public static void uint16ToByteArrayLE(int val, byte[] out, int offset) {
        out[offset] = (byte) (0xFF & val);
        out[offset + 1] = (byte) (0xFF & (val >> 8));
    }

    /**
     * Write 8 bytes to the byte array (starting at the offset) as signed 64-bit integer in little endian format.
     *
     * @param val
     * @param out
     * @param offset
     */
    /**
     * 将64位有符号整数按小端序写入字节数组
     *
     * @param val 待写入的有符号整数值
     * @param out 目标字节数组
     * @param offset 写入起始偏移量
     */
    public static void int64ToByteArrayLE(long val, byte[] out, int offset) {
        out[offset] = (byte) (0xFF & val);
        out[offset + 1] = (byte) (0xFF & (val >> 8));
        out[offset + 2] = (byte) (0xFF & (val >> 16));
        out[offset + 3] = (byte) (0xFF & (val >> 24));
        out[offset + 4] = (byte) (0xFF & (val >> 32));
        out[offset + 5] = (byte) (0xFF & (val >> 40));
        out[offset + 6] = (byte) (0xFF & (val >> 48));
        out[offset + 7] = (byte) (0xFF & (val >> 56));
    }

}
