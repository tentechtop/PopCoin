package com.pop.popcoinsystem.data.script;

public enum ScriptType {
    //    // 脚本类型常量
    //    public static final String TYPE_P2PKH = "pubkeyhash";
    //    public static final String TYPE_P2SH = "scripthash";
    //    public static final String TYPE_P2WPKH = "witness_v0_keyhash";
    //    public static final String TYPE_P2WSH = "witness_v0_scripthash";
    //    public static final String TYPE_OP_RETURN = "nulldata";
    //    public static final String TYPE_MULTISIG = "multisig";
    //    public static final String TYPE_NONSTANDARD = "nonstandard";


    TYPE_P2PKH(1, "pubkeyhash"),
    TYPE_P2SH(2,"scripthash"),
    TYPE_P2WPKH(3, "witness_v0_keyhash"),
    TYPE_P2WSH(4, "witness_v0_scripthash"),

    TYPE_OP_RETURN(5, "nulldata"),
    TYPE_MULTISIG(6, "multisig"),
    TYPE_NONSTANDARD(7, "nonstandard"),

    ;

    private final int value;
    private final String description;
    private static final java.util.Map<Integer, ScriptType> map = new java.util.HashMap<>();

    static {
        for (ScriptType nodeType : ScriptType.values()) {
            map.put(nodeType.value, nodeType);
        }
    }

    ScriptType(int value, String description) {
        this.value = value;
        this.description = description;
    }

    public int getValue() {
        return value;
    }

    public static ScriptType valueOf(int value) {
        return map.get(value);
    }
}