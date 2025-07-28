package com.pop.popcoinsystem.ca;

import com.pop.popcoinsystem.util.CryptoUtil;

import java.security.KeyPair;
import java.security.PublicKey;
import java.util.Date;

/**
 * CA证书体系测试类
 * 演示证书申请、颁发和验证流程
 */
public class CACertificateTest {

    public static void main(String[] args) {
        try {
            System.out.println("===== CA证书测试开始 =====");

            // 1. 初始化CA
            CertificateAuthority ca = new CertificateAuthority();
            System.out.println("CA根密钥对已生成，公钥: " +
                    CryptoUtil.bytesToHex(ca.getCaPublicKey().getEncoded()));

            // 2. 生成测试节点密钥对
            System.out.println("\n=== 测试节点证书申请 ===");
            KeyPair nodeKeyPair = CryptoUtil.ECDSASigner.generateKeyPair();
            System.out.println("测试节点密钥对已生成");

            // 3. 申请并颁发证书(有效期30天)
            CACertificate nodeCert = ca.issueCertificate(
                    "test-node-1",
                    nodeKeyPair.getPublic(),
                    30
            );
            System.out.println("证书颁发成功:");
            System.out.println("- 序列号: " + nodeCert.getSerialNumber());
            System.out.println("- 颁发者: " + nodeCert.getIssuer());
            System.out.println("- 持有者: " + nodeCert.getSubject());
            System.out.println("- 有效期: " + nodeCert.getNotBefore() + " 至 " + nodeCert.getNotAfter());

            // 4. 验证证书有效性
            System.out.println("\n=== 证书验证测试 ===");
            boolean isValid = ca.verifyCertificate(nodeCert);
            System.out.println("证书有效性验证结果: " + (isValid ? "通过" : "失败"));

            // 5. 测试篡改证书内容
            System.out.println("\n=== 篡改证书测试 ===");
            CACertificate tamperedCert = new CACertificate(
                    nodeCert.getVersion(),
                    nodeCert.getSerialNumber(),
                    nodeCert.getIssuer(),
                    "hacker-node",  // 篡改节点标识
                    nodeCert.getNotBefore(),
                    nodeCert.getNotAfter(),
                    nodeCert.getSubjectPublicKey(),
                    nodeCert.getSignatureAlgorithm(),
                    nodeCert.getSignature()
            );
            boolean isTampered = ca.verifyCertificate(tamperedCert);
            System.out.println("篡改后证书验证结果: " + (isTampered ? "通过" : "失败"));

            // 6. 测试节点认证流程
            System.out.println("\n=== 节点认证测试 ===");
            NodeAuthManager authManager = new NodeAuthManager(ca);
            boolean authResult = authManager.authenticateNode(nodeCert);
            System.out.println("节点认证结果: " + (authResult ? "通过" : "失败"));

            // 7. 测试节点权限验证
            PublicKey nodePubKey = nodeKeyPair.getPublic();
            boolean canMine = authManager.isAuthorizedToMine(nodePubKey);
            System.out.println("节点出块权限验证结果: " + (canMine ? "有权限" : "无权限"));

            // 8. 测试吊销证书
            System.out.println("\n=== 证书吊销测试 ===");
            ca.revokeCertificate(nodeCert.getSerialNumber());
            isValid = ca.verifyCertificate(nodeCert);
            canMine = authManager.isAuthorizedToMine(nodePubKey);
            System.out.println("吊销后证书验证结果: " + (isValid ? "通过" : "失败"));
            System.out.println("吊销后节点权限验证结果: " + (canMine ? "有权限" : "无权限"));

            System.out.println("\n===== CA证书测试完成 =====");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // 模拟区块类(用于测试)
    static class MockBlock {
        private byte[] blockContentHash;
        private byte[] minerSignature;

        public byte[] getBlockContentHash() {
            return blockContentHash;
        }

        public void setBlockContentHash(byte[] blockContentHash) {
            this.blockContentHash = blockContentHash;
        }

        public byte[] getMinerSignature() {
            return minerSignature;
        }

        public void setMinerSignature(byte[] minerSignature) {
            this.minerSignature = minerSignature;
        }
    }
}