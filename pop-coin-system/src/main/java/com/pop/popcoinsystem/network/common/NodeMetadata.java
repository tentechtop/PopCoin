package com.pop.popcoinsystem.network.common;

import com.pop.popcoinsystem.network.enums.NodeStatus;
import lombok.*;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Builder
public class NodeMetadata {

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

}
