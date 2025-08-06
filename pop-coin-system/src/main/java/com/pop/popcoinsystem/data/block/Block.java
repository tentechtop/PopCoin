package com.pop.popcoinsystem.data.block;

import com.pop.popcoinsystem.data.transaction.MerklePath;
import com.pop.popcoinsystem.data.transaction.Transaction;
import com.pop.popcoinsystem.util.BeanCopyUtils;
import com.pop.popcoinsystem.util.ByteUtils;
import com.pop.popcoinsystem.util.CryptoUtil;
import com.pop.popcoinsystem.util.DifficultyUtils;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static com.pop.popcoinsystem.util.Numeric.hexStringToByteArray;
import static com.pop.popcoinsystem.util.TypeUtils.reverseBytes;

@Slf4j
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

    // ------------------------------
    // 统一区块哈希计算与POW验证逻辑
    // ------------------------------
    /**
     * 计算区块哈希（统一入口）
     * 基于区块头信息计算双SHA-256哈希
     */
    public byte[] computeHash() {
        BlockHeader header = extractValidBlockHeader();
        return computeBlockHeaderHash(header);
    }
    /**
     * 验证区块POW（统一入口）
     * 检查区块哈希是否满足难度目标要求
     */
    public boolean validatePoW() {
        BlockHeader header = extractValidBlockHeader();
        return validateBlockHeaderPoW(header);
    }

    /**
     * 提取并验证区块头（统一逻辑，供哈希计算和POW验证共用）
     * 确保区块头数据有效性，避免后续计算出错
     */
    private BlockHeader extractValidBlockHeader() {
        // 提取区块头核心字段
        BlockHeader header = new BlockHeader();
        header.setVersion(this.version);
        header.setPreviousHash(this.previousHash);
        header.setMerkleRoot(this.merkleRoot);
        header.setTime(this.time);
        header.setDifficultyTarget(this.difficultyTarget);
        header.setNonce(this.nonce);
        // 统一验证区块头数据有效性
        if (!validateBlockHeaderData(header)) {
            throw new IllegalArgumentException("无效的区块头数据，无法进行哈希计算或POW验证");
        }
        return header;
    }
    /**
     * 计算区块头哈希（核心实现）
     * 双SHA-256 + 小端字节序调整
     */
    public static byte[] computeBlockHeaderHash(BlockHeader header) {
        try {
            // 序列化区块头（严格按协议格式）
            byte[] headerBytes = serializeBlockHeader(header);
            // 双SHA-256哈希
            byte[] hashResult = doubleSHA256(headerBytes);
            // 调整为小端字节序（区块链存储规范）
            return reverseBytes(hashResult);
        } catch (Exception e) {
            log.error("计算区块头哈希失败", e);
            throw new RuntimeException("区块头哈希计算失败", e);
        }
    }


    /**
     * 验证区块头POW（核心实现）
     * 核心逻辑：区块哈希值必须 <= 难度目标值
     */
    public static boolean validateBlockHeaderPoW(BlockHeader header) {
        if (header == null) {
            log.error("POW验证失败：区块头为null");
            return false;
        }

        // 1. 计算区块头哈希（使用统一的哈希计算逻辑）
        byte[] blockHash;
        try {
            blockHash = computeBlockHeaderHash(header);
        } catch (Exception e) {
            log.error("POW验证失败：计算区块哈希出错", e);
            return false;
        }

        // 2. 验证哈希长度（SHA-256哈希固定32字节）
        if (blockHash == null || blockHash.length != 32) {
            log.error("POW验证失败：无效的哈希长度（必须32字节）");
            return false;
        }

        // 3. 验证难度目标（必须4字节压缩格式）
        byte[] targetBytes = header.getDifficultyTarget();
        if (targetBytes == null || targetBytes.length != 4) {
            log.error("POW验证失败：无效的难度目标（必须4字节）");
            return false;
        }

        // 4. 转换为数值并比较（核心验证逻辑）
        try {
            BigInteger target = DifficultyUtils.compactToTarget(targetBytes); // 压缩目标转256位数值
            BigInteger hashValue = new BigInteger(1, blockHash); // 哈希值转为正数
            boolean isValid = hashValue.compareTo(target) <= 0;

            if (!isValid) {
                log.warn("POW验证失败：哈希值({}) > 目标值({})",
                        hashValue.toString(16), target.toString(16));
            }
            return isValid;
        } catch (ArithmeticException e) {
            log.error("POW验证失败：数值计算异常", e);
            return false;
        }
    }


    // ------------------------------
    // 原有辅助方法保持不变（以下为关键方法示例）
    // ------------------------------
    /**
     * 验证区块头字段有效性
     */
    public static boolean validateBlockHeaderData(BlockHeader header) {
        if (header.getPreviousHash() != null && header.getPreviousHash().length != 32) {
            log.error("前区块哈希必须为32字节");
            return false;
        }
        if (header.getMerkleRoot() != null && header.getMerkleRoot().length != 32) {
            log.error("默克尔根必须为32字节");
            return false;
        }
        if (header.getDifficultyTarget() != null && header.getDifficultyTarget().length != 4) {
            log.error("难度目标必须为4字节");
            return false;
        }
        if (header.getVersion() < 0 || header.getTime() < 0 || header.getNonce() < 0) {
            log.error("版本号、时间戳、随机数不能为负数");
            return false;
        }
        return true;
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


    // ------------------------------
    // 默克尔树（Merkle Tree）相关方法
    // 包含：构建默克尔树、计算默克尔根、生成默克尔路径
    // ------------------------------

    /**
     * 计算并设置当前区块的默克尔根
     */
    public void calculateAndSetMerkleRoot() {
        this.merkleRoot = calculateMerkleRoot(this.transactions);
    }


    /**
     * 计算交易列表的默克尔根哈希
     * @param transactions 区块中的交易列表（可为空，空列表返回32字节零数组）
     * @return 默克尔根哈希（32字节数组）
     */
    public static byte[] calculateMerkleRoot(List<Transaction> transactions) {
        if (transactions == null || transactions.isEmpty()) {
            return new byte[32]; // 空交易列表返回零哈希
        }
        // 1. 提取所有交易的哈希（txId）作为默克尔树的叶子节点
        List<byte[]> leafHashes = transactions.stream()
                .map(Transaction::getTxId) // 每个交易的txId作为叶子哈希
                .collect(Collectors.toList());
        // 2. 递归构建默克尔树并返回根哈希
        return buildMerkleTree(leafHashes);
    }

    /**
     * 递归构建默克尔树并返回根哈希
     * 核心逻辑：逐层合并哈希对，每对哈希拼接后做双SHA-256，最终得到根哈希
     * @param hashes 当前层级的哈希列表（初始为叶子节点哈希）
     * @return 默克尔树根哈希（32字节数组）
     */
    private static byte[] buildMerkleTree(List<byte[]> hashes) {
        // 过滤无效哈希（null值）
        List<byte[]> validHashes = hashes.stream()
                .filter(hash -> hash != null)
                .collect(Collectors.toList());

        // 边界情况：空列表返回零哈希
        if (validHashes.isEmpty()) {
            return new byte[32];
        }

        // 边界情况：单哈希直接作为根（如创世块仅含1笔交易）
        if (validHashes.size() == 1) {
            return validHashes.get(0);
        }

        // 构建下一层哈希列表
        List<byte[]> nextLevel = new ArrayList<>();
        for (int i = 0; i < validHashes.size(); i += 2) {
            byte[] left = validHashes.get(i);
            // 若为奇数个哈希，最后一个哈希与自身合并
            byte[] right = (i + 1 < validHashes.size()) ? validHashes.get(i + 1) : left;
            // 拼接哈希对并计算双SHA-256
            byte[] merged = ByteUtils.concat(left, right);
            byte[] parentHash = doubleSHA256(merged);
            nextLevel.add(parentHash);
        }

        // 递归处理下一层
        return buildMerkleTree(nextLevel);
    }

    /**
     * 生成指定交易在当前区块中的默克尔路径
     * 默克尔路径：从交易哈希（叶子节点）到默克尔根的所有兄弟哈希 + 交易索引
     * @param targetTxId 目标交易的txId（需在当前区块的交易列表中）
     * @return MerklePath对象（含路径哈希列表和索引），交易不存在则返回null
     */
    public MerklePath generateMerklePath(byte[] targetTxId) {
        if (transactions == null || transactions.isEmpty()) {
            log.warn("区块中无交易，无法生成默克尔路径");
            return null;
        }

        // 1. 提取所有交易的哈希作为叶子节点
        List<byte[]> leafHashes = new ArrayList<>();
        for (Transaction tx : transactions) {
            leafHashes.add(tx.getTxId());
        }

        // 2. 查找目标交易在叶子节点中的索引
        int index = -1;
        for (int i = 0; i < leafHashes.size(); i++) {
            if (Arrays.equals(leafHashes.get(i), targetTxId)) {
                index = i;
                break;
            }
        }
        if (index == -1) {
            log.warn("交易不在当前区块中，txId: {}", CryptoUtil.bytesToHex(targetTxId));
            return null;
        }
        // 3. 构建完整默克尔树（所有层级的哈希列表）
        List<List<byte[]>> merkleTree = buildFullMerkleTree(leafHashes);
        // 4. 收集从叶子到根的所有兄弟哈希（默克尔路径）
        List<byte[]> pathHashes = new ArrayList<>();
        int currentIndex = index;

        // 遍历每一层（除根节点层）
        for (int level = 0; level < merkleTree.size() - 1; level++) {
            List<byte[]> currentLevel = merkleTree.get(level);
            // 计算当前节点的兄弟节点索引
            int siblingIndex = (currentIndex % 2 == 0) ? currentIndex + 1 : currentIndex - 1;

            // 若兄弟节点不存在（当前层为奇数个节点），用当前节点哈希代替
            if (siblingIndex >= currentLevel.size()) {
                siblingIndex = currentIndex;
            }

            // 添加兄弟哈希到路径
            pathHashes.add(currentLevel.get(siblingIndex));

            // 计算上一层的索引（当前节点在父节点中的位置）
            currentIndex = currentIndex / 2;
        }

        return new MerklePath(pathHashes, index);
    }


    /**
     * 构建完整的默克尔树（包含所有层级的哈希列表）
     * 辅助方法，用于generateMerklePath生成路径时获取各层哈希
     * @param leafHashes 叶子节点哈希列表（交易txId）
     * @return 默克尔树所有层级（从叶子到根）
     */
    private List<List<byte[]>> buildFullMerkleTree(List<byte[]> leafHashes) {
        List<List<byte[]>> tree = new ArrayList<>();
        // 第0层：叶子节点
        tree.add(new ArrayList<>(leafHashes));

        // 逐层构建直到根节点
        while (tree.get(tree.size() - 1).size() > 1) {
            List<byte[]> currentLevel = tree.get(tree.size() - 1);
            List<byte[]> nextLevel = new ArrayList<>();

            for (int i = 0; i < currentLevel.size(); i += 2) {
                byte[] left = currentLevel.get(i);
                byte[] right = (i + 1 < currentLevel.size()) ? currentLevel.get(i + 1) : left;
                byte[] merged = ByteUtils.concat(left, right);
                byte[] parentHash = doubleSHA256(merged);
                nextLevel.add(parentHash);
            }

            tree.add(nextLevel);
        }

        return tree;
    }


    /**
     * 验证交易是否存在于指定区块中（基于默克尔路径和区块头）
     * 核心逻辑：通过交易哈希、默克尔路径计算默克尔根，与区块头中的默克尔根比对
     *
     * @param blockHeader 区块头（含默克尔根，用于验证基准）
     * @param transactionId 待验证交易的ID（txId，32字节哈希）
     * @param merklePath 该交易在区块中的默克尔路径（由generateMerklePath方法生成）
     * @return 验证通过返回true，否则返回false
     */
    public static boolean verifyTransactionInBlock(BlockHeader blockHeader, byte[] transactionId, MerklePath merklePath) {
        // 1. 验证输入参数有效性
        if (blockHeader == null) {
            log.error("验证失败：区块头不能为空");
            return false;
        }
        if (transactionId == null || transactionId.length != 32) {
            log.error("验证失败：交易ID无效（必须为32字节哈希）");
            return false;
        }
        if (merklePath == null) {
            log.error("验证失败：默克尔路径不能为空");
            return false;
        }
        List<byte[]> pathHashes = merklePath.getPathHashes();
        int index = merklePath.getIndex();
        if (pathHashes == null || pathHashes.isEmpty()) {
            // 特殊情况：区块中只有1笔交易时，默克尔路径为空
            log.debug("默克尔路径为空，检查是否为单交易区块");
        } else {
            // 验证路径哈希是否均为32字节
            for (byte[] hash : pathHashes) {
                if (hash == null || hash.length != 32) {
                    log.error("验证失败：默克尔路径包含无效哈希（必须为32字节）");
                    return false;
                }
            }
        }

        // 2. 从区块头获取基准默克尔根
        byte[] merkleRootFromHeader = blockHeader.getMerkleRoot();
        if (merkleRootFromHeader == null || merkleRootFromHeader.length != 32) {
            log.error("验证失败：区块头中的默克尔根无效（必须为32字节）");
            return false;
        }

        // 3. 基于交易哈希和默克尔路径计算默克尔根
        byte[] calculatedRoot = calculateRootFromPath(transactionId, pathHashes, index);
        if (calculatedRoot == null) {
            log.error("验证失败：计算默克尔根时发生错误");
            return false;
        }

        // 4. 比对计算结果与区块头中的默克尔根
        boolean isValid = Arrays.equals(calculatedRoot, merkleRootFromHeader);
        if (!isValid) {
            log.warn("验证失败：计算的默克尔根与区块头不一致\n计算值: {}\n区块头值: {}",
                    CryptoUtil.bytesToHex(calculatedRoot),
                    CryptoUtil.bytesToHex(merkleRootFromHeader));
        }
        return isValid;
    }

    /**
     * 根据交易哈希、默克尔路径和索引计算默克尔根
     *
     * @param transactionHash 交易哈希（叶子节点哈希）
     * @param pathHashes 默克尔路径中的兄弟哈希列表
     * @param index 交易在默克尔树中的索引（叶子节点位置）
     * @return 计算得到的默克尔根（32字节），失败返回null
     */
    private static byte[] calculateRootFromPath(byte[] transactionHash, List<byte[]> pathHashes, int index) {
        if (transactionHash == null || transactionHash.length != 32) {
            log.error("交易哈希无效，无法计算默克尔根");
            return null;
        }
        if (index < 0) {
            log.error("交易索引不能为负数");
            return null;
        }

        // 初始哈希为交易哈希（叶子节点）
        byte[] currentHash = transactionHash;
        int currentIndex = index;

        // 逐层向上计算哈希，直到根节点
        for (byte[] siblingHash : pathHashes) {
            // 根据当前索引判断当前哈希在左还是右
            if (currentIndex % 2 == 0) {
                // 索引为偶数：当前哈希在左，兄弟哈希在右
                currentHash = doubleSHA256(ByteUtils.concat(currentHash, siblingHash));
            } else {
                // 索引为奇数：兄弟哈希在左，当前哈希在右
                currentHash = doubleSHA256(ByteUtils.concat(siblingHash, currentHash));
            }
            // 计算上一层的索引（当前节点在父节点中的位置）
            currentIndex = currentIndex / 2;
        }

        return currentHash;
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

            // 4. 时间戳（8字节，小端）
            long timestamp = blockHeader.getTime();
            if (timestamp < 0) {
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
