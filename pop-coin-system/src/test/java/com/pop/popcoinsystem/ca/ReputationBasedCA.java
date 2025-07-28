package com.pop.popcoinsystem.ca;

import com.pop.popcoinsystem.util.CryptoUtil;

import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 去中心化CA系统 - 基于节点信用评级和投票选举
 */
public class ReputationBasedCA {

    // 系统常量
    private static final int CREDIT_SCORE_MAX = 100;         // 最高信用分
    private static final int CHECK_CYCLE_BLOCKS = 100;       // 检查周期(区块数)
    private static final int REQUIRED_FULLNODE_CONFIRMATIONS = 3; // 全节点验证所需确认数
    private static final int MIN_CONSECUTIVE_MAX_SCORE = 3;  // 连续满分次数要求
    private static final int MAX_CA_NODES = 7;                // 最大CA节点数

    // 节点信息类
    static class NodeInfo {
        private final String nodeId;
        private final PublicKey publicKey;
        private int creditScore;
        private int consecutiveMaxScoreCount;
        private boolean isFullNode;
        private long lastCheckedBlock;
        private boolean isCA;

        public NodeInfo(String nodeId, PublicKey publicKey) {
            this.nodeId = nodeId;
            this.publicKey = publicKey;
            this.creditScore = CREDIT_SCORE_MAX; // 初始信用分
            this.consecutiveMaxScoreCount = 0;
            this.isFullNode = false;
            this.lastCheckedBlock = 0;
            this.isCA = false;
        }

        // Getters and setters
        public String getNodeId() { return nodeId; }
        public PublicKey getPublicKey() { return publicKey; }
        public int getCreditScore() { return creditScore; }
        public void setCreditScore(int score) { this.creditScore = score; }
        public int getConsecutiveMaxScoreCount() { return consecutiveMaxScoreCount; }
        public void setConsecutiveMaxScoreCount(int count) { this.consecutiveMaxScoreCount = count; }
        public boolean isFullNode() { return isFullNode; }
        public void setFullNode(boolean fullNode) { isFullNode = fullNode; }
        public long getLastCheckedBlock() { return lastCheckedBlock; }
        public void setLastCheckedBlock(long block) { this.lastCheckedBlock = block; }
        public boolean isCA() { return isCA; }
        public void setCA(boolean isCA) { this.isCA = isCA; }

        @Override
        public String toString() {
            return "Node{id=" + nodeId + ", score=" + creditScore +
                    ", maxScoreCount=" + consecutiveMaxScoreCount +
                    ", fullNode=" + isFullNode + ", isCA=" + isCA + "}";
        }
    }

    // 区块链接口(简化版)
    interface Blockchain {
        long getCurrentBlockHeight();
        boolean isNodeFullNode(String nodeId);
        List<String> getKnownFullNodes();
    }

    // 节点注册表
    private static final Map<String, NodeInfo> nodeRegistry = new ConcurrentHashMap<>();
    private final Blockchain blockchain;
    private long lastElectionBlock = 0;
    private final List<String> currentCAs = new ArrayList<>();

    public ReputationBasedCA(Blockchain blockchain) {
        this.blockchain = blockchain;
    }

    // 1. 节点注册
    public synchronized void registerNode(String nodeId, PublicKey publicKey) {
        if (!nodeRegistry.containsKey(nodeId)) {
            nodeRegistry.put(nodeId, new NodeInfo(nodeId, publicKey));
            System.out.println("节点注册成功: " + nodeId);
        } else {
            System.out.println("节点已注册: " + nodeId);
        }
    }

    // 2. 周期性节点检查
    public synchronized void performPeriodicCheck() {
        long currentBlock = blockchain.getCurrentBlockHeight();
        if (currentBlock - lastElectionBlock < CHECK_CYCLE_BLOCKS) {
            return; // 未到检查周期
        }

        System.out.println("开始周期性节点检查 (区块高度: " + currentBlock + ")");

        // 检查所有节点
        for (NodeInfo node : nodeRegistry.values()) {
            // 跳过已被禁用的节点
            if (node.getCreditScore() <= 0) {
                continue;
            }

            // 更新节点状态
            updateNodeStatus(node);

            // 打印节点状态
            System.out.println("节点状态: " + node);
        }

        // 执行CA选举
        performCAElection();
        lastElectionBlock = currentBlock;
    }

    // 3. 更新节点状态
    private void updateNodeStatus(NodeInfo node) {
        // 验证是否为全节点
        boolean isFullNode = blockchain.isNodeFullNode(node.getNodeId());
        node.setFullNode(isFullNode);

        // 模拟节点行为评分 (实际应用中应基于真实行为数据)
        int behaviorScore = simulateBehaviorScoring(node.getNodeId());

        // 更新信用分
        int newScore = Math.min(CREDIT_SCORE_MAX, Math.max(0, node.getCreditScore() + behaviorScore));
        node.setCreditScore(newScore);

        // 更新连续满分次数
        if (newScore == CREDIT_SCORE_MAX) {
            node.setConsecutiveMaxScoreCount(node.getConsecutiveMaxScoreCount() + 1);
        } else {
            node.setConsecutiveMaxScoreCount(0); // 重置连续满分计数
        }
    }

    // 4. 执行CA选举
    private void performCAElection() {
        System.out.println("开始CA选举...");

        // 筛选符合条件的候选节点
        List<NodeInfo> eligibleCandidates = nodeRegistry.values().stream()
                .filter(node -> node.isFullNode() &&
                        node.getCreditScore() == CREDIT_SCORE_MAX &&
                        node.getConsecutiveMaxScoreCount() >= MIN_CONSECUTIVE_MAX_SCORE)
                .collect(Collectors.toList());

        System.out.println("符合CA资格的节点数: " + eligibleCandidates.size());

        // 如果没有符合条件的节点，继续使用现有CA
        if (eligibleCandidates.isEmpty()) {
            System.out.println("没有符合条件的新CA候选节点");
            return;
        }

        // 排序候选节点 (按信用记录和随机因子)
        eligibleCandidates.sort((n1, n2) -> {
            // 先按连续满分次数降序
            int cmp = Integer.compare(n2.getConsecutiveMaxScoreCount(), n1.getConsecutiveMaxScoreCount());
            if (cmp != 0) return cmp;

            // 再按随机因子排序，确保公平性
            return Double.compare(Math.random(), 0.5);
        });

        // 选出新的CA集合
        List<String> newCAs = eligibleCandidates.stream()
                .limit(MAX_CA_NODES)
                .map(NodeInfo::getNodeId)
                .collect(Collectors.toList());

        // 更新CA状态
        updateCAStatus(newCAs);

        System.out.println("新选举的CA节点: " + newCAs);
    }

    // 5. 更新CA状态
    private void updateCAStatus(List<String> newCAs) {
        // 移除不再是CA的节点
        for (Iterator<String> it = currentCAs.iterator(); it.hasNext(); ) {
            String caId = it.next();
            if (!newCAs.contains(caId)) {
                NodeInfo node = nodeRegistry.get(caId);
                if (node != null) {
                    node.setCA(false);
                }
                it.remove();
            }
        }

        // 添加新的CA节点
        for (String caId : newCAs) {
            if (!currentCAs.contains(caId)) {
                NodeInfo node = nodeRegistry.get(caId);
                if (node != null) {
                    node.setCA(true);
                    currentCAs.add(caId);
                }
            }
        }
    }

    // 6. 模拟节点行为评分 (实际应用中应基于真实行为数据)
    private int simulateBehaviorScoring(String nodeId) {
        // 这里只是模拟评分逻辑，实际应用中应基于节点的真实行为数据
        // 例如: 出块率、网络贡献、数据完整性等

        Random random = new Random(nodeId.hashCode());
        int baseScore = random.nextInt(11) - 5; // -5 到 +5 之间的随机分数

        // 模拟全节点获得额外加分
        if (blockchain.isNodeFullNode(nodeId)) {
            baseScore += 2;
        }

        return baseScore;
    }

    // 7. 获取当前CA列表
    public List<NodeInfo> getCurrentCAs() {
        return currentCAs.stream()
                .map(nodeRegistry::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    // 8. CA颁发证书
    public CACertificate issueCertificate(String issuerId, String subjectId,
                                          PublicKey subjectKey, int validityDays) {
        NodeInfo issuer = nodeRegistry.get(issuerId);

        // 验证颁发者是否为CA
        if (issuer == null || !issuer.isCA()) {
            throw new IllegalArgumentException("颁发者不是有效的CA节点");
        }

        // 生成证书
        return new CACertificate(
                "v2", // 证书版本
                UUID.randomUUID().toString(), // 序列号
                issuerId,
                subjectId,
                new Date(),
                new Date(System.currentTimeMillis() + validityDays * 24 * 60 * 60 * 1000),
                subjectKey.getEncoded(),
                "ECDSA",
                new byte[0] // 简化：实际应使用CA私钥签名
        );
    }

    // 9. 验证证书有效性
    public boolean verifyCertificate(CACertificate cert) {
        // 检查证书签名
        NodeInfo caNode = nodeRegistry.get(cert.getIssuer());
        if (caNode == null || !caNode.isCA()) {
            return false; // 颁发者不是有效CA
        }

        // 检查证书有效期
        Date now = new Date();
        if (now.before(cert.getNotBefore()) || now.after(cert.getNotAfter())) {
            return false; // 证书已过期或尚未生效
        }

        // 实际应用中应验证证书签名
        return true;
    }

    // 示例：创建测试环境
    public static void main(String[] args) throws Exception {
        // 创建模拟区块链
        Blockchain mockBlockchain = new Blockchain() {
            private long currentHeight = 0;

            @Override
            public long getCurrentBlockHeight() {
                return ++currentHeight; // 每次调用增加1
            }

            @Override
            public boolean isNodeFullNode(String nodeId) {
                // 模拟判断：节点ID以"full"开头的为全节点
                return nodeId.startsWith("full");
            }

            @Override
            public List<String> getKnownFullNodes() {
                return nodeRegistry.keySet().stream()
                        .filter(id -> id.startsWith("full"))
                        .collect(Collectors.toList());
            }
        };

        // 创建CA系统
        ReputationBasedCA caSystem = new ReputationBasedCA(mockBlockchain);


        // 注册一些节点(包含全节点和轻节点)
        for (int i = 1; i <= 10; i++) {
            KeyPair keyPair = CryptoUtil.ECDSASigner.generateKeyPair();
            String nodeId = (i <= 5) ? "full-node-" + i : "light-node-" + i;
            caSystem.registerNode(nodeId, keyPair.getPublic());
        }

        // 模拟多个周期的检查和选举
        for (int cycle = 1; cycle <= 10; cycle++) {
            System.out.println("\n===== 周期 " + cycle + " =====");

            // 模拟区块增长到检查点
            while (mockBlockchain.getCurrentBlockHeight() % CHECK_CYCLE_BLOCKS != 0) {
                mockBlockchain.getCurrentBlockHeight();
            }

            // 执行周期性检查和选举
            caSystem.performPeriodicCheck();

            // 打印当前CA列表
            System.out.println("当前CA节点:");
            caSystem.getCurrentCAs().forEach(System.out::println);

            // 模拟证书颁发
            if (!caSystem.getCurrentCAs().isEmpty()) {
                NodeInfo ca = caSystem.getCurrentCAs().get(0);
                NodeInfo subject = caSystem.nodeRegistry.values().stream()
                        .filter(n -> !n.isCA() && n.isFullNode())
                        .findFirst().orElse(null);

                if (subject != null) {
                    CACertificate cert = caSystem.issueCertificate(
                            ca.getNodeId(),
                            subject.getNodeId(),
                            subject.getPublicKey(),
                            30
                    );

                    System.out.println("CA " + ca.getNodeId() + " 颁发证书给 " + subject.getNodeId());
                    System.out.println("证书验证结果: " + caSystem.verifyCertificate(cert));
                }
            }
        }
    }
}