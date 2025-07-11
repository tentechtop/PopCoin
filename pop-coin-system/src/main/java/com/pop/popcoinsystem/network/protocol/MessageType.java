package com.pop.popcoinsystem.network.protocol;

import io.netty.channel.Channel;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import java.util.HashMap;
import java.util.Map;

public enum MessageType {
    EMPTY(0, "空消息"),
    FIND_NODE_REQ(1, "寻找节点请求"),
    FIND_NODE_RES(2, "寻找节点响应"),
    PING(3, "ping"),
    PONG(4, "pong"),
    SHUTDOWN(5, "关闭"),
    TRANSACTION(10, "交易广播消息"),
    BLOCK(11, "区块广播消息"),
    GET_BLOCK_HEADERS_REQ(20, "索取区块头数据请求"),
    GET_BLOCK_HEADERS_RES(21, "索取区块头数据响应"),
    GET_BLOCK_REQ(22, "索取区块数据请求"),
    GET_BLOCK_RES(23, "索取区块数据响应"),
    GET_BLOCK_CHAIN_REQ(24, "索取/同步区块链请求"),
    GET_BLOCK_CHAIN_RES(25, "索取/同步区块链响应"),
    GET_BALANCE_REQ(30, "查询钱包余额请求"),
    GET_BALANCE_RES(31, "查询钱包余额响应"),
    GET_TRANSACTION_RECORD_REQ(32, "查询交易记录请求"),
    GET_TRANSACTION_RECORD_RES(33, "查询交易记录响应"),

    HANDSHAKE_REQ(34, "握手请求"),
    HANDSHAKE_RES(35, "握手响应"),




    ;


    private final int code;
    private final String description;
    private static final Map<Integer, MessageType> codeMap = new HashMap<>();

    static {
        for (MessageType type : MessageType.values()) {
            codeMap.put(type.code, type);
        }
    }

    public static String getDescriptionByCode(int code){
        return codeMap.get(code).getDescription();
    }

    MessageType(int code, String description) {
        this.code = code;
        this.description = description;
    }

    public int getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public static MessageType fromCode(int code) {
        return codeMap.get(code);
    }
}