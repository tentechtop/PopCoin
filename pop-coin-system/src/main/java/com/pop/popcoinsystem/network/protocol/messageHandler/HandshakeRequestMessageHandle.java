package com.pop.popcoinsystem.network.protocol.messageHandler;

import com.pop.popcoinsystem.data.block.Block;
import com.pop.popcoinsystem.exception.UnsupportedChainException;
import com.pop.popcoinsystem.network.KademliaNodeServer;
import com.pop.popcoinsystem.network.common.ExternalNodeInfo;
import com.pop.popcoinsystem.exception.FullBucketException;
import com.pop.popcoinsystem.network.common.NodeInfo;
import com.pop.popcoinsystem.network.protocol.message.*;
import com.pop.popcoinsystem.network.protocol.messageData.Handshake;
import com.pop.popcoinsystem.service.impl.BlockChainServiceImpl;
import com.pop.popcoinsystem.util.BeanCopyUtils;
import com.pop.popcoinsystem.util.CryptoUtil;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import java.io.Serializable;
import java.net.ConnectException;
import java.util.Arrays;

import static com.pop.popcoinsystem.constant.BlockChainConstants.GENESIS_BLOCK_HASH_HEX;

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
        ExternalNodeInfo senderNodeInfo = BeanCopyUtils.copyObject(sender, ExternalNodeInfo.class);
        Handshake handshake = message.getData();
        ExternalNodeInfo data = handshake.getExternalNodeInfo();//请求方节点信息
        kademliaNodeServer.getRoutingTable().update(data);//更新对方的节点信息
        NodeInfo me = kademliaNodeServer.getNodeInfo();

        BlockChainServiceImpl blockChainService = kademliaNodeServer.getBlockChainService();
        Block block = blockChainService.getMainLatestBlock();

        byte[] genesisBlockHash = handshake.getGenesisBlockHash();
        byte[] genesisHsh = CryptoUtil.hexToBytes(GENESIS_BLOCK_HASH_HEX);
        if (!Arrays.equals(genesisHsh, genesisBlockHash)){
            log.error("链信息不一致");
            //删除节点
            kademliaNodeServer.getRoutingTable().delete(senderNodeInfo);
            throw new UnsupportedChainException("链信息不一致");
        }

        byte[] remoteLatestHash  = handshake.getLatestBlockHash();
        long remoteLatestHeight  = handshake.getLatestBlockHeight();
        byte[] remoteChainWork = handshake.getChainWork();//工作总量
        byte[] localLatestHash  = block.getHash();
        long localLatestHeight  = block.getHeight();
        byte[] localChainWork = block.getChainWork();


        // 3. 比较差异并发起同步
        kademliaNodeServer.getBlockChainService().compareAndSync(
                kademliaNodeServer,
                sender,
                localLatestHeight,
                localLatestHash,
                localChainWork,
                remoteLatestHeight,
                remoteLatestHash,
                remoteChainWork

        );

        //返回握手响应 携带自己的节点信息 和区块链信息 用于对方是否需要同步
        Handshake handshakeResponse = new Handshake();
        handshakeResponse.setExternalNodeInfo(kademliaNodeServer.getExternalNodeInfo());
        handshakeResponse.setGenesisBlockHash(CryptoUtil.hexToBytes(GENESIS_BLOCK_HASH_HEX));
        handshakeResponse.setLatestBlockHash(block.getHash());
        handshakeResponse.setLatestBlockHeight(block.getHeight());
        handshakeResponse.setChainWork(block.getChainWork());
        HandshakeResponseMessage handshakeResponseMessage = new HandshakeResponseMessage(handshakeResponse);
        handshakeResponseMessage.setSender(me);
        handshakeResponseMessage.setReceiver(message.getSender());
        handshakeResponseMessage.setData(handshakeResponse);
        kademliaNodeServer.getTcpClient().sendMessage(handshakeResponseMessage);
        return null;
    }

}
