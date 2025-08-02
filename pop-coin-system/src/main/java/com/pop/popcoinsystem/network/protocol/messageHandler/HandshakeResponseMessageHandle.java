package com.pop.popcoinsystem.network.protocol.messageHandler;

import com.pop.popcoinsystem.data.block.Block;
import com.pop.popcoinsystem.exception.UnsupportedChainException;
import com.pop.popcoinsystem.network.KademliaNodeServer;
import com.pop.popcoinsystem.network.common.ExternalNodeInfo;
import com.pop.popcoinsystem.network.common.NodeInfo;
import com.pop.popcoinsystem.exception.FullBucketException;
import com.pop.popcoinsystem.network.protocol.message.*;
import com.pop.popcoinsystem.network.protocol.messageData.Handshake;
import com.pop.popcoinsystem.service.impl.BlockChainServiceImpl;
import com.pop.popcoinsystem.util.CryptoUtil;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import java.io.Serializable;
import java.net.ConnectException;
import java.util.Arrays;

import static com.pop.popcoinsystem.constant.BlockChainConstants.GENESIS_BLOCK_HASH_HEX;

@Slf4j
public class HandshakeResponseMessageHandle implements MessageHandler{
    @Override
    public KademliaMessage<? extends Serializable> handleMesage(KademliaNodeServer kademliaNodeServer, KademliaMessage<?> message) throws InterruptedException, FullBucketException, ConnectException, UnsupportedChainException {
        return doHandle(kademliaNodeServer, (HandshakeResponseMessage) message);
    }

    protected EmptyKademliaMessage doHandle(KademliaNodeServer kademliaNodeServer, @NotNull HandshakeResponseMessage message) throws InterruptedException, ConnectException, UnsupportedChainException {
        log.info("收到握手响应,验证链信息是否一致");
        NodeInfo sender = message.getSender();//消息来源
        Handshake handshake = message.getData();
        ExternalNodeInfo data = handshake.getExternalNodeInfo();
        try {
            log.info("成功更新节点 {} 到路由表", sender.getId());
            //ping消息应该携带 节点基本消息外的额外消息如
            kademliaNodeServer.getRoutingTable().update(data);
        }catch (FullBucketException e){
            kademliaNodeServer.getRoutingTable().forceUpdate(data);
        }

        BlockChainServiceImpl blockChainService = kademliaNodeServer.getBlockChainService();
        Block block = blockChainService.getMainLatestBlock();

        byte[] genesisBlockHash = handshake.getGenesisBlockHash();
        byte[] genesisHsh = CryptoUtil.hexToBytes(GENESIS_BLOCK_HASH_HEX);
        if (!Arrays.equals(genesisHsh, genesisBlockHash)){
            log.error("链信息不一致");
            //删除节点
            kademliaNodeServer.getRoutingTable().delete(data);
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

        //再发送查找节点的请求
        log.debug("收到响应后再发送查找节点的请求");
        FindNodeRequestMessage findNodeRequestMessage = new FindNodeRequestMessage();
        findNodeRequestMessage.setSender(kademliaNodeServer.getNodeInfo());
        findNodeRequestMessage.setReceiver(sender);
        findNodeRequestMessage.setData(kademliaNodeServer.getNodeInfo().getId());//根据本节点查找
        kademliaNodeServer.getTcpClient().sendMessage(findNodeRequestMessage);
        return null;
    }



}
