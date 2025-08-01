package com.pop.popcoinsystem.network.enums;

public enum NodeType {
    FULL(1),      // 全节点
    LIGHT(2),     // 轻节点
    OUTBOUND(3);  // 仅出站

    private final int value;
    private static final java.util.Map<Integer, NodeType> map = new java.util.HashMap<>();

    static {
        for (NodeType nodeType : NodeType.values()) {
            map.put(nodeType.value, nodeType);
        }
    }

    NodeType(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }

    public static NodeType valueOf(int value) {
        return map.get(value);
    }
}