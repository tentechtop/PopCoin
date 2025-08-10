package com.pop.popcoinsystem.network.common;

import lombok.Data;

import java.math.BigInteger;
import java.util.LinkedList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

@Data
public class Bucket {
    // 桶ID 也可以是距离
    private  int id;
    //存储节点ID 保证节点顺序 用LinkedList保证插入修改速度
    private ConcurrentLinkedDeque<BigInteger> nodeIds = new ConcurrentLinkedDeque<>();
    //ID与节点信息的映射
    private final ConcurrentHashMap<BigInteger, ExternalNodeInfo> nodeMap = new ConcurrentHashMap<>();
    //最后访问时间
    private long lastAccessTime;


    public Bucket(int id) {
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
     * 推送到头部（线程安全版本）
     * 先移除旧节点，再添加到头部，保证顺序正确性
     */
    public void pushToFront(ExternalNodeInfo node) {
        BigInteger nodeId = node.getId();
        // 1. 先移除（若存在）
        nodeIds.remove(nodeId); // ConcurrentLinkedDeque的remove是线程安全的
        // 2. 添加到头部
        nodeIds.addFirst(nodeId); // 线程安全的头部添加
        // 3. 更新映射中的节点信息（如最后活跃时间）
        ExternalNodeInfo existingNode = nodeMap.get(nodeId);
        if (existingNode != null) {
            existingNode.setLastSeen(node.getLastSeen());
        } else {
            // 若映射中没有，补充添加（避免数据不一致）
            nodeMap.put(nodeId, node);
        }
        // 更新最后访问时间
        this.lastAccessTime = System.currentTimeMillis();
    }

    /**
     * 添加节点到头部（线程安全）
     */
    public void add(ExternalNodeInfo node) {
        BigInteger nodeId = node.getId();
        // 避免重复添加
        if (!nodeIds.contains(nodeId)) {
            nodeIds.addFirst(nodeId);
            nodeMap.put(nodeId, node);
            this.lastAccessTime = System.currentTimeMillis();
        }
    }

    public ExternalNodeInfo getNode(BigInteger id) {
        ExternalNodeInfo externalNodeInfo = nodeMap.get(id);
        if (externalNodeInfo != null){
            return externalNodeInfo;
        }
        return null;
    }

    /**
     * 移除节点（线程安全）
     */
    public void remove(BigInteger nodeId) {
        nodeIds.remove(nodeId);
        nodeMap.remove(nodeId);
        this.lastAccessTime = System.currentTimeMillis();
    }

    public void remove(ExternalNodeInfo node){
        this.remove(node.getId());
    }


    /**
     * 将节点添加到链表末尾（表示活跃度较低），若节点已存在则先移除再添加到末尾
     * @param node 要操作的节点
     */
    /**
     * 推送到末尾（线程安全）
     */
    public void pushToAfter(ExternalNodeInfo node) {
        BigInteger nodeId = node.getId();
        nodeIds.remove(nodeId);
        nodeIds.addLast(nodeId); // 线程安全的尾部添加
        ExternalNodeInfo existingNode = nodeMap.get(nodeId);
        if (existingNode != null) {
            existingNode.setLastSeen(node.getLastSeen());
        } else {
            nodeMap.put(nodeId, node);
        }
        this.lastAccessTime = System.currentTimeMillis();
    }

    /**
     * 获取所有节点ID（用于遍历，返回不可修改的视图避免并发问题）
     */
    public Iterable<BigInteger> getNodeIds() {
        // 返回迭代器的快照，避免遍历中被修改导致异常
        return () -> nodeIds.iterator();
    }
}
