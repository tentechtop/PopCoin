package com.pop.popcoinsystem.network.netType;




public enum NATType {
    PUBLIC,               // 公网IP
    FULL_CONE,            // 完全锥形NAT
    RESTRICTED_CONE,      // 受限锥形NAT
    PORT_RESTRICTED_CONE, // 端口受限锥形NAT
    SYMMETRIC,            // 对称型NAT
    UNKNOWN               // 未知类型
}
