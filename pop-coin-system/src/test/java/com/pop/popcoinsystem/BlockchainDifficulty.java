package com.pop.popcoinsystem;

import java.math.BigInteger;
import java.util.Arrays;

public class BlockchainDifficulty {
    private byte[] chainWork;
    private long difficulty;

    public BlockchainDifficulty(byte[] chainWork, long difficulty) {
        this.chainWork = chainWork;
        this.difficulty = difficulty;

    }

    public byte[] getChainWork() {
        return chainWork;
    }

    public long getDifficulty() {
        return difficulty;
    }

    public byte[] addDifficulty() {
        // 将 chainWork 转换为 BigInteger
        BigInteger chainWorkBI = new BigInteger(1, chainWork);

        // 将 difficulty 转换为 BigInteger
        BigInteger difficultyBI = BigInteger.valueOf(difficulty);

        // 相加得到新的难度
        BigInteger newDifficultyBI = chainWorkBI.add(difficultyBI);

        // 转换回 byte[]
        return newDifficultyBI.toByteArray();
    }

    public static void main(String[] args) {
        // 示例：假设 chainWork 是一个 32 字节的数组
        byte[] chainWork = new byte[32];
        Arrays.fill(chainWork, (byte) 0x01); // 填充为全 1

        long difficulty = 1000L;

        BlockchainDifficulty blockchain = new BlockchainDifficulty(chainWork, difficulty);
        byte[] newDifficulty = blockchain.addDifficulty();

        System.out.println("新难度值的字节数组长度: " + newDifficulty.length);
        System.out.println("新难度值的前4个字节: " + bytesToHex(newDifficulty, 4));
    }

    private static String bytesToHex(byte[] bytes, int length) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < Math.min(length, bytes.length); i++) {
            result.append(String.format("%02x", bytes[i]));
        }
        return result.toString();
    }
}