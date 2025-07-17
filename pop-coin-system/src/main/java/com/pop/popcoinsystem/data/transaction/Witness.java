package com.pop.popcoinsystem.data.transaction;

import com.pop.popcoinsystem.data.script.Script;
import com.pop.popcoinsystem.util.CryptoUtil;
import lombok.Getter;
import lombok.Setter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 隔离见证数据结构
 * 用于存储P2WSH和P2WPKH交易的见证数据
 */
@Getter
@Setter
public class Witness {
    private List<byte[]> stack; // 见证栈，包含签名和其他见证数据

    public Witness() {
        this.stack = new ArrayList<>();
    }

    /**
     * 添加见证项（如签名）到见证栈
     * @param item 见证项字节数组
     */
    public void addItem(byte[] item) {
        this.stack.add(item);
    }
    public void addSignature(byte[] sig) {
        this.stack.add(sig);
    }

    /**
     * 添加多个见证项
     * @param items 见证项列表
     */
    public void addItems(List<byte[]> items) {
        this.stack.addAll(items);
    }

    /**
     * 设置赎回脚本作为最后一个见证项
     * @param redeemScript 赎回脚本
     */
    public void setRedeemScript(Script redeemScript) {
        this.stack.add(redeemScript.serialize());
    }

    /**
     * 获取见证栈大小
     * @return 见证项数量
     */
    public int getSize() {
        return stack.size();
    }

    /**
     * 获取指定位置的见证项
     * @param index 索引
     * @return 见证项字节数组
     */
    public byte[] getItem(int index) {
        if (index < 0 || index >= stack.size()) {
            return new byte[0];
        }
        return stack.get(index);
    }

    /**
     * 获取赎回脚本（最后一个见证项）
     * @return 赎回脚本字节数组
     */
    public byte[] getRedeemScript() {
        if (stack.isEmpty()) {
            return new byte[0];
        }
        return stack.get(stack.size() - 1);
    }

    /**
     * 序列化Witness为字节数组
     * @return 序列化后的字节数组
     */
    public byte[] serialize() {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            // 写入见证项数量（VarInt格式）
            byte[] countBytes = CryptoUtil.encodeVarInt(stack.size());
            bos.write(countBytes);

            // 依次写入每个见证项
            for (byte[] item : stack) {
                // 写入项长度（VarInt格式）
                bos.write(CryptoUtil.encodeVarInt(item.length));
                // 写入项内容
                bos.write(item);
            }

            return bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize Witness", e);
        }
    }

    /**
     * 从字节数组反序列化Witness
     * @param bytes 字节数组
     * @return 反序列化后的Witness对象
     */
    public static Witness deserialize(byte[] bytes) {
        Witness witness = new Witness();
        int offset = 0;

        // 读取见证项数量
        long count = CryptoUtil.readVarInt(bytes, offset);
        offset += CryptoUtil.varIntSize(count);

        // 依次读取每个见证项
        for (int i = 0; i < count; i++) {
            // 读取项长度
            long itemLength = CryptoUtil.readVarInt(bytes, offset);
            offset += CryptoUtil.varIntSize(itemLength);

            // 读取项内容
            byte[] item = Arrays.copyOfRange(bytes, offset, offset + (int) itemLength);
            witness.addItem(item);
            offset += itemLength;
        }

        return witness;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Witness{");
        sb.append("size=").append(stack.size());
        sb.append(", items=[");

        for (int i = 0; i < stack.size(); i++) {
            byte[] item = stack.get(i);
            sb.append("item").append(i).append("=").append(CryptoUtil.bytesToHex(item));
            if (i < stack.size() - 1) {
                sb.append(", ");
            }
        }

        sb.append("]}");
        return sb.toString();
    }


    public int getPushCount() {
        return stack.size();
    }
}