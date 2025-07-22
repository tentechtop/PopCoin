package com.pop.popcoinsystem.network.common;

import lombok.Data;

import java.math.BigInteger;
import java.util.LinkedList;
import java.util.concurrent.ConcurrentHashMap;

@Data
public class Bucket {
    // 桶ID 也可以是距离
    private  int id;
    //存储节点ID 保证节点顺序 用LinkedList保证插入修改速度
    private LinkedList<BigInteger> nodeIds;
    //ID与节点信息的映射
    private final ConcurrentHashMap<BigInteger, ExternalNodeInfo> nodeMap = new ConcurrentHashMap<>();
    //最后访问时间
    private long lastAccessTime;


    public Bucket(int id) {
        this.nodeIds = new LinkedList<>();
        this.id = id;
    }



    public int size() {
        return nodeIds.size();
    }

    public boolean contains(ExternalNodeInfo node) {
        return nodeIds.contains(node.getId());
    }
    public boolean contains(BigInteger id) {
        return nodeIds.contains(id);
    }

    /**
     * 推送到最前面 将节点添加到路由表或更新已有节点的信息，并将其移至对应 K 桶的头部（表示活跃度最高）。
     * @param node
     */
    public void pushToFront(ExternalNodeInfo node) {
        nodeIds.remove(node.getId());
        nodeIds.add(0, node.getId());
        //更新最后访问时间
        nodeMap.get(node.getId()).setLastSeen(node.getLastSeen());
    }

    public void add(ExternalNodeInfo node) {
        nodeIds.add(0,node.getId());
        nodeMap.put(node.getId(), node);
    }

    public ExternalNodeInfo getNode(BigInteger id) {
        return nodeMap.get(id);
    }

    public void remove(BigInteger nodeId){
        nodeIds.remove(nodeId);
        nodeMap.remove(nodeId);
    }

    public void remove(NodeInfo node){
        this.remove(node.getId());
    }

}
