package com.pop.popcoinsystem.network.enums;

/**
 * 节点网络类型
 */
public enum NodeNatType {
    PUBLIC,               // 公网IP
    FULL_CONE,            // 完全锥形NAT
    RESTRICTED_CONE,      // 受限锥形NAT
    PORT_RESTRICTED_CONE, // 端口受限锥形NAT
    SYMMETRIC,            // 对称型NAT
    UNKNOWN               // 未知类型
}
