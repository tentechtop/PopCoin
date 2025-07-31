package com.pop.popcoinsystem.data.script;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;


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
