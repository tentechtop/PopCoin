package com.pop.popcoinsystem.data.script;


import lombok.Getter;

@Getter
public enum AddressType {
    P2PKH(1),           // Pay to Public Key Hash
    P2SH(2),            // Pay to Script Hash
    P2WPKH(3),          // Pay to Witness Public Key Hash
    P2WSH(4);           // Pay to Witness Script Hash


    private final int value;
    private static final java.util.Map<Integer, AddressType> map = new java.util.HashMap<>();

    static {
        for (AddressType type : AddressType.values()) {
            map.put(type.value, type);
        }
    }

    AddressType(int value) {
        this.value = value;
    }

    public static AddressType valueOf(int value) {
        return map.get(value);
    }
}