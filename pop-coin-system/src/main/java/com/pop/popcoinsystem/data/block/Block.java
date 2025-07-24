package com.pop.popcoinsystem.data.block;

import com.pop.popcoinsystem.data.transaction.Transaction;
import com.pop.popcoinsystem.util.CryptoUtil;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static com.pop.popcoinsystem.util.Numeric.hexStringToByteArray;
import static com.pop.popcoinsystem.util.TypeUtils.reverseBytes;

@Data
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class Block implements Serializable {

    //表示该区块在区块链中的高度，即它是第几个区块。这里的高度为 1，表示它是区块链中的第一个区块（创世块是高度 0）。
    private long height;//区块高度 0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18.........................
    //唯一的标识
    private byte[] hash;
    //前一个区块的哈希值
    private byte[] previousHash;
    //版本号
    private int version;
    // 表示该区块中所有交易的默克尔树根哈希值。它用于快速验证区块中的交易是否被篡改。
    private byte[] merkleRoot;//默克尔根
    //表示该区块的时间戳，以秒为单位的 Unix 时间戳。
    private long time;
    //表示该区块之前 11 个区块的中位时间。它用于一些时间敏感的计算。
    private long medianTime;
    //表示该区块之前的区块链总工作量，以十六进制表示。它反映了整个区块链的挖矿工作量。
    private String chainWork;
    //表示该区块的挖矿难度。它反映了挖矿的难度程度，即找到一个有效区块哈希的难度。
    //. 挖矿难度（Difficulty）
    //本质：一个 相对数值，表示当前难度相对于创世区块难度的倍数
    //作用：作为人类可读的难度指标，方便比较不同时期的挖矿难度
    //创世区块难度：固定为 1.0
    //比特币难度值目前约在 4e12 以内，远小于 2^31-1
    private int difficulty;
    //表示该区块的难度目标，以紧凑格式表示。它用于挖矿过程中的工作量证明计算。
    //1. 难度目标（Difficulty Target）
    //本质：一个 256 位的数值（通常以十六进制表示）
    //作用：直接作为矿工挖矿的目标阈值
    //规则：矿工需要找到一个区块哈希值，使得该哈希值 小于 难度目标
    //表示形式：
    //压缩格式（Difficulty Bits）：如 0x1d00ffff
    //完整格式（Target）：如 0x00000000FFFF0000...（前 32 位为 0，后 224 位为 F）
    private byte[] difficultyTarget;
    //表示该区块的随机数，用于挖矿过程中的工作量证明计算。
    private int nonce;
    //表示该区块中包含的交易数量
    private int txCount;
    //表示该区块的见证数据大小（以字节为单位）。
    private int strippedSize;
    //表示该区块的大小（以字节为单位），包括见证数据。
    private int size;
    //表示该区块的权重，用于比特币的区块大小限制计算。
    private int weight;

    //区块中的交易 存储结构是分开存储的
    private List<Transaction> transactions;


    /**
     * 计算区块哈希（两次SHA-256）
     *
     * @return 计算得到的区块哈希（字节数组）
     */
    public static byte[] calculateHash(Block block) {
        // 1. 构建区块头字节流（按比特币协议顺序排列字段）
        byte[] headerBytes = serializeBlockHeader(block);
        // 2. 执行两次SHA-256哈希
        byte[] firstHash = CryptoUtil.applySHA256(headerBytes);
        byte[] finalHash = CryptoUtil.applySHA256(firstHash);
        // 3. 哈希结果需要反转（比特币存储为小端字节序）
        return reverseBytes(finalHash);
    }

    /**
     * 计算并设置当前区块的默克尔根
     */
    public void calculateAndSetMerkleRoot() {
        this.merkleRoot = calculateMerkleRoot(this.transactions);
    }



    /**
     * 计算交易列表的默克尔根
     * @param transactions 区块中的交易列表
     * @return 默克尔根哈希（字节数组）
     */
    public static byte[] calculateMerkleRoot(List<Transaction> transactions) {
        if (transactions == null || transactions.isEmpty()) {
            return new byte[32]; // 返回32字节的零数组
        }

        // 1. 计算所有交易的哈希值
        List<byte[]> transactionHashes = transactions.stream()
                .map(Transaction::getTxId) // 假设Transaction类有getHash()方法返回交易哈希
                .collect(Collectors.toList());

        // 2. 开始构建默克尔树
        return buildMerkleTree(transactionHashes);
    }

    /**
     * 递归构建默克尔树并返回根哈希
     * @param hashes 当前层的哈希列表
     * @return 根哈希
     */
    private static byte[] buildMerkleTree(List<byte[]> hashes) {
        // 如果列表为空，返回空哈希
        if (hashes.isEmpty()) {
            return new byte[32];
        }

        // 如果只有一个哈希，那就是根哈希（特殊情况，如创世块）
        if (hashes.size() == 1) {
            return hashes.get(0);
        }

        // 存储下一层的哈希值
        List<byte[]> nextLevel = new ArrayList<>();

        // 处理当前层的哈希对
        for (int i = 0; i < hashes.size(); i += 2) {
            // 获取左右节点
            byte[] left = hashes.get(i);
            byte[] right = (i + 1 < hashes.size()) ? hashes.get(i + 1) : left; // 奇数节点时使用自身

            // 合并两个哈希并计算新哈希
            byte[] merged = new byte[left.length + right.length];
            System.arraycopy(left, 0, merged, 0, left.length);
            System.arraycopy(right, 0, merged, left.length, right.length);

            // 计算双SHA-256哈希
            byte[] combinedHash = doubleSHA256(merged);
            nextLevel.add(combinedHash);
        }

        // 递归处理下一层
        return buildMerkleTree(nextLevel);
    }

    /**
     * 执行双SHA-256哈希计算（两次SHA-256）
     * @param data 输入数据
     * @return 哈希结果
     */
    private static byte[] doubleSHA256(byte[] data) {
        byte[] firstHash = CryptoUtil.applySHA256(data);
        return CryptoUtil.applySHA256(firstHash);
    }

    /**
     * 序列化区块头（严格遵循比特币协议）
     * 所有数值字段采用小端字节序（Little-Endian）
     *
     */
    /**
     * 序列化区块头（严格遵循比特币协议）
     * 字段顺序：version(4字节) → previousHash(32字节) → merkleRoot(32字节) → time(4字节) → difficultyBits(4字节) → nonce(4字节)
     * 所有数值字段采用小端字节序（Little-Endian）
     */
    private static byte[] serializeBlockHeader(Block block) {
        try (java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
             java.io.DataOutputStream dos = new java.io.DataOutputStream(baos)) {

            // 1. 版本号（4字节，小端）
            dos.writeInt(Integer.reverseBytes(block.getVersion()));

            // 2. 前区块哈希（32字节，大端存储需反转为小端）
            byte[] prevHash = block.getPreviousHash();
            if (prevHash == null || prevHash.length != 32) {
                throw new IllegalArgumentException("前区块哈希必须为32字节");
            }
            dos.write(reverseBytes(prevHash)); // 反转字节序为小端

            // 3. 默克尔根（32字节，大端存储需反转为小端）
            byte[] merkleRoot = block.getMerkleRoot();
            if (merkleRoot == null || merkleRoot.length != 32) {
                throw new IllegalArgumentException("默克尔根必须为32字节");
            }
            dos.write(reverseBytes(merkleRoot)); // 反转字节序为小端

            // 4. 时间戳（4字节，小端，比特币使用32位时间戳）
            long timestamp = block.getTime();
            if (timestamp < 0 || timestamp > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("时间戳必须为32位正整数（秒级Unix时间）");
            }
            dos.writeInt(Integer.reverseBytes((int) timestamp));

            // 5. 难度目标（4字节，小端，压缩格式）
            byte[] difficultyTarget = block.getDifficultyTarget();
            if (difficultyTarget == null || difficultyTarget.length != 4) {
                throw new IllegalArgumentException("难度目标必须为4字节");
            }
            dos.write(reverseBytes(difficultyTarget)); // 反转字节序为小端

            // 6. Nonce（4字节，小端）
            dos.writeInt(Integer.reverseBytes(block.getNonce()));

            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("区块头序列化失败: " + e.getMessage(), e);
        }
    }



}
