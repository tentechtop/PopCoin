package com.pop.popcoinsystem.network.common;

import com.pop.popcoinsystem.network.enums.NodeStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.math.BigInteger;
import java.util.concurrent.TimeUnit;

@Builder
@Data
@AllArgsConstructor
@NoArgsConstructor
@Slf4j
public class NodeSettings implements Serializable {

    private BigInteger id;//节点ID
    private  String ipv4;//ipv4地址
    //private  String ipv6;//ipv6地址
    private  int udpPort;//UDP端口 用于节点发现
    private  int tcpPort;//TCP端口 用于通信传输
    private int version;//节点版本
    private int score;//分数
    private double neighborAverageScore; //邻居平均分
    private long totalSentBytes;// 发送字节总数
    private long totalReceivedBytes;// 接收字节总数
    private int nodeType;//节点类型
    private int nodeNatType;//节点网络类型
    private double averageResponseTime;       // 平均响应时间
    private int failedConnectionAttempts;     // 失败的连接尝试次数
    private boolean isSeedNode;               // 是否为种子节点
    private NodeStatus nodeStatus;//节点状态

    public String privateKeyHex;
    public String publicKeyHex;



    public int identifierSize;
    /* Maximum size of the buckets */
    public int bucketSize;
    public int findNodeSize;
    public int maximumLastSeenAgeToConsiderAlive;

    public int pingScheduleTimeValue;
    @Builder.Default
    public TimeUnit pingScheduleTimeUnit = TimeUnit.SECONDS;
    public int dhtExecutorPoolSize;
    public int scheduledExecutorPoolSize;
    public boolean enabledFirstStoreRequestForcePass;


    public static class Default {
        public static int IDENTIFIER_SIZE = 160;
        public static int BUCKET_SIZE = 20;
        public static int FIND_NODE_SIZE = 20;
        public static int MAXIMUM_LAST_SEEN_AGE_TO_CONSIDER_ALIVE = 20;

        // V4
        public static int PING_SCHEDULE_TIME_VALUE = 20;
        public static TimeUnit PING_SCHEDULE_TIME_UNIT = TimeUnit.SECONDS;
        public static int DHT_EXECUTOR_POOL_SIZE = 20;
        public static int SCHEDULED_EXECUTOR_POOL_SIZE = 1;
        public static boolean ENABLED_FIRST_STORE_REQUEST_FORCE_PASS = false;


        public static NodeSettings build(){
            return NodeSettings.builder()
                    .identifierSize(IDENTIFIER_SIZE)
                    .bucketSize(BUCKET_SIZE)
                    .findNodeSize(FIND_NODE_SIZE)
                    .maximumLastSeenAgeToConsiderAlive(MAXIMUM_LAST_SEEN_AGE_TO_CONSIDER_ALIVE)
                    .pingScheduleTimeUnit(PING_SCHEDULE_TIME_UNIT)
                    .pingScheduleTimeValue(PING_SCHEDULE_TIME_VALUE)
                    .dhtExecutorPoolSize(DHT_EXECUTOR_POOL_SIZE)
                    .scheduledExecutorPoolSize(SCHEDULED_EXECUTOR_POOL_SIZE)
                    .enabledFirstStoreRequestForcePass(ENABLED_FIRST_STORE_REQUEST_FORCE_PASS)
                    .build();
        }
    }
}
