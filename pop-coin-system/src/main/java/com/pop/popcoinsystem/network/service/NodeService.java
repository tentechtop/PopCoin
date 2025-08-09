package com.pop.popcoinsystem.network.service;

import com.pop.popcoinsystem.data.vo.result.Result;
import com.pop.popcoinsystem.network.common.ExternalNodeInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class NodeService {

    @Lazy
    @Autowired
    private KademliaNodeServer kademliaNodeServer;

    /**
     * 获取节点列表
     * @return
     */
    public Result list() {
        List<ExternalNodeInfo> allNodes = kademliaNodeServer.getRoutingTable().getAllNodes();
        return Result.ok(allNodes);
    }
}
