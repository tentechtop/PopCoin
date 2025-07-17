package com.pop.popcoinsystem.network.enums;

import lombok.Getter;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * 魔术数用于网络层的数据包验证。
 * 地址前缀用于地址格式的验证和解析。
 * 魔术数通常是 4 字节，而地址前缀是 1 字节。
 */

@Getter
public enum NETVersion {
    MAIN(1, new byte[]{0x00}, (byte) 0x00,(byte) 0x05,"主网"),
    TEST(2, new byte[]{0x6f}, (byte) 0x00,(byte) 0x05,"测试网"),
    PRIVATE(3, new byte[]{0x05}, (byte) 0x00,(byte) 0x05,"私有网络")
    ;

    private final int version;
    private final byte[] magic;
    private final byte p2pkhPreAddress;
    private final byte p2shPreAddress;
    private final String description;


    private static final Map<Integer, NETVersion> VERSION_MAP = new HashMap<>();
    static {
        for (NETVersion v : NETVersion.values()) {
            VERSION_MAP.put(v.version, v);
        }
    }



    NETVersion(int version,byte[] magic,byte p2pkhPreAddress,byte p2shPreAddress,String description) {
        this.version = version;
        this.magic = magic;
        this.p2pkhPreAddress = p2pkhPreAddress;
        this.p2shPreAddress = p2shPreAddress;
        this.description = description;
    }


    //根据网络获取魔术数
    public static byte[] getMagic(int version) {
        NETVersion v = VERSION_MAP.get(version);
        if (v == null) {
            throw new IllegalArgumentException("Invalid network version: " + version);
        }
        return v.magic;
    }

    //获取p2pkh前缀
    public static byte getP2PKHPreAddress(int version) {
        NETVersion v = VERSION_MAP.get(version);
        if (v == null) {
            throw new IllegalArgumentException("Invalid network version: " + version);
        }
        return v.p2pkhPreAddress;
    }

    //获取p2sh前缀
    public static byte getP2SHPreAddress(int version) {
        NETVersion v = VERSION_MAP.get(version);
        if (v == null) {
            throw new IllegalArgumentException("Invalid network version: " + version);
        }
        return v.p2shPreAddress;
    }











}