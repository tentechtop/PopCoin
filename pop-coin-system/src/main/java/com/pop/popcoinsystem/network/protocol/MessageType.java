package com.pop.popcoinsystem.network.protocol;

import io.netty.channel.Channel;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public abstract class MessageType {
    public static final int EMPTY = 0;                // 空消息
    public static final int FIND_NODE_REQ = 1;        // 寻找节点请求
    public static final int FIND_NODE_RES = 2;        // 寻找节点响应
    public static final int PING = 3;                 // ping
    public static final int PONG = 4;                 // pong
    public static final int SHUTDOWN = 5;             // 关闭

    public static final int TRANSACTION = 10;         // 交易广播消息
    public static final int BLOCK = 11;               // 区块广播消息

    public static final int GET_BLOCK_HEADERS_REQ = 20; // 索取区块头数据请求 (从哪里到哪里的 0-1000)
    public static final int GET_BLOCK_HEADERS_RES = 21; // 索取区块头数据响应

    public static final int GET_BLOCK_REQ = 22;       // 索取区块数据请求
    public static final int GET_BLOCK_RES = 23;       // 索取区块数据响应

    public static final int GET_BLOCK_CHAIN_REQ = 24; // 索取/同步区块链请求
    public static final int GET_BLOCK_CHAIN_RES = 25; // 索取/同步区块链响应

    public static final int GET_BALANCE_REQ = 30;     // 查询钱包余额请求 (查询公钥地址的UTXO)
    public static final int GET_BALANCE_RES = 31;     // 查询钱包余额响应

    public static final int GET_TRANSACTION_RECORD_REQ = 32; // 查询交易记录请求
    public static final int GET_TRANSACTION_RECORD_RES = 33; // 查询交易记录响应

    public static final Map<Integer, String> MessageTypeMap = new HashMap<>();

    static {
        MessageTypeMap.put(EMPTY, "EMPTY");
        MessageTypeMap.put(FIND_NODE_REQ, "FIND_NODE_REQ");
        MessageTypeMap.put(FIND_NODE_RES, "FIND_NODE_RES");
        MessageTypeMap.put(PING, "PING");
        MessageTypeMap.put(PONG, "PONG");
        MessageTypeMap.put(SHUTDOWN, "SHUTDOWN");
        MessageTypeMap.put(TRANSACTION, "TRANSACTION");
        MessageTypeMap.put(BLOCK, "BLOCK");
        MessageTypeMap.put(GET_BLOCK_HEADERS_REQ, "GET_BLOCK_HEADERS_REQ");
        MessageTypeMap.put(GET_BLOCK_HEADERS_RES, "GET_BLOCK_HEADERS_RES");
        MessageTypeMap.put(GET_BLOCK_REQ, "GET_BLOCK_REQ");
        MessageTypeMap.put(GET_BLOCK_RES, "GET_BLOCK_RES");
        MessageTypeMap.put(GET_BLOCK_CHAIN_REQ, "GET_BLOCK_CHAIN_REQ");
        MessageTypeMap.put(GET_BLOCK_CHAIN_RES, "GET_BLOCK_CHAIN_RES");
        MessageTypeMap.put(GET_BALANCE_REQ, "GET_BALANCE_REQ");
        MessageTypeMap.put(GET_BALANCE_RES, "GET_BALANCE_RES");
        MessageTypeMap.put(GET_TRANSACTION_RECORD_REQ, "GET_TRANSACTION_RECORD_REQ");
        MessageTypeMap.put(GET_TRANSACTION_RECORD_RES, "GET_TRANSACTION_RECORD_RES");
    }


}
