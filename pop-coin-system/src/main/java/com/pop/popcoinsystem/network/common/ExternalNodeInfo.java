package com.pop.popcoinsystem.network.common;

import com.pop.popcoinsystem.data.block.BlockHeader;
import com.pop.popcoinsystem.network.enums.NodeNatType;
import com.pop.popcoinsystem.network.enums.NodeStatus;
import com.pop.popcoinsystem.network.enums.NodeType;
import lombok.*;
import org.jetbrains.annotations.NotNull;

import java.io.Serializable;
import java.math.BigInteger;
import java.util.Date;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Builder
public class ExternalNodeInfo implements Comparable<Object>, Serializable {

    private BigInteger id;//节点ID
    private int version;//节点版本
    private  String ipv4;//ipv4地址
    private  String ipv6;//ipv6地址
    private  int udpPort;//UDP端口 用于节点发现
    private  int tcpPort;//TCP端口 用于通信传输
    private Date lastSeen;//最后活跃时间
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
    protected BigInteger distance;//与当前节点的距离
    //节点公钥
    private byte[] publicKey; //公钥byte[]


    public NodeInfo extractNodeInfo() {
        NodeInfo nodeInfo = new NodeInfo();
        nodeInfo.setId(id);
        nodeInfo.setIpv4(ipv4);
        nodeInfo.setUdpPort(udpPort);
        nodeInfo.setTcpPort(tcpPort);
        return nodeInfo;
    }


    public ExternalNodeInfo(BigInteger id, String ipv4, String ipv6, int udpPort, int tcpPort,boolean isSeedNode) {
        this.id = id;
        this.ipv4 = ipv4;
        this.ipv6 = ipv6;
        this.udpPort = udpPort;
        this.tcpPort = tcpPort;
        this.lastSeen = new Date();
        this.score = 0;
        this.totalSentBytes = 0;
        this.totalReceivedBytes = 0;
        this.nodeType = NodeType.FULL.getValue();
        this.nodeNatType = NodeNatType.PUBLIC.getValue();
        this.averageResponseTime = 0;
        this.neighborAverageScore = 0;
        this.failedConnectionAttempts = 0;
        this.isSeedNode = isSeedNode;
        this.nodeStatus = NodeStatus.ACTIVE;
        this.distance = BigInteger.ZERO;
        this.version = 1;
    }

    public ExternalNodeInfo(ExternalNodeInfo node, BigInteger xor) {
        this.distance = xor;
        this.id = node.getId();
        this.ipv4 = node.getIpv4();
        this.ipv6 = node.getIpv6();
        this.udpPort = node.getUdpPort();
        this.tcpPort = node.getTcpPort();
        this.lastSeen = node.getLastSeen();
        this.score = node.getScore();
        this.totalSentBytes = node.getTotalSentBytes();
        this.totalReceivedBytes = node.getTotalReceivedBytes();
        this.nodeType = node.getNodeType();
        this.nodeNatType = node.getNodeNatType();
        this.averageResponseTime = node.getAverageResponseTime();
        this.neighborAverageScore = node.getNeighborAverageScore();
        this.failedConnectionAttempts = node.getFailedConnectionAttempts();
        this.isSeedNode = node.isSeedNode();
        this.nodeStatus = node.getNodeStatus();
        this.version = 1;
    }

    @Override
    public int compareTo(@NotNull Object o) {
        ExternalNodeInfo c = (ExternalNodeInfo) o;
        return distance.compareTo(c.distance);
    }


    //加分 最高分100
    public void addScore(int score) {
        this.score += score;
        if (this.score > 100) {
            this.score = 100;
        }
        if (this.score < -100) {
            this.score = -100;
        }
    }

    //减分 最低分-100
    public void deductScore(int score) {
        this.score -= score;
        if (this.score > 100) {
            this.score = 100;
        }
        if (this.score < -100) {
            this.score = -100;
        }
    }


    public void response(int score) {
        this.score += score;
        if (this.score > 100) {
            this.score = 100;
        }
        if (this.score < -100) {
            this.score = -100;
        }
    }

    public void onSuccessfulResponse(boolean isComplex) {
        this.failedConnectionAttempts = 0;
        int addPoints = isComplex ? 3 : 1;//ping ping 1分 RPC调用3分
        addScore(addPoints);
    }

    public void onFailureResponse(boolean isComplex) {
        this.failedConnectionAttempts = 0;
        int addPoints = isComplex ? 3 : 1;
        deductScore(addPoints);
    }


}
