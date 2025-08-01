package com.pop.popcoinsystem.network.common;

import lombok.Data;

import java.math.BigInteger;

@Data
public class BootstrapNode {
    private String ip;
    private int tcpPort;
    private int udpPort;
    private BigInteger nodeId;
}
