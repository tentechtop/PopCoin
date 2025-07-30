package com.pop.popcoinsystem.ca;

import com.pop.popcoinsystem.util.CryptoUtil;
import lombok.extern.slf4j.Slf4j;

import java.security.PublicKey;
import java.util.HashSet;
import java.util.Set;

/**
 * 节点认证管理器
 * 控制合法节点参与共识的权限
 */
@Slf4j
public class NodeAuthManager {
    private final CertificateAuthority ca;
    // 已认证节点列表(公钥->节点ID)
    private final Set<String> authorizedNodes = new HashSet<>();

    public NodeAuthManager(CertificateAuthority ca) {
        this.ca = ca;
    }

    /**
     * 节点加入网络时的认证流程
     * @param nodeCert 节点证书
     * @return 是否允许加入
     */
    public boolean authenticateNode(CACertificate nodeCert) {
        // 1. 验证证书有效性
        if (!ca.verifyCertificate(nodeCert)) {
            return false;
        }

        // 2. 提取节点公钥并记录
        String pubKeyHex = CryptoUtil.bytesToHex(nodeCert.getSubjectPublicKey());
        authorizedNodes.add(pubKeyHex);
        log.info("节点认证通过: {}", nodeCert.getSubject());
        return true;
    }

    /**
     * 验证节点是否有权限出块
     * @param nodePubKey 节点公钥
     * @return 权限验证结果
     */
    public boolean isAuthorizedToMine(PublicKey nodePubKey) {
        return authorizedNodes.contains(
                CryptoUtil.bytesToHex(nodePubKey.getEncoded())
        );
    }

    /**
     * 移除恶意节点权限
     * @param nodePubKey 节点公钥
     */
    public void banNode(PublicKey nodePubKey) {
        String pubKeyHex = CryptoUtil.bytesToHex(nodePubKey.getEncoded());
        authorizedNodes.remove(pubKeyHex);
        log.warn("节点已被封禁: {}", pubKeyHex);
    }
}