package com.pop.popcoinsystem.network.protocol.messageHandler;

import com.pop.popcoinsystem.network.KademliaNodeServer;
import com.pop.popcoinsystem.network.protocol.message.KademliaMessage;

import java.io.Serializable;

public class PingMessageHandler implements MessageHandler {
    @Override
    public <U extends KademliaMessage<? extends Serializable>, O extends KademliaMessage<? extends Serializable>> O handle(KademliaNodeServer kademliaNode, U message) {
        return null;
    }
}
