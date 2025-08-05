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
        NodeInfo nodeInfo = kademliaNodeServer.getNodeInfo();
        BigInteger id = nodeInfo.getId();
        RoutingTable routingTable = kademliaNodeServer.getRoutingTable();

        //总结：循环的 “天然终止条件”
        //这段代码的逻辑虽然会触发 “处理响应→发送新请求→再处理新响应” 的链条，但由于以下约束，链条会自然终止：
        //路由表update方法的返回值限制（重复节点不触发新请求）；
        //有限的 ID 空间和节点数量（候选节点不会无限产生）；
        //固定的请求目标（避免无限制扩散）；
        //网络超时和异常处理（中断无效流程）。
        //因此，代码不会产生无限循环，而是会在 “没有新节点可加入路由表” 时自然终止。
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
                KademliaMessage kademliaMessage = kademliaNodeServer.getTcpClient().sendMessageWithResponse(pingKademliaMessage);
                if (kademliaMessage == null){
                    log.error("未收到节点{}的Ping消息", externalNode);
                    return;
                }
                boolean update = kademliaNodeServer.getRoutingTable().update(kademliaMessage.getSender());
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
