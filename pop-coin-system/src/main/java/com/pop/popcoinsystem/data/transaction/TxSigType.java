package com.pop.popcoinsystem.data.transaction;


import lombok.Getter;

@Getter
public enum TxSigType {
    P2PKH(1),           // Pay to Public Key Hash
    P2SH(2),            // Pay to Script Hash
    P2WPKH(3),          // Pay to Witness Public Key Hash
    P2WSH(4);           // Pay to Witness Script Hash


    private final int value;
    private static final java.util.Map<Integer, TxSigType> map = new java.util.HashMap<>();

    static {
        for (TxSigType type : TxSigType.values()) {
            map.put(type.value, type);
        }
    }

    TxSigType(int value) {
        this.value = value;
    }

    public static TxSigType valueOf(int value) {
        return map.get(value);
    }
}