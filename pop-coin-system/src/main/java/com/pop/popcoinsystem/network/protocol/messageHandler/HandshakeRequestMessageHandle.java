package com.pop.popcoinsystem.network.protocol.messageHandler;

import com.pop.popcoinsystem.data.block.Block;
import com.pop.popcoinsystem.exception.UnsupportedChainException;
import com.pop.popcoinsystem.network.KademliaNodeServer;
import com.pop.popcoinsystem.network.common.ExternalNodeInfo;
import com.pop.popcoinsystem.exception.FullBucketException;
import com.pop.popcoinsystem.network.common.NodeInfo;
import com.pop.popcoinsystem.network.protocol.message.*;
import com.pop.popcoinsystem.network.protocol.messageData.Handshake;
import com.pop.popcoinsystem.network.protocol.messageData.HeadersRequestParam;
import com.pop.popcoinsystem.service.BlockChainService;
import com.pop.popcoinsystem.util.BeanCopyUtils;
import com.pop.popcoinsystem.util.ByteUtils;
import com.pop.popcoinsystem.util.CryptoUtil;
import com.pop.popcoinsystem.util.DifficultyUtils;
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
        byte[] remoteChainWork = handshake.getChainWork();//工作总量
        byte[] localLatestHash  = block.getHash();
        long localLatestHeight  = block.getHeight();
        byte[] localChainWork = block.getChainWork();


        // 3. 比较差异并发起同步
        compareAndSync(
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

    /**
     * 比较本地与远程节点的区块差异，并发起同步请求
     */
    private void compareAndSync(KademliaNodeServer nodeServer, NodeInfo remoteNode,
                                long localHeight, byte[] localHash, byte[] localWork,
                                long remoteHeight, byte[] remoteHash,byte[] remoteWork
    ) throws ConnectException, InterruptedException {
        // 情况1：远程链工作量更大（无论高度如何，都应同步到工作量更大的链）
        if (DifficultyUtils.compare(localWork, remoteWork)==-1){
            log.info("远程链工作量更大（本地:{}，远程:{}），准备同步", DifficultyUtils.bytesToLong(localWork), DifficultyUtils.bytesToLong(remoteWork));
            if (localHeight < remoteHeight) {
                // 远程链更长且工作量更大 - 从本地最新区块开始同步后续区块
                log.info("远程链更长且工作量更大，请求区块同步");
                sendHeadersRequest(nodeServer, remoteNode, localHash, remoteHash);
            } else if (localHeight == remoteHeight) {
                // 高度相同但工作量不同（分叉）- 从创世区块找分叉点
                log.warn("区块链分叉，远程链工作量更大，查找分叉点");
                sendForkPointRequest(nodeServer, remoteNode);
            } else {
                // 本地链更高但工作量更小（存在无效区块）- 从远程最新区块开始同步
                log.warn("本地链高度更高但工作量更小，可能包含无效区块，请求完整链同步");
                sendHeadersRequest(nodeServer, remoteNode, CryptoUtil.hexToBytes(GENESIS_BLOCK_HASH_HEX), remoteHash);
            }
        }
        // 情况2：本地链工作量更大
        else if (DifficultyUtils.compare(localWork, remoteWork)==1) {
            log.info("本地链工作量更大（本地:{}，远程:{}），无需主动同步", DifficultyUtils.bytesToLong(localWork), DifficultyUtils.bytesToLong(remoteWork));
            // 远程节点会在自己的握手处理中发现差异并请求同步
        }
        // 情况3：工作量相同
        else {
            if (localHeight < remoteHeight) {
                // 工作量相同但远程更长 - 同步新区块
                log.info("本地链落后（本地高度:{}，远程高度:{}），开始同步", localHeight, remoteHeight);
                sendHeadersRequest(nodeServer, remoteNode, localHash, remoteHash);
            } else if (localHeight == remoteHeight && !Arrays.equals(localHash, remoteHash)) {
                // 工作量相同、高度相同但哈希不同（临时分叉，等待更多区块确认）
                log.warn("区块链临时分叉，工作量相同，等待更多区块确认");
            } else {
                // 链状态完全一致
                log.info("链状态完全一致，无需同步");
            }
        }
    }

    /**
     * 发送区块头请求
     */
    private void sendHeadersRequest(KademliaNodeServer nodeServer, NodeInfo remoteNode, byte[] startHash, byte[] endHash)
            throws ConnectException, InterruptedException {
        log.info("发送区块头请求");

        GetHeadersRequestMessage headersRequest = new GetHeadersRequestMessage();
        headersRequest.setSender(nodeServer.getNodeInfo());
        headersRequest.setReceiver(remoteNode);
        headersRequest.setData(new HeadersRequestParam(startHash, endHash));


        //nodeServer.getTcpClient().sendMessage(headersRequest);
    }

    /**
     * 发送分叉点查找请求
     */
    private void sendForkPointRequest(KademliaNodeServer nodeServer, NodeInfo remoteNode)
            throws ConnectException, InterruptedException {
        log.info("发送分叉点查找请求");

        FindForkPointRequestMessage forkRequest = new FindForkPointRequestMessage();
        forkRequest.setSender(nodeServer.getNodeInfo());
        forkRequest.setReceiver(remoteNode);
        forkRequest.setData(CryptoUtil.hexToBytes(GENESIS_BLOCK_HASH_HEX));
        //nodeServer.getTcpClient().sendMessage(forkRequest);
    }

}
