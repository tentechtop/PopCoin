package com.pop.popcoinsystem.data.block;

import com.pop.popcoinsystem.data.transaction.Transaction;
import com.pop.popcoinsystem.util.BeanCopyUtils;
import com.pop.popcoinsystem.util.CryptoUtil;
import com.pop.popcoinsystem.util.DifficultyUtils;
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
    //区块高度 0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18.........................
    //区块不存储高度 这里显示是为了携带和计算方便
    private long height;
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
    private byte[] chainWork;
    //表示该区块的挖矿难度。它反映了挖矿的难度程度，即找到一个有效区块哈希的难度。
    //. 挖矿难度（Difficulty）
    //本质：一个 相对数值，表示当前难度相对于创世区块难度的倍数
    //作用：作为人类可读的难度指标，方便比较不同时期的挖矿难度
    //创世区块难度：固定为 1.0
    //比特币难度值目前约在 4e12 以内，远小于 2^31-1
    private long difficulty;
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
    private long witnessSize;
    //表示该区块的大小（以字节为单位），包括见证数据。
    private long size;
    //表示该区块的权重，用于比特币的区块大小限制计算。
    private long weight;

    //区块中的交易 存储结构是分开存储的
    private List<Transaction> transactions;



    public byte[] computeBlockHash(Block block) {
        try {
            // 1. 验证区块头关键字段的有效性
            BlockHeader blockHeader = BeanCopyUtils.copyObject(block, BlockHeader.class);
            return computeBlockHeaderHash(blockHeader);
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("区块头数据无效：" + e.getMessage());
        } catch (Exception e) {
            throw new RuntimeException("哈希计算失败：" + e.getMessage(), e);
        }
    }

    public static byte[] computeBlockHeaderHash(BlockHeader blockHeader) {
        try {
            // 1. 验证区块头关键字段的有效性
            validateBlockHeader(blockHeader);
            // 2. 序列化区块头（按协议规定的顺序和格式）
            byte[] headerBytes = serializeBlockHeader(blockHeader);
            // 3. 执行两次SHA-256哈希计算
            byte[] hashResult = doubleSHA256(headerBytes);
            // 4. 调整字节序（区块链通常以小端格式存储哈希）
            return reverseBytes(hashResult);
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("区块头数据无效：" + e.getMessage());
        } catch (Exception e) {
            throw new RuntimeException("哈希计算失败：" + e.getMessage(), e);
        }
    }


    /**
     * 验证区块头字段的有效性（避免无效数据导致的哈希错误）
     */
    private void validateBlockHeader(BlockHeader block) {
        if (block.getPreviousHash() != null && block.getPreviousHash().length != 32) {
            throw new IllegalArgumentException("前区块哈希必须为32字节");
        }
        if (block.getMerkleRoot() != null && block.getMerkleRoot().length != 32) {
            throw new IllegalArgumentException("默克尔根必须为32字节");
        }
        if (block.getDifficultyTarget() != null && block.getDifficultyTarget().length != 4) {
            throw new IllegalArgumentException("难度目标必须为4字节");
        }
        //版本
        if (block.getVersion() < 0) {
            throw new IllegalArgumentException("版本号必须为32位无符号整数");
        }
    }






    /**
     * 计算见证数据大小（以字节为单位）
     * @return 见证数据大小
     */
    public long calculateWitnessSize() {
        if (transactions == null || transactions.isEmpty()) {
            return 0;
        }

        long totalWitnessSize = 0;
        for (Transaction tx : transactions) {
            // 假设 Transaction 类有 getWitnessSize() 方法返回见证数据大小
            totalWitnessSize += tx.getSize();
        }
        return totalWitnessSize;
    }

    /**
     * 计算区块大小（以字节为单位）
     * 区块大小 = 区块头大小 + 交易数量大小 + 所有交易的大小
     */
    public void calculateAndSetSize() {
        // 区块头大小固定为80字节
        long headerSize = 80;
        // 交易数量的大小（使用VarInt编码）
        long txCountSize = getVarIntSize(txCount);

        // 所有交易的总大小
        long transactionsSize = 0;
        if (transactions != null) {
            for (Transaction tx : transactions) {
                // 假设 Transaction 类有 getSize() 方法返回交易大小
                transactionsSize += tx.getSize();
            }
        }
        this.size = headerSize + txCountSize + transactionsSize;
    }

    public static long calculateAndSetSize(Block block) {
        // 区块头大小固定为80字节
        long headerSize = 80;
        // 交易数量的大小（使用VarInt编码）
        long txCountSize = getVarIntSize(block.getTransactions().size());
        List<Transaction> transactions = block.getTransactions();
        // 所有交易的总大小
        long transactionsSize = 0;
        if (transactions != null) {
            for (Transaction tx : transactions) {
                // 假设 Transaction 类有 getSize() 方法返回交易大小
                transactionsSize += tx.getSize();
            }
        }
        return headerSize + txCountSize + transactionsSize;
    }


    /**
     * 计算区块权重
     * 权重公式: base_size * 3 + total_size
     * 其中 base_size 是不含见证数据的大小，total_size 是包含见证数据的完整大小
     */
    public void calculateAndSetWeight() {
        // 确保size已正确计算（若未初始化则先计算）
        if (size <= 0) {
            calculateAndSetSize();
        }

        // 1. 验证见证数据大小的合理性（避免负数baseSize）
        // 见证数据大小不能超过区块总大小（否则baseSize为负）
        if (witnessSize < 0) {
            // 修正负数见证大小（无效值，重置为0）
            witnessSize = 0;
        } else if (witnessSize > size) {
            // 见证大小不能超过区块总大小（否则baseSize为负），强制修正为size
            witnessSize = size;
        }

        // 2. 计算不含见证数据的基础大小（确保非负）
        long baseSize = size - witnessSize;

        // 3. 计算权重（BIP141公式），并确保结果非负（理论上此时已不可能为负）
        long calculatedWeight = baseSize * 3 + size;
        this.weight = Math.max(calculatedWeight, 0);
    }

    public static long calculateAndSetWeight(Block block) {
        // 确保size已正确计算（若未初始化则先计算）
        long size = block.getSize();
        if (size <= 0) {
            size = calculateAndSetSize(block);
        }
        long witnessSize = block.getWitnessSize();

        // 1. 验证见证数据大小的合理性（避免负数baseSize）
        // 见证数据大小不能超过区块总大小（否则baseSize为负）
        if (witnessSize < 0) {
            // 修正负数见证大小（无效值，重置为0）
            witnessSize = 0;
        } else if (witnessSize > size) {
            // 见证大小不能超过区块总大小（否则baseSize为负），强制修正为size
            witnessSize = size;
        }

        // 2. 计算不含见证数据的基础大小（确保非负）
        long baseSize = size - witnessSize;

        // 3. 计算权重（BIP141公式），并确保结果非负（理论上此时已不可能为负）
        long calculatedWeight = baseSize * 3 + size;
        return Math.max(calculatedWeight, 0);
    }



    /**
     * 辅助方法：计算VarInt编码的大小（以字节为单位）
     */
    private static long getVarIntSize(int value) {
        if (value < 0xfd) {
            return 1; // 直接用1字节表示
        } else if (value <= 0xffff) {
            return 3; // 0xfd + 2字节
        } else if (value <= 0xffffffffL) {
            return 5; // 0xfe + 4字节
        } else {
            return 9; // 0xff + 8字节
        }
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
                .map(Transaction::getTxId) // 方法返回交易哈希
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

        // 过滤掉所有null值
        hashes = hashes.stream()
                .filter(hash -> hash != null)
                .collect(Collectors.toList());

        // 如果过滤后没有有效哈希，返回空哈希
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
    public static byte[] doubleSHA256(byte[] data) {
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
    public static byte[] serializeBlockHeader(BlockHeader blockHeader) {
        try (java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
             java.io.DataOutputStream dos = new java.io.DataOutputStream(baos)) {

            // 1. 版本号（4字节，小端）
            dos.writeInt(Integer.reverseBytes(blockHeader.getVersion()));

            // 2. 前区块哈希（32字节，大端存储需反转为小端）
            byte[] prevHash = blockHeader.getPreviousHash();
            if (prevHash == null || prevHash.length != 32) {
                throw new IllegalArgumentException("前区块哈希必须为32字节");
            }
            dos.write(reverseBytes(prevHash)); // 反转字节序为小端

            // 3. 默克尔根（32字节，大端存储需反转为小端）
            byte[] merkleRoot = blockHeader.getMerkleRoot();
            if (merkleRoot == null || merkleRoot.length != 32) {
                throw new IllegalArgumentException("默克尔根必须为32字节");
            }
            dos.write(reverseBytes(merkleRoot)); // 反转字节序为小端

            // 4. 时间戳（4字节，小端，比特币使用32位时间戳）
            long timestamp = blockHeader.getTime();
            if (timestamp < 0 || timestamp > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("时间戳必须为32位正整数（秒级Unix时间）");
            }
            dos.writeInt(Integer.reverseBytes((int) timestamp));

            // 5. 难度目标（4字节，小端，压缩格式）
            byte[] difficultyTarget = blockHeader.getDifficultyTarget();
            if (difficultyTarget == null || difficultyTarget.length != 4) {
                throw new IllegalArgumentException("难度目标必须为4字节");
            }
            dos.write(reverseBytes(difficultyTarget)); // 反转字节序为小端

            // 6. Nonce（4字节，小端）
            dos.writeInt(Integer.reverseBytes(blockHeader.getNonce()));

            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("区块头序列化失败: " + e.getMessage(), e);
        }
    }


    public BlockHeader extractHeader() {
        BlockHeader header = new BlockHeader();
        // 复制区块头核心字段（根据你的BlockHeader定义补充）
        header.setVersion(this.version);
        header.setPreviousHash(this.previousHash);
        header.setMerkleRoot(this.merkleRoot);
        header.setTime(this.time);
        header.setDifficultyTarget(this.difficultyTarget);
        header.setNonce(this.nonce);

        // 补充扩展头字段（高度、哈希等）
/*        header.setHash(this.hash);
        header.setHeight(this.height);
        header.setMedianTime(this.medianTime);
        header.setChainWork(this.chainWork);
        header.setDifficulty(this.difficulty);
        header.setWitnessSize(this.witnessSize);
        header.setSize(this.size);
        header.setWeight(this.weight);*/

        return header;
    }

    /**
     * 从当前区块拆分出区块体
     */
    public BlockBody extractBody() {
        BlockBody body = new BlockBody();
        body.setTransactions(this.transactions); // 交易列表
        body.setTxCount(this.txCount); // 交易数量
        return body;
    }

    /**
     * 从区块头和区块体合并为完整区块
     */
    public static Block merge(BlockHeader header, BlockBody body,byte[] hash,long  height,long medianTime,byte[] chainWork) {
        Block block = new Block();
        // 复制体字段
        block.setTransactions(body.getTransactions());
        block.setTxCount(body.getTxCount());
        // 复制头字段
        block.setVersion(header.getVersion());
        block.setPreviousHash(header.getPreviousHash());
        block.setMerkleRoot(header.getMerkleRoot());
        block.setTime(header.getTime());
        block.setDifficultyTarget(header.getDifficultyTarget());
        block.setNonce(header.getNonce());
        block.setHash(hash);
        block.setHeight(height);
        block.setMedianTime(medianTime);
        block.setChainWork(chainWork);
        block.setDifficulty(DifficultyUtils.targetToDifficulty(header.getDifficultyTarget()));//难度目标转值
        block.setWitnessSize(calculateBlockWitnessSize(body.getTransactions()));
        block.setSize(calculateAndSetSize(block));//区块大小
        block.setWeight(calculateAndSetWeight(block));//区块权重
        return block;
    }

    // 计算区块的总见证数据大小
    /**
     * 计算区块中所有交易的总见证数据大小（以字节为单位）
     * 见证数据是隔离见证（SegWit）协议中与交易验证相关的附加数据（如签名）
     * @param transactions 区块中的交易列表
     * @return 总见证数据大小（字节）
     */
    public static long calculateBlockWitnessSize(List<Transaction> transactions) {
        // 若交易列表为null，直接返回0（无见证数据）
        if (transactions == null) {
            return 0;
        }
        long totalWitnessSize = 0;
        // 遍历所有交易，累加每个交易的见证数据大小
        for (Transaction transaction : transactions) {
            // 跳过null交易（避免空指针异常）
            if (transaction != null) {
                // 假设Transaction类有getWitnessSize()方法返回当前交易的见证数据大小
                totalWitnessSize += transaction.getWitnessSize();
            }
        }
        return totalWitnessSize;
    }


}
