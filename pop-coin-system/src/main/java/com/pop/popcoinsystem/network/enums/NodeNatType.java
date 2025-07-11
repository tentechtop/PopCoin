package com.pop.popcoinsystem.network.enums;

/**
 * 节点网络类型
 */
public enum NodeNatType {
    PUBLIC(0),               // 公网IP
    FULL_CONE(1),            // 完全锥形NAT
    RESTRICTED_CONE(2),      // 受限锥形NAT
    PORT_RESTRICTED_CONE(3), // 端口受限锥形NAT
    SYMMETRIC(4),            // 对称型NAT
    UNKNOWN(5);              // 未知类型

    private final int value;
    private static final java.util.Map<Integer, NodeNatType> map = new java.util.HashMap<>();

    static {
        for (NodeNatType type : NodeNatType.values()) {
            map.put(type.value, type);
        }
    }

    NodeNatType(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }

    public static NodeNatType valueOf(int value) {
        return map.get(value);
    }
}