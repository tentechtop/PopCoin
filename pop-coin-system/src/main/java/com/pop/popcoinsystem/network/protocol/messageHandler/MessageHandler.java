package com.pop.popcoinsystem.network.protocol.messageHandler;

import com.pop.popcoinsystem.network.service.KademliaNodeServer;
import com.pop.popcoinsystem.network.protocol.message.KademliaMessage;

import java.io.Serializable;

public interface MessageHandler {

    KademliaMessage<? extends Serializable> handleMesage (KademliaNodeServer kademliaNodeServer, KademliaMessage<?> message) throws Exception;



}
