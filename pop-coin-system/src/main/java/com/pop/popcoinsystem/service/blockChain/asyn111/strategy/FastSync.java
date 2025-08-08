package com.pop.popcoinsystem.service.blockChain.asyn111.strategy;

/**
 * 为减少全节点的同步时间（尤其是区块链已积累大量数据时），现代区块链（如以太坊）提供快速同步：
 *
 * 不逐块执行历史交易，而是直接从网络获取 “最新的状态快照”（如最新区块对应的全量账户状态），再同步快照之后的区块并执行交易。
 * 优势：跳过历史交易的重复执行，大幅缩短同步时间（从几天缩短到几小时）。
 */
public class FastSync {
}
