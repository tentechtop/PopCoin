package com.pop.popcoinsystem;

import com.pop.popcoinsystem.network.service.KademliaNodeServer;
import com.pop.popcoinsystem.network.service.UDPClient;
import com.pop.popcoinsystem.network.common.NodeInfo;
import com.pop.popcoinsystem.network.protocol.message.PingKademliaMessage;
import lombok.extern.slf4j.Slf4j;

import java.math.BigInteger;

import static java.lang.Thread.sleep;


@Slf4j
public class myTest {
    public static void main(String[] args)  {
        try{
            KademliaNodeServer kademliaNodeServer = new KademliaNodeServer(BigInteger.valueOf(1L), "127.0.0.1", 8333, 8334);
            NodeInfo nodeInfo1 = kademliaNodeServer.getNodeInfo();





        }catch (Exception e){
            e.printStackTrace();
        }
    }
}
