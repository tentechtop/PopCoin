package com.pop.popcoinsystem.network.common;

import com.pop.popcoinsystem.network.enums.NodeNatType;
import com.pop.popcoinsystem.network.enums.NodeStatus;
import com.pop.popcoinsystem.network.enums.NodeType;
import lombok.*;
import org.jetbrains.annotations.NotNull;

import java.math.BigInteger;
import java.util.Date;



/**
 * 本地信息
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Builder
public class NodeInfo {
    private BigInteger id;//节点ID
    private  String ipv4;//ipv4地址
    //private  String ipv6;//ipv6地址
    private  int udpPort;//UDP端口 用于节点发现
    private  int tcpPort;//TCP端口 用于通信传输



    public ExternalNodeInfo extractExternalNodeInfo() {
        ExternalNodeInfo nodeInfo = new ExternalNodeInfo();
        nodeInfo.setId(id);
        nodeInfo.setIpv4(ipv4);
        nodeInfo.setUdpPort(udpPort);
        nodeInfo.setTcpPort(tcpPort);
        return nodeInfo;
    }

    public NodeInfo(BigInteger id, String ipv4, int udpPort, int tcpPort,boolean isSeedNode) {
        this.id = id;
        this.ipv4 = ipv4;
        this.udpPort = udpPort;
        this.tcpPort = tcpPort;
    }

    public NodeInfo(NodeInfo node, BigInteger xor) {
        this.id = node.getId();
        this.ipv4 = node.getIpv4();
        this.udpPort = node.getUdpPort();
        this.tcpPort = node.getTcpPort();
    }



}
