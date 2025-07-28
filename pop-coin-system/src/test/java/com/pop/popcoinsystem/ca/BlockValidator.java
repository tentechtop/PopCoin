package com.pop.popcoinsystem.ca;

import com.pop.popcoinsystem.data.block.Block;
import com.pop.popcoinsystem.util.CryptoUtil;
import lombok.extern.slf4j.Slf4j;

import java.security.PublicKey;

/**
 * 区块验证工具类
 * 增强抗51%攻击的验证逻辑
 */
@Slf4j
public class BlockValidator {
    private final NodeAuthManager authManager;

    public BlockValidator(NodeAuthManager authManager) {
        this.authManager = authManager;
    }

    /**
     * 验证区块合法性
     * @param block 待验证区块
     * @param minerPubKey 出块节点公钥
     * @return 验证结果
     */
    public boolean validateBlock(Block block, PublicKey minerPubKey) {
        // 1. 验证出块节点权限
        if (!authManager.isAuthorizedToMine(minerPubKey)) {
            log.warn("非法节点出块，公钥: {}", CryptoUtil.bytesToHex(minerPubKey.getEncoded()));
            return false;
        }

/*        // 2. 验证区块签名(确保区块未被篡改)
        boolean sigValid = CryptoUtil.ECDSASigner.verifySignature(
                minerPubKey,
                block.getBlockContentHash(),  // 区块内容哈希
                block.getMinerSignature()     // 出块节点签名
        );

        if (!sigValid) {
            log.warn("区块签名验证失败");
        }
        return sigValid;*/
        return true;
    }
}