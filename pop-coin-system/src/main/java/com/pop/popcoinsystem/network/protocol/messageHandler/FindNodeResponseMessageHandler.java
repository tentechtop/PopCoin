package com.pop.popcoinsystem.network.protocol.messageHandler;

import com.pop.popcoinsystem.exception.FullBucketException;
import com.pop.popcoinsystem.exception.HandlerNotFoundException;
import com.pop.popcoinsystem.network.common.NodeInfo;
import com.pop.popcoinsystem.network.protocol.message.*;
import com.pop.popcoinsystem.network.service.KademliaNodeServer;
import com.pop.popcoinsystem.network.common.ExternalNodeInfo;
import com.pop.popcoinsystem.network.common.FindNodeResult;
import com.pop.popcoinsystem.util.BeanCopyUtils;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import java.io.Serializable;
import java.math.BigInteger;
import java.net.ConnectException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;

@Slf4j
public class FindNodeResponseMessageHandler implements MessageHandler{
    private final ExecutorService executorService = Executors.newFixedThreadPool(1);

    @Override
    public KademliaMessage<? extends Serializable> handleMesage(KademliaNodeServer kademliaNodeServer, KademliaMessage<?> message) throws FullBucketException, InterruptedException, ConnectException {
        return doHandle(kademliaNodeServer, (FindNodeResponseMessage) message);
    }

    protected FindNodeResponseMessage doHandle(KademliaNodeServer kademliaNodeServer, @NotNull FindNodeResponseMessage message) throws InterruptedException, ConnectException, FullBucketException {
        NodeInfo nodeInfo = kademliaNodeServer.getNodeInfo();
        BigInteger id = nodeInfo.getId();

        executorService.submit(() -> (message).getData().getNodes().forEach(externalNode -> {
            // ignore self
            if (externalNode.getId().equals(kademliaNodeServer.getId())){
                return;
            }
            try {
                PingKademliaMessage pingKademliaMessage = new PingKademliaMessage();
                pingKademliaMessage.setSender(nodeInfo);//本节点信息
                pingKademliaMessage.setReceiver(BeanCopyUtils.copyObject(externalNode, NodeInfo.class));
                pingKademliaMessage.setReqResId();
                pingKademliaMessage.setResponse(false);
                KademliaMessage kademliaMessage = kademliaNodeServer.getTcpClient().sendMessageWithResponse(pingKademliaMessage);
                boolean update = kademliaNodeServer.getRoutingTable().update(kademliaMessage.getSender());
                log.info("节点是否已经存在 true是不存在 false是存在: " + update);
                if (kademliaMessage!=null && update){
                    // 向这些活跃的节点发起查找
                    FindNodeRequestMessage findNodeRequestMessage = new FindNodeRequestMessage();
                    findNodeRequestMessage.setSender(nodeInfo);
                    findNodeRequestMessage.setReceiver(kademliaMessage.getSender());
                    findNodeRequestMessage.setData(id);//根据本节点查找
                    kademliaNodeServer.getTcpClient().sendMessage(findNodeRequestMessage);
                }
            } catch (HandlerNotFoundException | FullBucketException e) {
                System.out.println(e.getMessage());
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } catch (ConnectException e) {
                throw new RuntimeException(e);
            } catch (TimeoutException e) {
                throw new RuntimeException(e);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }));
        return null;
    }
}
