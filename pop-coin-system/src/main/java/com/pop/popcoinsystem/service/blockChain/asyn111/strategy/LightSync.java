package com.pop.popcoinsystem.service.blockChain.asyn111.strategy;

/**
 * 轻量同步（Light Sync）—— 轻节点 / 客户端专用
 * 轻节点（如手机钱包）无需存储完整数据，仅同步区块头和必要的交易证明，依赖全节点验证数据，适合资源有限的场景。
 *
 * 步骤：
 * 仅同步区块头链，通过区块头中的 Merkle 根哈希验证交易是否存在于某区块（无需下载完整区块体）。
 * 当需要查询特定交易（如自己的转账）时，向全节点请求该交易的 “Merkle 证明”，通过区块头的 Merkle 根验证交易的真实性。
 */
public class LightSync {
}
