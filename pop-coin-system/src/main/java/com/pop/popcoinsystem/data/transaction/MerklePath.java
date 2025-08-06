package com.pop.popcoinsystem.data.transaction;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 默克尔路径：用于验证交易是否存在于区块中
 * 包含从交易哈希到默克尔根的所有兄弟哈希，以及交易在默克尔树中的索引
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class MerklePath {
    // 默克尔路径中的哈希列表（从叶子节点到根节点的所有兄弟哈希）
    private List<byte[]> pathHashes;
    // 交易在默克尔树中的索引（用于判断当前哈希在左侧还是右侧）
    private int index;
}