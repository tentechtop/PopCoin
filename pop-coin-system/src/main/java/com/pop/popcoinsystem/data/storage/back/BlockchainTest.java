package com.pop.popcoinsystem.data.storage.back;


import java.util.*;

public class BlockchainTest {
    // 区块结构
    static class Block {
        private final String hash;
        private final String parentHash;
        private final long height;
        private final long timestamp;
        private final long difficulty;

        public Block(String hash, String parentHash, long height, long timestamp, long difficulty) {
            this.hash = hash;
            this.parentHash = parentHash;
            this.height = height;
            this.timestamp = timestamp;
            this.difficulty = difficulty;
        }

        public String getHash() {
            return hash;
        }

        public String getParentHash() {
            return parentHash;
        }

        public long getHeight() {
            return height;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public long getDifficulty() {
            return difficulty;
        }

        @Override
        public String toString() {
            return "Block{" +
                    "hash='" + hash + '\'' +
                    ", parentHash='" + parentHash + '\'' +
                    ", height=" + height +
                    ", timestamp=" + timestamp +
                    ", difficulty=" + difficulty +
                    '}';
        }
    }

    // 区块链管理器
    static class BlockchainManager {
        //hash到区块
        private final Map<String, Block> blocks = new HashMap<>();
        //父到子
        private final Map<String, List<String>> parentToChildren = new HashMap<>();
        //高度到哈希
        private final Map<Long, List<String>> heightToHashes = new HashMap<>();
        //分叉高度
        private final Set<Long> forkHeights = new HashSet<>();
        private String bestBlockHash;

        public void addBlock(Block block) {
            // 验证时间戳有效性
/*            if (block.getTimestamp() > System.currentTimeMillis()) {
                throw new IllegalArgumentException("区块时间戳不能晚于当前时间");
            }*/

            String parentHash = block.getParentHash();

            // 检查创世区块
            if (parentHash == null) {
                if (block.getHeight() != 0) {
                    throw new IllegalArgumentException("创世区块高度必须为0");
                }
            } else {
                // 检查父区块是否存在
                Block parentBlock = blocks.get(parentHash);
                if (parentBlock == null) {
                    throw new IllegalArgumentException("父区块不存在: " + parentHash);
                }

                // 验证区块高度是否连续
                if (block.getHeight() != parentBlock.getHeight() + 1) {
                    throw new IllegalArgumentException("区块高度与父区块不连续。父区块高度: " +
                            parentBlock.getHeight() + ", 新区块高度: " + block.getHeight());
                }

                // 验证时间戳顺序
/*                if (block.getTimestamp() <= parentBlock.getTimestamp()) {
                    throw new IllegalArgumentException("区块时间戳必须晚于父区块");
                }*/
            }

            // 添加区块到存储
            blocks.put(block.getHash(), block);

            // 更新高度到哈希的映射
            heightToHashes.computeIfAbsent(block.getHeight(), k -> new ArrayList<>()).add(block.getHash());

            // 更新父区块的子区块列表
            parentToChildren.computeIfAbsent(parentHash, k -> new ArrayList<>()).add(block.getHash());

            // 检查是否形成分叉
            if (heightToHashes.get(block.getHeight()).size() > 1) {
                forkHeights.add(block.getHeight());
            }

            // 更新最佳区块（主链顶端）
            updateBestBlock();
        }

        // 更新最佳区块（修复总难度计算逻辑）
        private void updateBestBlock1() {
            if (blocks.isEmpty()) {
                bestBlockHash = null;
                return;
            }

            // 找到最高的高度
            long maxHeight = 0;
            for (Block block : blocks.values()) {
                maxHeight = Math.max(maxHeight, block.getHeight());
            }

            // 获取该高度上所有区块的哈希
            List<String> topHashes = heightToHashes.getOrDefault(maxHeight, Collections.emptyList());
            if (topHashes.isEmpty()) {
                return;
            }

            // 如果有多个候选，选择难度最大的链
            if (topHashes.size() > 1) {
                String bestHash = null;
                long maxTotalDifficulty = 0;
                for (String hash : topHashes) {
                    long totalDifficulty = calculateTotalDifficulty(hash);
                    System.out.printf("候选链顶端: %s, 高度: %d, 总难度: %d\n",
                            hash, blocks.get(hash).getHeight(), totalDifficulty);
                    if (totalDifficulty > maxTotalDifficulty) {
                        maxTotalDifficulty = totalDifficulty;
                        bestHash = hash;
                    }
                }
                bestBlockHash = bestHash;
                System.out.printf("选择最佳链顶端: %s, 总难度: %d\n", bestHash, maxTotalDifficulty);
            } else {
                bestBlockHash = topHashes.get(0);
            }
        }

        private void updateBestBlock() {
            if (blocks.isEmpty()) {
                bestBlockHash = null;
                return;
            }

            Map<String, Long> chainTotalDifficulty = new HashMap<>();
            // 计算所有链顶端的总难度（顶端指没有子区块的区块）
            for (String hash : blocks.keySet()) {
                if (!parentToChildren.containsKey(hash) || parentToChildren.get(hash).isEmpty()) {
                    chainTotalDifficulty.put(hash, calculateTotalDifficulty(hash));
                }
            }

            // 排序：先按总难度降序，再按高度降序
            List<Map.Entry<String, Long>> sortedChains = new ArrayList<>(chainTotalDifficulty.entrySet());
            sortedChains.sort((a, b) -> {
                if (!a.getValue().equals(b.getValue())) {
                    return b.getValue().compareTo(a.getValue()); // 总难度高的优先
                } else {
                    // 总难度相同时，高度高的优先
                    return Long.compare(blocks.get(b.getKey()).getHeight(), blocks.get(a.getKey()).getHeight());
                }
            });

            bestBlockHash = sortedChains.get(0).getKey();
        }




        public long getMainChainHeight() {
            Block bestBlock = blocks.get(bestBlockHash);
            return bestBlock != null ? bestBlock.getHeight() : 0;
        }

        public Set<Long> getForkHeights() {
            return forkHeights;
        }

        public String getBestBlockHash() {
            return bestBlockHash;
        }

        private long calculateTotalDifficulty1(String blockHash) {
            long totalDifficulty = 0;
            String currentHash = blockHash;

            while (currentHash != null) {
                Block block = blocks.get(currentHash);
                if (block == null) {
                    break;
                }
                totalDifficulty += block.getDifficulty();
                currentHash = block.getParentHash();
            }

            return totalDifficulty;
        }


        // 1. 修复总难度计算的边界校验（避免漏算）
        private long calculateTotalDifficulty(String blockHash) {
            long totalDifficulty = 0;
            String currentHash = blockHash;

            while (currentHash != null) {
                Block block = blocks.get(currentHash);
                if (block == null) {
                    // 若中间区块缺失，该链为无效链，总难度归零
                    return 0;
                }
                totalDifficulty += block.getDifficulty();
                currentHash = block.getParentHash();
            }

            return totalDifficulty;
        }









        public String getMainChainBlockHashAtHeight(long height) {
            // 获取主链顶端区块
            String currentHash = bestBlockHash;
            if (currentHash == null) {
                return null;
            }

            // 从顶端开始回溯到指定高度
            while (currentHash != null) {
                Block block = blocks.get(currentHash);
                if (block == null) {
                    break;
                }

                if (block.getHeight() == height) {
                    return currentHash;
                } else if (block.getHeight() < height) {
                    break;
                }

                currentHash = block.getParentHash();
            }

            return null;
        }

        public void cleanupDiscardedChainsByDifficulty() {
            String mainChainTip = getBestBlockHash();
            if (mainChainTip == null) {
                return;
            }
            // 1. 标记主链区块
            Set<String> mainChainHashes = new HashSet<>();
            String currentHash = mainChainTip;
            while (currentHash != null) {
                mainChainHashes.add(currentHash);
                Block currentBlock = blocks.get(currentHash);
                if (currentBlock == null) break;
                currentHash = currentBlock.getParentHash();
            }

            // 2. 收集所有需要删除的区块（非主链区块）
            Set<String> blocksToRemove = new HashSet<>();
            for (String hash : blocks.keySet()) {
                if (!mainChainHashes.contains(hash)) {
                    blocksToRemove.add(hash);
                }
            }

            // 3. 执行清理
            for (String hash : blocksToRemove) {
                Block block = blocks.remove(hash);
                if (block == null) continue;

                // 更新高度-哈希映射
                List<String> hashesAtHeight = heightToHashes.get(block.getHeight());
                if (hashesAtHeight != null) {
                    hashesAtHeight.remove(hash);
                    if (hashesAtHeight.isEmpty()) {
                        heightToHashes.remove(block.getHeight());
                    }
                }

                // 更新父-子映射
                String parentHash = block.getParentHash();
                List<String> siblings = parentToChildren.get(parentHash);
                if (siblings != null) {
                    siblings.remove(hash);
                    if (siblings.isEmpty()) {
                        parentToChildren.remove(parentHash);
                    }
                }
            }
            // 4. 更新分叉高度集合
            forkHeights.clear();
            for (Map.Entry<Long, List<String>> entry : heightToHashes.entrySet()) {
                if (entry.getValue().size() > 1) {
                    forkHeights.add(entry.getKey());
                }
            }
        }

        public void printChainStructure() {
            System.out.println("\n区块链结构:");
            long currentHeight = 0;
            String currentHash = getMainChainBlockHashAtHeight(currentHeight);

            while (currentHash != null) {
                Block block = blocks.get(currentHash);
                if (block == null) break;

                // 打印主链区块
                System.out.printf("高度 %d: %s (难度: %d)\n",
                        block.getHeight(), block.getHash(), block.getDifficulty());

                // 检查是否存在分叉
                List<String> siblings = heightToHashes.get(block.getHeight());
                if (siblings != null && siblings.size() > 1) {
                    System.out.printf("  └── 分叉: ");
                    for (String siblingHash : siblings) {
                        if (!siblingHash.equals(currentHash)) {
                            Block sibling = blocks.get(siblingHash);
                            System.out.printf("%s (难度: %d) ", siblingHash, sibling.getDifficulty());
                        }
                    }
                    System.out.println();
                }

                // 移动到下一个高度
                currentHeight++;
                currentHash = getMainChainBlockHashAtHeight(currentHeight);
            }
        }
    }

    // 主函数测试
    public static void main(String[] args) throws InterruptedException {
        BlockchainManager manager = new BlockchainManager();
        long now = System.currentTimeMillis();

        // 创建创世块
        manager.addBlock(new Block("genesis", null, 0, now - 25 * 60 * 60 * 1000, 1));

        // 创建链A（高度1-11）
        long timeA = now - 24 * 60 * 60 * 1000;
        manager.addBlock(new Block("A1", "genesis", 1, timeA + 100, 2));
        manager.addBlock(new Block("A2", "A1", 2, timeA + 200, 2));
        manager.addBlock(new Block("A3", "A2", 3, timeA + 300, 2));
        manager.addBlock(new Block("A4", "A3", 4, timeA + 400, 2));
        manager.addBlock(new Block("A5", "A4", 5, timeA + 500, 2));
        manager.addBlock(new Block("A6", "A5", 6, timeA + 600, 2));
        manager.addBlock(new Block("A7", "A6", 7, timeA + 700, 2));
        manager.addBlock(new Block("A8", "A7", 8, timeA + 800, 2));
        manager.addBlock(new Block("A9", "A8", 9, timeA + 900, 2));
        manager.addBlock(new Block("A10", "A9", 10, timeA + 1000, 2));
        manager.addBlock(new Block("A11", "A10", 11, timeA + 1100, 2));

        // 创建链B（高度3-23，主链）
        long timeB = now;
        manager.addBlock(new Block("B3", "A2", 3, timeB + 350, 20));
        manager.addBlock(new Block("B4", "B3", 4, timeB + 450, 20));
        manager.addBlock(new Block("B5", "B4", 5, timeB + 550, 20));
        manager.addBlock(new Block("B6", "B5", 6, timeB + 650, 20));
        manager.addBlock(new Block("B7", "B6", 7, timeB + 750, 20));
        manager.addBlock(new Block("B8", "B7", 8, timeB + 850, 20));
        manager.addBlock(new Block("B9", "B8", 9, timeB + 950, 20));
        manager.addBlock(new Block("B10", "B9", 10, timeB + 1050, 20));
        manager.addBlock(new Block("B11", "B10", 11, timeB + 1150, 20));
        manager.addBlock(new Block("B12", "B11", 12, timeB + 1250, 20));
        manager.addBlock(new Block("B13", "B12", 13, timeB + 1350, 20));
        manager.addBlock(new Block("B14", "B13", 14, timeB + 1450, 20));
        manager.addBlock(new Block("B15", "B14", 15, timeB + 1550, 20));
        manager.addBlock(new Block("B16", "B15", 16, timeB + 1650, 20));
        manager.addBlock(new Block("B17", "B16", 17, timeB + 1750, 20));
        manager.addBlock(new Block("B18", "B17", 18, timeB + 1850, 20));
        manager.addBlock(new Block("B19", "B18", 19, timeB + 1950, 20));
        manager.addBlock(new Block("B20", "B19", 20, timeB + 2050, 20));
        manager.addBlock(new Block("B21", "B20", 21, timeB + 2150, 20));
        manager.addBlock(new Block("B22", "B21", 22, timeB + 2250, 20));
        manager.addBlock(new Block("B23", "B22", 23, timeB + 2350, 20));

        // 创建链C（高度3-24，难度较低）
        long timeC = now - 23 * 60 * 60 * 1000;
        manager.addBlock(new Block("C3", "A2", 3, timeC + 320, 3));
        manager.addBlock(new Block("C4", "C3", 4, timeC + 420, 3));
        manager.addBlock(new Block("C5", "C4", 5, timeC + 520, 3));
        manager.addBlock(new Block("C6", "C5", 6, timeC + 620, 3));
        manager.addBlock(new Block("C7", "C6", 7, timeC + 720, 3));
        manager.addBlock(new Block("C8", "C7", 8, timeC + 820, 3));
        manager.addBlock(new Block("C9", "C8", 9, timeC + 920, 3));
        manager.addBlock(new Block("C10", "C9", 10, timeC + 1020, 3));
        manager.addBlock(new Block("C11", "C10", 11, timeC + 1120, 3));
        manager.addBlock(new Block("C12", "C11", 12, timeC + 1220, 3));
        manager.addBlock(new Block("C13", "C12", 13, timeC + 1320, 2));
        manager.addBlock(new Block("C14", "C13", 14, timeC + 1420, 2));
        manager.addBlock(new Block("C15", "C14", 15, timeC + 1520, 2));
        manager.addBlock(new Block("C16", "C15", 16, timeC + 1620, 2));
        manager.addBlock(new Block("C17", "C16", 17, timeC + 1720, 2));
        manager.addBlock(new Block("C18", "C17", 18, timeC + 1820, 2));
        manager.addBlock(new Block("C19", "C18", 19, timeC + 1920, 2));
        manager.addBlock(new Block("C20", "C19", 20, timeC + 2020, 2));
        manager.addBlock(new Block("C21", "C20", 21, timeC + 2120, 2));
        manager.addBlock(new Block("C22", "C21", 22, timeC + 2220, 2));
        manager.addBlock(new Block("C23", "C22", 23, timeC + 2320, 2));
        manager.addBlock(new Block("C24", "C23", 24, timeC + 2420, 2));

        // 创建链D（高度20-21，从C19分叉）
        long timeD = now - 23 * 60 * 60 * 1000;
        manager.addBlock(new Block("D20", "C19", 20, timeD + 320, 3));
        manager.addBlock(new Block("D21", "D20", 21, timeD + 420, 3));

        // 打印初始状态
        System.out.println("\n初始状态：");
        System.out.println("主链高度: " + manager.getMainChainHeight());
        System.out.println("分叉高度: " + manager.getForkHeights());
        System.out.println("主链顶端: " + manager.getBestBlockHash());

        String mainChainBlockHashAtHeight3 = manager.getMainChainBlockHashAtHeight(3);
        System.out.println("高度3的主链区块: " + mainChainBlockHashAtHeight3);
        String mainChainBlockHashAtHeight18 = manager.getMainChainBlockHashAtHeight(18);
        System.out.println("高度18的主链区块: " + mainChainBlockHashAtHeight18);

        // 打印完整链结构
        manager.printChainStructure();

        // 执行清理
        System.out.println("\n执行清理...");
        manager.cleanupDiscardedChainsByDifficulty();

        // 打印清理后状态
        System.out.println("\n清理后状态：");
        System.out.println("主链高度: " + manager.getMainChainHeight());
        System.out.println("分叉高度: " + manager.getForkHeights());
        System.out.println("剩余区块数量: " + manager.blocks.size());

        // 打印清理后的链结构
        manager.printChainStructure();
    }
}