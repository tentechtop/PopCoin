package com.pop.popcoinsystem.data.transaction.dto;

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
public class WitnessDTO {
    private List<String> stack; // 见证栈，包含签名和其他见证数据
    public void setStack(List<byte[]> stack) {
        ArrayList<String> strings = new ArrayList<>();
        for (byte[] bytes : stack) {
            strings.add(CryptoUtil.bytesToHex(bytes));
        }
        this.stack = strings;
    }
}