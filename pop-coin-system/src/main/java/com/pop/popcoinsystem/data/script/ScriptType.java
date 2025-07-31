package com.pop.popcoinsystem.data.script;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;

/**
 * P2PKH（Pay-to-PubKey-Hash）
 * 锁定脚本格式：OP_DUP OP_HASH160 <公钥哈希> OP_EQUALVERIFY OP_CHECKSIG
 * 解锁脚本需提供：[签名:SigHashType] [公钥]
 * 原理：通过公钥哈希与锁定脚本中的哈希匹配，再用签名验证公钥的所有权，完成解锁。
 * P2SH（Pay-to-Script-Hash）
 * 锁定脚本格式：OP_HASH160 <赎回脚本哈希> OP_EQUAL
 * 解锁脚本需提供：[签名列表:SigHashType] [赎回脚本]
 * 原理：先验证赎回脚本的哈希与锁定脚本中的哈希一致，再执行赎回脚本（如多重签名逻辑），用签名列表满足赎回脚本的验证条件。
 * P2WPKH（Pay-to-Witness-PubKey-Hash，隔离见证）
 * 锁定脚本格式：OP_0 <公钥哈希>（嵌套在 P2SH 中时为 “包裹式 SegWit”）
 * 解锁数据需写入见证区：[签名:SigHashType] [公钥]（按输入顺序通过add(index, element)添加）
 * 原理：隔离见证将解锁数据从脚本区移至见证区，通过公钥哈希匹配 + 签名验证解锁，减少交易重量（降低手续费）。
 * P2WSH（Pay-to-Witness-Script-Hash，隔离见证）
 * 锁定脚本格式：OP_0 <赎回脚本哈希>（嵌套在 P2SH 中时为 “包裹式 SegWit”）
 * 解锁数据需写入见证区：[签名列表:SigHashType] [赎回脚本]（按输入顺序添加）
 * 原理：类似 P2SH，但解锁数据在见证区，兼具复杂脚本功能与 SegWit 的低费、高安全性。
 */


@Data
public enum ScriptType {
    P2PKH(1, "pubkeyhash"),
    P2SH(2, "scripthash"),
    P2WPKH(3, "witness_v0_keyhash"),
    P2WSH(4, "witness_v0_scripthash"),

    OP_RETURN(5, "nulldata"),
    MULTISIG(6, "multisig"),
    NONSTANDARD(7, "nonstandard");

    private final int value;
    private final String description;
    private static final java.util.Map<Integer, ScriptType> map = new java.util.HashMap<>();


    static {
        for (ScriptType type : ScriptType.values()) {
            map.put(type.value, type);
        }
    }


    ScriptType(int value, String description) {
        this.value = value;
        this.description = description;
    }


    public static ScriptType valueOf(int value) {
        return map.get(value);
    }

    public int getValue() {
        return value;
    }

    public String getDescription() {
        return description;
    }
}
