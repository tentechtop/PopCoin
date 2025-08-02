package com.pop.popcoinsystem.network.protocol.messageHandler;

import com.pop.popcoinsystem.exception.FullBucketException;
import com.pop.popcoinsystem.exception.UnsupportedChainException;
import com.pop.popcoinsystem.network.service.KademliaNodeServer;
import com.pop.popcoinsystem.network.protocol.message.*;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import java.io.Serializable;
import java.net.ConnectException;

@Slf4j
public class FindForkPointResponseMessageHandle implements MessageHandler{
    @Override
    public KademliaMessage<? extends Serializable> handleMesage(KademliaNodeServer kademliaNodeServer, KademliaMessage<?> message) throws InterruptedException, FullBucketException, ConnectException, UnsupportedChainException {
        return doHandle(kademliaNodeServer, (FindForkPointResponseMessage) message);
    }


    protected FindForkPointResponseMessage doHandle(KademliaNodeServer kademliaNodeServer, @NotNull FindForkPointResponseMessage message) throws InterruptedException {
        log.info("收到分叉点响应");//从哪里开始分叉


        //这里要返回数据
        return null;
    }
}
