package com.pop.popcoinsystem.network.protocol.messageHandler;

import com.pop.popcoinsystem.exception.UnsupportedChainException;
import com.pop.popcoinsystem.network.KademliaNodeServer;
import com.pop.popcoinsystem.exception.FullBucketException;
import com.pop.popcoinsystem.network.protocol.message.KademliaMessage;

import java.io.Serializable;
import java.net.ConnectException;
import java.util.concurrent.ExecutionException;

public interface MessageHandler {

    KademliaMessage<? extends Serializable> handleMesage (KademliaNodeServer kademliaNodeServer, KademliaMessage<?> message) throws InterruptedException, FullBucketException, ConnectException, UnsupportedChainException, ExecutionException;



}
