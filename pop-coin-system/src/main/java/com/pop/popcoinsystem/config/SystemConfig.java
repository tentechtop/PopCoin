package com.pop.popcoinsystem.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * #测试难度  0000ffffffff0000000000000000000000000000000000000000000000000000
 * #测试难度  ffffffffffff0000000000000000000000000000000000000000000000000000
 * #没有私钥的只能调用普通接口 和区块的基本信息接口 以及交易验证和同步数据等服务
 */
@Data
@Component
@ConfigurationProperties(prefix = "popcoin")
public class SystemConfig {

    // 节点公钥hash（支持多个，空格分隔）
    private String publickey = "";

    // 节点网络版本（1主网，2测试网1，3测试网2）
    private int netversion = 1;

    // 节点类型（0全节点，1轻节点，2仅出站节点）
    private int nodetype = 0;

    // 创世区块hash
    private String genesisblockhash = "000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f";

    // 初始难度目标（难度1对应的目标值）
    private String initDifficulty = "ffffffffffff0000000000000000000000000000000000000000000000000000";

    // TCP端口
    private int tcpPort = 8334;

    // UDP端口
    private int udpPort = 8333;


}
