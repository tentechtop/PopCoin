package com.pop.popcoinsystem.network.protocol.messageHandler;

import com.pop.popcoinsystem.exception.FullBucketException;
import com.pop.popcoinsystem.exception.HandlerNotFoundException;
import com.pop.popcoinsystem.network.common.NodeInfo;
import com.pop.popcoinsystem.network.common.RoutingTable;
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
        log.info("收到查找节点响应");
        NodeInfo nodeInfo = kademliaNodeServer.getNodeInfo();
        BigInteger id = nodeInfo.getId();
        RoutingTable routingTable = kademliaNodeServer.getRoutingTable();
        executorService.submit(() -> (message).getData().getNodes().forEach(externalNode -> {
            // ignore self
            if (externalNode.getId().equals(kademliaNodeServer.getId())){
                return;
            }
            //如果IP相同 端口相同的节点也忽略
            if (externalNode.getIpv4().equals(nodeInfo.getIpv4())
                    && (externalNode.getTcpPort() == nodeInfo.getTcpPort() || externalNode.getUdpPort() == nodeInfo.getUdpPort())){
                //让路由表删除这个节点
                routingTable.delete(externalNode);
                return;
            }
            try {
                PingKademliaMessage pingKademliaMessage = new PingKademliaMessage();
                pingKademliaMessage.setSender(nodeInfo);//本节点信息
                pingKademliaMessage.setReceiver(BeanCopyUtils.copyObject(externalNode, NodeInfo.class));
                pingKademliaMessage.setReqResId();
                pingKademliaMessage.setResponse(false);
                KademliaMessage kademliaMessage = null;
                try {
                    kademliaMessage =  kademliaNodeServer.getTcpClient().sendMessageWithResponse(pingKademliaMessage);
                }catch (TimeoutException e){
                    log.error("未收到节点{}的Pong消息", externalNode);
                    return;
                }
                if (kademliaMessage == null){
                    log.error("未收到节点{}的pong消息", externalNode);
                    return;
                }
                ExternalNodeInfo node = routingTable.findNode(kademliaMessage.getSender().getId());
                boolean update = false;
                if (node == null){
                    update = routingTable.update(kademliaMessage.getSender());
                }else {
                    node.updateAddInfo(kademliaMessage.getSender());
                    update = routingTable.update(node);
                }
                if (update){
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
