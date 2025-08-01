package com.pop.popcoinsystem.network.protocol.messageHandler;

import com.pop.popcoinsystem.data.block.Block;
import com.pop.popcoinsystem.exception.UnsupportedChainException;
import com.pop.popcoinsystem.network.KademliaNodeServer;
import com.pop.popcoinsystem.network.common.Bucket;
import com.pop.popcoinsystem.network.common.ExternalNodeInfo;
import com.pop.popcoinsystem.network.common.NodeInfo;
import com.pop.popcoinsystem.network.common.RoutingTable;
import com.pop.popcoinsystem.exception.FullBucketException;
import com.pop.popcoinsystem.network.protocol.message.*;
import com.pop.popcoinsystem.network.protocol.message.content.Handshake;
import com.pop.popcoinsystem.network.protocol.message.content.HeadersRequestParam;
import com.pop.popcoinsystem.service.BlockChainService;
import com.pop.popcoinsystem.util.BeanCopyUtils;
import com.pop.popcoinsystem.util.CryptoUtil;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import java.io.Serializable;
import java.math.BigInteger;
import java.net.ConnectException;
import java.util.Arrays;
import java.util.Date;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

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
        ExternalNodeInfo senderNodeInfo = BeanCopyUtils.copyObject(sender, ExternalNodeInfo.class);
        Handshake handshake = message.getData();
        ExternalNodeInfo data = handshake.getExternalNodeInfo();

        BlockChainService blockChainService = kademliaNodeServer.getBlockChainService();
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

        byte[] localLatestHash  = block.getHash();
        long localLatestHeight  = block.getHeight();

        // 3. 比较差异并发起同步
        compareAndSync(
                kademliaNodeServer,
                sender,
                localLatestHeight,
                localLatestHash,
                remoteLatestHeight,
                remoteLatestHash
        );

        //再请求对方已经知道的节点信息
        FindNodeRequestMessage findNodeRequestMessage = new FindNodeRequestMessage();
        findNodeRequestMessage.setSender(kademliaNodeServer.getNodeInfo());
        findNodeRequestMessage.setReceiver(sender);
        findNodeRequestMessage.setData(kademliaNodeServer.getNodeInfo().getId());
        kademliaNodeServer.getTcpClient().sendMessage(findNodeRequestMessage);
        try {
            log.info("成功更新节点 {} 到路由表", sender.getId());
            //ping消息应该携带 节点基本消息外的额外消息如
            kademliaNodeServer.getRoutingTable().update(data);
        }catch (FullBucketException e){
            kademliaNodeServer.getRoutingTable().forceUpdate(data);
        }
        return new EmptyKademliaMessage();
    }



    /**
     * 比较本地与远程节点的区块差异，并发起同步请求
     */
    private void compareAndSync(KademliaNodeServer nodeServer, NodeInfo remoteNode, long localHeight, byte[] localHash, long remoteHeight, byte[] remoteHash) throws ConnectException, InterruptedException {
        // 情况1：本地链落后于远程节点（需要同步远程的新区块）
        if (localHeight < remoteHeight) {
            log.info("本地链落后（本地高度:{}，远程高度:{}），开始同步", localHeight, remoteHeight);
            // 发送请求：从本地最新区块开始，获取后续所有区块头
            GetHeadersRequestMessage headersRequestMessage = new GetHeadersRequestMessage();
            headersRequestMessage.setSender(nodeServer.getNodeInfo());
            headersRequestMessage.setReceiver(remoteNode);
            // 请求参数：本地最新区块哈希（作为起始点），请求到远程最新区块为止
            HeadersRequestParam headersRequestParam = new HeadersRequestParam(localHash, remoteHash);
            headersRequestMessage.setData(headersRequestParam);
            nodeServer.getTcpClient().sendMessage(headersRequestMessage);
        }
        // 情况2：本地链与远程链高度相同但哈希不同（存在分叉）
        else if (localHeight == remoteHeight && !Arrays.equals(localHash, remoteHash)) {
            log.warn("区块链分叉（本地哈希:{}，远程哈希:{}），开始查找分叉点",
                    CryptoUtil.bytesToHex(localHash),
                    CryptoUtil.bytesToHex(remoteHash)
            );
            // 发送请求：从创世区块开始比对，找到分叉点
            FindForkPointRequestMessage forkRequest = new FindForkPointRequestMessage();
            forkRequest.setSender(nodeServer.getNodeInfo());
            forkRequest.setReceiver(remoteNode);
            forkRequest.setData(CryptoUtil.hexToBytes(GENESIS_BLOCK_HASH_HEX)); // 从创世区块开始查找
            nodeServer.getTcpClient().sendMessage(forkRequest);
        }
        // 情况3：本地链领先或一致（无需同步，或远程会主动请求）
        else {
            log.info("本地链状态正常（本地高度:{}，远程高度:{}），无需同步", localHeight, remoteHeight);
        }
    }




}
