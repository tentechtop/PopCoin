package com.pop.popcoinsystem.network.protocol.messageHandler;

import com.pop.popcoinsystem.data.block.Block;
import com.pop.popcoinsystem.exception.UnsupportedChainException;
import com.pop.popcoinsystem.network.service.KademliaNodeServer;
import com.pop.popcoinsystem.network.common.ExternalNodeInfo;
import com.pop.popcoinsystem.exception.FullBucketException;
import com.pop.popcoinsystem.network.common.NodeInfo;
import com.pop.popcoinsystem.network.protocol.message.*;
import com.pop.popcoinsystem.network.protocol.messageData.Handshake;
import com.pop.popcoinsystem.service.blockChain.BlockChainServiceImpl;
import com.pop.popcoinsystem.util.BeanCopyUtils;
import com.pop.popcoinsystem.util.CryptoUtil;
import com.pop.popcoinsystem.util.DifficultyUtils;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import java.io.Serializable;
import java.net.ConnectException;
import java.util.Arrays;

import static com.pop.popcoinsystem.constant.BlockChainConstants.GENESIS_PREV_BLOCK_HASH;


@Slf4j
public class HandshakeRequestMessageHandle implements MessageHandler{
    @Override
    public KademliaMessage<? extends Serializable> handleMesage(KademliaNodeServer kademliaNodeServer, KademliaMessage<?> message) throws InterruptedException, FullBucketException, ConnectException, UnsupportedChainException {
        return doHandle(kademliaNodeServer, (HandshakeRequestMessage) message);
    }


    protected HandshakeResponseMessage doHandle(KademliaNodeServer kademliaNodeServer, @NotNull HandshakeRequestMessage message) throws InterruptedException, ConnectException, FullBucketException, UnsupportedChainException {
        log.info("收到握手请求");
        //将该节点添加到路由表中  一定是活跃节点记录下
        NodeInfo sender = message.getSender();
        Handshake handshake = message.getData();
        ExternalNodeInfo externalNodeInfo = handshake.getExternalNodeInfo();//请求方节点信息
        NodeInfo me = kademliaNodeServer.getNodeInfo();
        BlockChainServiceImpl blockChainService = kademliaNodeServer.getBlockChainService();
        Block block = blockChainService.getMainLatestBlock();

        byte[] genesisBlockHash = handshake.getGenesisBlockHash();
        byte[] genesisHsh = kademliaNodeServer.getBlockChainService().GENESIS_BLOCK_HASH();
        if (!Arrays.equals(genesisHsh, genesisBlockHash)){
            log.error("区块链信息不一致，拒绝握手");
            //回复握手失败
            Handshake handshake1 = new Handshake();
            handshake1.setHandshakeSuccess(false);
            handshake1.setErrorMessage("区块链信息不一致，拒绝握手");
            HandshakeResponseMessage handshakeResponseMessage = new HandshakeResponseMessage(handshake1);
            handshakeResponseMessage.setSender(me);
            handshakeResponseMessage.setReceiver(message.getSender());
            kademliaNodeServer.getTcpClient().sendMessage(handshakeResponseMessage);
            kademliaNodeServer.getRoutingTable().delete(externalNodeInfo);
            return null;
        }
        log.info("对方节点类型:{}", externalNodeInfo.getNodeType());
        log.info("握手成功 节点信息:{}", externalNodeInfo);


        //如果对方节点信息不存在就初始化对方的分数信息
        kademliaNodeServer.getRoutingTable().update(externalNodeInfo);//更新对方的节点信息
        //返回握手响应 携带自己的节点信息 和区块链信息 用于对方是否需要同步
        Handshake handshakeResponse = new Handshake();
        handshakeResponse.setExternalNodeInfo(kademliaNodeServer.getExternalNodeInfo());
        handshakeResponse.setGenesisBlockHash(kademliaNodeServer.getBlockChainService().GENESIS_BLOCK_HASH());
        handshakeResponse.setLatestBlockHash(block.getHash());
        handshakeResponse.setLatestBlockHeight(block.getHeight());
        handshakeResponse.setChainWork(block.getChainWork());
        handshakeResponse.setHandshakeSuccess(true);
        HandshakeResponseMessage handshakeResponseMessage = new HandshakeResponseMessage(handshakeResponse);
        handshakeResponseMessage.setSender(me);
        handshakeResponseMessage.setReceiver(message.getSender());
        handshakeResponseMessage.setData(handshakeResponse);
        kademliaNodeServer.getTcpClient().sendMessage(handshakeResponseMessage);
        return null;
    }

}
