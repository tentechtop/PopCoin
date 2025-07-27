package com.pop.popcoinsystem.util;

import lombok.extern.slf4j.Slf4j;

import java.math.BigInteger;
import java.util.Arrays;

import static com.pop.popcoinsystem.util.CryptoUtil.bytesToHex;

@Slf4j
public class DifficultyUtils {

    // 难度1对应的目标值（256位）：0x1d00ffff对应的展开值
    private static final BigInteger DIFFICULTY_1_TARGET = new BigInteger(
            "00ffffffffff0000000000000000000000000000000000000000000000000000", 16);

    // 每个区块预期出块时间（10分钟，单位：秒）
    private static final long EXPECTED_BLOCK_TIME = 600;
    // 难度调整周期的区块数量
    private static final int DIFFICULTY_ADJUSTMENT_INTERVAL = 2016;
    // 预期周期总时间（2016*10分钟，单位：秒）
    private static final long EXPECTED_CYCLE_TIME = DIFFICULTY_ADJUSTMENT_INTERVAL * EXPECTED_BLOCK_TIME;

    /**
     * 将难度值（long）转换为目标值（256位大整数）
     * @param difficulty 难度值（long类型）
     * @return 目标值（BigInteger）
     */
    public static BigInteger difficultyToTarget(long difficulty) {
        if (difficulty <= 0) {
            throw new IllegalArgumentException("难度值必须为正数");
        }
        // 目标值 = 难度1目标值 / 难度值
        return DIFFICULTY_1_TARGET.divide(BigInteger.valueOf(difficulty));
    }

    /**
     * 将目标值转换为难度值（long）
     * @param target 目标值（256位BigInteger）
     * @return 难度值（long）
     */
    public static long targetToDifficulty(BigInteger target) {
        if (target.compareTo(BigInteger.ZERO) <= 0) {
            throw new IllegalArgumentException("目标值必须为正数");
        }
        // 难度值 = 难度1目标值 / 目标值（向上取整）
        BigInteger[] divideAndRemainder = DIFFICULTY_1_TARGET.divideAndRemainder(target);
        long difficulty = divideAndRemainder[0].longValue();
        if (divideAndRemainder[1].compareTo(BigInteger.ZERO) > 0) {
            difficulty += 1;
        }
        return difficulty;
    }

    /**
     * 计算调整后的新难度（long）
     * @param oldDifficulty 上一周期的难度值（long）
     * @param actualCycleTime 上2016个区块的实际总时间（秒）
     * @return 调整后的新难度值（long）
     */
    public static long calculateNewDifficulty(long oldDifficulty, long actualCycleTime) {
        if (actualCycleTime <= 0) {
            throw new IllegalArgumentException("实际周期时间必须为正数");
        }
        // 新难度 = 旧难度 * 实际周期时间 / 预期周期时间
        double newDifficultyDouble = (double) oldDifficulty * actualCycleTime / EXPECTED_CYCLE_TIME;

        // 限制调整范围：最大为旧难度的4倍，最小为旧难度的1/4
        newDifficultyDouble = Math.min(newDifficultyDouble, oldDifficulty * 4.0);
        newDifficultyDouble = Math.max(newDifficultyDouble, oldDifficulty / 4.0);

        // 转换为long（四舍五入）
        return Math.round(newDifficultyDouble);
    }

    /**
     * 将目标值转换为4字节压缩格式（难度目标字段）
     * @param target 目标值（256位BigInteger）
     * @return 4字节压缩格式数组
     */
    public static byte[] targetToCompact(BigInteger target) {
        if (target.compareTo(BigInteger.ZERO) == 0) {
            return new byte[]{0x00, 0x00, 0x00, 0x00};
        }
        // 目标值的字节数组（大端序）
        byte[] targetBytes = target.toByteArray();
        int exponent = targetBytes.length;
        byte[] coefficient = new byte[3];

        // 提取系数（前3字节，不足补0，超过截断）
        int copyLength = Math.min(3, targetBytes.length);
        System.arraycopy(targetBytes, 0, coefficient, 3 - copyLength, copyLength);

        // 若系数最高位为1，需调整指数和系数
        if ((coefficient[0] & 0x80) != 0) {
            coefficient = new byte[]{0x00, coefficient[0], coefficient[1]};
            exponent += 1;
        }

        return new byte[]{(byte) exponent, coefficient[0], coefficient[1], coefficient[2]};
    }

    /**
     * 将4字节压缩格式解析为目标值
     * @param compact 4字节压缩格式数组
     * @return 目标值（256位BigInteger）
     */
    public static BigInteger compactToTarget(byte[] compact) {
        if (compact.length != 4) {
            throw new IllegalArgumentException("压缩格式必须为4字节");
        }
        int exponent = compact[0] & 0xFF; // 指数（无符号）
        byte[] coefficient = new byte[3];
        System.arraycopy(compact, 1, coefficient, 0, 3);

        // 目标值 = 系数 * 2^(8*(exponent-3))
        BigInteger coeffBigInt = new BigInteger(1, coefficient); // 正数
        int shift = 8 * (exponent - 3);
        return coeffBigInt.shiftLeft(shift);
    }

    /**
     * 判断哈希值是否符合难度目标
     * @param hash 区块哈希值（256位，32字节，大端序）
     * @param target 难度目标值（256位BigInteger）
     * @return 符合条件返回true，否则返回false
     */
    public static boolean isHashValid(byte[] hash, BigInteger target) {
        if (hash == null || hash.length != 32) {
            throw new IllegalArgumentException("哈希值必须为32字节");
        }
        // 将哈希字节数组转换为BigInteger（大端序，正数）
        BigInteger hashValue = new BigInteger(1, hash);
        // 检查哈希值是否小于等于目标值
        return hashValue.compareTo(target) <= 0;
    }

    /**
     * 验证哈希值是否符合难度目标
     * @param hash
     * @param difficultyTarget
     * @return
     */
    public static boolean isValidHash(byte[] hash, byte[] difficultyTarget) {
        // 1. 验证输入参数
        if (hash == null || hash.length != 32) {
            throw new IllegalArgumentException("哈希值必须为32字节");
        }
        if (difficultyTarget == null || difficultyTarget.length != 4) {
            throw new IllegalArgumentException("难度目标必须为4字节");
        }

        // 2. 将难度目标（4字节压缩格式）转换为完整的256位目标值
        BigInteger target = DifficultyUtils.compactToTarget(difficultyTarget);

        // 3. 将哈希字节数组转换为BigInteger（大端序，正数）
        BigInteger hashValue = new BigInteger(1, hash);

        // 4. 比较哈希值是否小于等于目标值
        return hashValue.compareTo(target) <= 0;
    }


    /**
     * 将难度值转换为4字节的压缩格式（难度目标）
     *
     * @param difficulty 难度值（long类型）
     * @return 4字节压缩格式的难度目标
     */
    public static byte[] difficultyToCompact(long difficulty) {
        // 1. 将难度值转换为256位目标值
        BigInteger target = difficultyToTarget(difficulty);

        // 2. 将256位目标值转换为4字节压缩格式
        return targetToCompact(target);
    }

    // 示例测试
    public static void main(String[] args) {
        // 测试1：难度1对应的目标值
        BigInteger target1 = difficultyToTarget(1L);
        System.out.println("难度1的目标值：" + target1.toString(16));

        // 测试2：目标值转换为难度值
        long difficulty = targetToDifficulty(target1);
        System.out.println("目标值对应的难度：" + difficulty); // 应输出1

        // 测试4：压缩格式转换
        byte[] compact = targetToCompact(target1);
        System.out.println("压缩格式（16进制）：" + bytesToHex(compact)); // 应输出1d00ffff

        // 测试5：哈希验证（修正后的示例）
        byte[] validHash = hexToBytes("000000000000000000010000000000000000000000000000000000000000");
        byte[] invalidHash = hexToBytes("010000000000000000000000000000000000000000000000000000000000");
        System.out.println("有效哈希验证结果：" + isHashValid(validHash, target1)); // 应输出true
        System.out.println("无效哈希验证结果：" + isHashValid(invalidHash, target1)); // 应输出false

        byte[] validHash2 = hexToBytes("00000000697486bbbacf95813c0d71937f3dbd60a77892ee31b9be6245dea3bf");
        boolean validHash1 = isValidHash(validHash2, compact);
        System.out.println("自定义：" + validHash1);

    }

    // 辅助方法：字节数组转16进制字符串
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    // 辅助方法：16进制字符串转字节数组
    private static byte[] hexToBytes(String hex) {
        if (hex == null || hex.length() % 2 != 0) {
            throw new IllegalArgumentException("16进制字符串长度必须为偶数");
        }
        // 确保生成32字节的数组
        byte[] bytes = new byte[32];
        byte[] hexBytes = CryptoUtil.hexToBytes(hex);

        // 复制到32字节数组（右对齐，左侧补0）
        int copyLength = Math.min(32, hexBytes.length);
        System.arraycopy(hexBytes, 0, bytes, 32 - copyLength, copyLength);

        return bytes;
    }




}