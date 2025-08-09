package com.pop.popcoinsystem.network.common;

import com.pop.popcoinsystem.exception.FullBucketException;
import com.pop.popcoinsystem.network.enums.NodeStatus;
import com.pop.popcoinsystem.network.enums.NodeType;
import com.pop.popcoinsystem.storage.NodeInfoStorageService;
import com.pop.popcoinsystem.storage.StorageService;
import com.pop.popcoinsystem.util.BeanCopyUtils;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Slf4j
@NoArgsConstructor
public class RoutingTable {
    /* 路由表所有者的ID（节点ID） */
    protected BigInteger localNodeId;
    /* 存储桶列表 */
    protected ArrayList<Bucket> buckets;
    //路由表参数
    protected transient NodeSettings nodeSettings;
    // 读写锁，保护路由表
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    // 最后访问时间，用于刷新桶
    private final Map<Integer, Long> lastBucketAccessTime = new ConcurrentHashMap<>();

    /**
     * 初始化路由表
     */
    public RoutingTable(BigInteger localNodeId, NodeSettings nodeSettings) {
        log.debug("初始化路由表");
        this.localNodeId = localNodeId;
        this.nodeSettings = nodeSettings;
        buckets = new ArrayList<>();
        for (int i = 0; i < nodeSettings.getIdentifierSize() + 1; i++) {
            buckets.add(createBucketOfId(i));
        }
    }
    private Bucket createBucketOfId(int id) {
        return new Bucket(id);
    }


    /**
     * 更新路由表 添加或移动节点到适当的K桶
     */
    public boolean update(ExternalNodeInfo node) throws FullBucketException {
        node.setNodeStatus(NodeStatus.ACTIVE);//在线
        node.onSuccessfulResponse(false);
        node.setLastSeen(new Date());
        Bucket bucket = this.findBucket(node.getId());
        // 更新桶的访问时间
        lastBucketAccessTime.put(bucket.getId(), System.currentTimeMillis());
        node.setDistance(node.getId().xor(this.localNodeId));
        if (bucket.contains(node)) {
            bucket.pushToFront(node);
            return false;
        }else if (bucket.size() < this.nodeSettings.getBucketSize()) {
            bucket.add(node);
            return true;
        }
        throw new FullBucketException();
    }

    public boolean update(NodeInfo updateNode) throws FullBucketException {
        ExternalNodeInfo node = updateNode.extractExternalNodeInfo();
        node.setNodeStatus(NodeStatus.ACTIVE);//在线
        node.onSuccessfulResponse(false);
        node.setLastSeen(new Date());
        Bucket bucket = this.findBucket(node.getId());
        // 更新桶的访问时间
        lastBucketAccessTime.put(bucket.getId(), System.currentTimeMillis());
        node.setDistance(node.getId().xor(this.localNodeId));
        if (bucket.contains(node)) {
            bucket.pushToFront(node);
            return false;
        }else if (bucket.size() < this.nodeSettings.getBucketSize()) {
            bucket.add(node);
            return true;
        }
        throw new FullBucketException();
    }



    /**
     * 强制将节点添加到路由表中。若对应K桶已满，会移除最老的节点以腾出空间。
     */
    public synchronized void forceUpdate(ExternalNodeInfo node) {
        try {
            this.update(node);
        } catch (FullBucketException e) {
            Bucket bucket = this.findBucket(node.getId());
            Date date = null;
            BigInteger oldestNode = null;
            //遍历桶内所有节点（除自身外），找到lastSeen时间最早的节点（即最久未活跃的节点）。
            for (BigInteger nodeId : bucket.getNodeIds()) {
                if (nodeId.equals(this.localNodeId)){
                    continue;
                }
                if (date == null || bucket.getNode(nodeId).getLastSeen().before(date)){
                    date = bucket.getNode(nodeId).getLastSeen();
                    oldestNode = nodeId;
                }
            }
            bucket.remove(oldestNode);
            this.forceUpdate(node);
        }
    }

    public FindNodeResult findClosestResult(BigInteger destinationId) {
        FindNodeResult findNodeResult = new FindNodeResult(destinationId);
        Bucket bucket = this.findBucket(destinationId);

        for (int i = 1; findNodeResult.size() < this.nodeSettings.getBucketSize() && ((bucket.getId() - i) >= 0 ||
                (bucket.getId() + i) <= this.nodeSettings.getIdentifierSize()); i++) {
            if (bucket.getId() - i >= 0) {
                Bucket bucketP = this.buckets.get(bucket.getId() - i);
                addToAnswer(bucketP, findNodeResult, destinationId);
            }
            if (bucket.getId() + i <= this.nodeSettings.getIdentifierSize()) {
                Bucket bucketN = this.buckets.get(bucket.getId() + i);
                addToAnswer(bucketN, findNodeResult, destinationId);
            }
        }
        Collections.sort(findNodeResult.getNodes());
        new FindNodeResultReducer(this.localNodeId, findNodeResult, this.nodeSettings.getFindNodeSize(), this.nodeSettings.getIdentifierSize()).reduce();
        while (findNodeResult.size() > this.nodeSettings.getFindNodeSize()) {
            findNodeResult.remove(findNodeResult.size() - 1); //TODO: Not the best thing.
        }
        return findNodeResult;
    }

    public void addToAnswer (Bucket bucket, FindNodeResult answer, BigInteger destination){
        for (BigInteger id : bucket.getNodeIds()) {
            ExternalNodeInfo node = bucket.getNode(id);
            answer.add(new ExternalNodeInfo(node, destination.xor(id)));
        }
    }

    /**
     * 功能：从路由表中查找与destinationId最接近的 K 个节点（K 通常为 20）。
     * @param destinationId 目标节点ID
     * @return 最接近的K个节点的列表
     */
    public List<ExternalNodeInfo> findClosest(BigInteger destinationId) {
        // 用于存储结果的列表
        ArrayList<ExternalNodeInfo> closestNodeList = new ArrayList<>(nodeSettings.getBucketSize());
        // 获取目标ID所在的桶
        Bucket targetBucket = this.findBucket(destinationId);

        // 使用优先队列（最大堆）来维护当前最近的K个节点
        PriorityQueue<ExternalNodeInfo> closestNodes = new PriorityQueue<>(
                nodeSettings.getBucketSize(),
                (a, b) -> getDistance(b.getId()).compareTo(getDistance(a.getId()))
        );
        // 计算目标ID的前缀
        int targetPrefix = findBucket(destinationId).getId();
        // 首先检查目标桶
        lock.readLock().lock();
        try {
            // 处理目标桶中的所有节点
            for (BigInteger nodeId : targetBucket.getNodeIds()) {
                ExternalNodeInfo node = targetBucket.getNode(nodeId);
                // 只处理活跃节点
                if (node == null || node.getNodeStatus() != NodeStatus.ACTIVE) {
                    continue;
                }
                addIfCloser(node, destinationId, closestNodes);
            }
            // 然后扩展搜索到相邻的桶
            int left = targetPrefix - 1;
            int right = targetPrefix + 1;
            // 交替向两边扩展搜索
            while ((left >= 0 || right < buckets.size()) && closestNodes.size() < nodeSettings.getBucketSize()) {
                if (left >= 0) {
                    Bucket bucket = buckets.get(left);
                    for (BigInteger nodeId : bucket.getNodeIds()) {
                        ExternalNodeInfo node = bucket.getNode(nodeId);
                        addIfCloser(node, destinationId, closestNodes);
                    }
                    left--;
                }
                if (right < buckets.size()) {
                    Bucket bucket = buckets.get(right);
                    for (BigInteger nodeId : bucket.getNodeIds()) {
                        ExternalNodeInfo node = bucket.getNode(nodeId);
                        addIfCloser(node, destinationId, closestNodes);
                    }
                    right++;
                }
            }
            // 将优先队列中的节点转移到结果列表中
            while (!closestNodes.isEmpty()) {
                closestNodeList.add(closestNodes.poll());
            }
            // 结果需要按距离从小到大排序
            closestNodeList.sort(Comparator.comparing(node -> getDistance(node.getId())));
            return closestNodeList;
        } finally {
            lock.readLock().unlock();
        }
    }


    public List<ExternalNodeInfo> findClosest() {
        BigInteger destinationId = localNodeId;
        // 用于存储结果的列表
        ArrayList<ExternalNodeInfo> closestNodeList = new ArrayList<>(nodeSettings.getBucketSize());
        // 获取目标ID所在的桶
        Bucket targetBucket = this.findBucket(destinationId);
        // 使用优先队列（最大堆）来维护当前最近的K个节点
        PriorityQueue<ExternalNodeInfo> closestNodes = new PriorityQueue<>(
                nodeSettings.getBucketSize(),
                (a, b) -> getDistance(b.getId()).compareTo(getDistance(a.getId()))
        );
        // 计算目标ID的前缀
        int targetPrefix = findBucket(destinationId).getId();
        // 首先检查目标桶
        lock.readLock().lock();
        try {
            // 处理目标桶中的所有节点
            for (BigInteger nodeId : targetBucket.getNodeIds()) {
                ExternalNodeInfo node = targetBucket.getNode(nodeId);
                if (node == null || node.getNodeStatus() != NodeStatus.ACTIVE) {
                    continue;
                }
                addIfCloser(node, destinationId, closestNodes);
            }
            // 然后扩展搜索到相邻的桶
            int left = targetPrefix - 1;
            int right = targetPrefix + 1;
            // 交替向两边扩展搜索
            while ((left >= 0 || right < buckets.size()) && closestNodes.size() < nodeSettings.getBucketSize()) {
                if (left >= 0) {
                    Bucket bucket = buckets.get(left);
                    for (BigInteger nodeId : bucket.getNodeIds()) {
                        ExternalNodeInfo node = bucket.getNode(nodeId);
                        addIfCloser(node, destinationId, closestNodes);
                    }
                    left--;
                }

                if (right < buckets.size()) {
                    Bucket bucket = buckets.get(right);
                    for (BigInteger nodeId : bucket.getNodeIds()) {
                        ExternalNodeInfo node = bucket.getNode(nodeId);
                        addIfCloser(node, destinationId, closestNodes);
                    }
                    right++;
                }
            }
            // 将优先队列中的节点转移到结果列表中
            while (!closestNodes.isEmpty()) {
                closestNodeList.add(closestNodes.poll());
            }
            // 结果需要按距离从小到大排序
            closestNodeList.sort(Comparator.comparing(node -> getDistance(node.getId())));
            //去除自己 一般不存在
            //closestNodeList.removeIf(node -> node.getId().equals(localNodeId));
            return closestNodeList;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 根据节点类型查询活跃节点，并按分数降序排序
     * @param nodeType 目标节点类型
     * @param limit 返回节点的最大数量（null 表示返回所有符合条件的节点）
     * @return 符合类型的活跃节点列表（按分数降序）
     */
    public List<ExternalNodeInfo> findNodesByType(NodeType nodeType, Integer limit) {
        if (nodeType == null) {
            log.warn("查询节点类型为空，返回空列表");
            return Collections.emptyList();
        }
        // 存储符合条件的节点
        List<ExternalNodeInfo> result = new ArrayList<>();
        // 遍历所有桶
        for (Bucket bucket : buckets) {
            // 遍历桶中所有节点
            for (BigInteger nodeId : bucket.getNodeIds()) {
                ExternalNodeInfo node = bucket.getNode(nodeId);
                if (node != null
                        && node.getNodeStatus() == NodeStatus.ACTIVE
                        && NodeType.valueOf(node.getNodeType()).equals(nodeType)
                        && !Objects.equals(node.getId(), localNodeId)
                ) {
                    result.add(node);
                }
            }
        }
        // 按分数降序排序（分数高的节点优先）
        result.sort((a, b) -> Integer.compare(b.getScore(), a.getScore()));
        // 限制返回数量（如果指定了limit）
        if (limit != null && limit > 0 && result.size() > limit) {
            return result.subList(0, limit);
        }
        return result;
    }


    /**
     * 重载方法：查询指定类型的所有活跃节点（不限制数量）
     */
    public List<ExternalNodeInfo> findNodesByType(NodeType nodeType) {
        return findNodesByType(nodeType, null);
    }

    /**
     * 辅助方法：如果节点比当前队列中的最远节点更近，则添加到队列中
     */
    private void addIfCloser(ExternalNodeInfo node, BigInteger destinationId, PriorityQueue<ExternalNodeInfo> closestNodes) {
        // 双重校验：确保只处理活跃节点
        if (node.getNodeStatus() != NodeStatus.ACTIVE) {
            return;
        }
        // 计算当前节点与目标ID的距离
        BigInteger distance = getDistance(node.getId());
        // 如果队列未满，直接添加
        if (closestNodes.size() < nodeSettings.getBucketSize()) {
            closestNodes.offer(node);
        } else {
            // 否则，检查是否比最远的节点更近
            ExternalNodeInfo farthest = closestNodes.peek();
            if (farthest != null && getDistance(farthest.getId()).compareTo(distance) > 0) {
                closestNodes.poll();
                closestNodes.offer(node);
            }
        }
    }

    /**
     * 获取 K桶
     */
    public List<Bucket> getBuckets() {
        return buckets;
    }


    /**
     * 删除节点
     */
    public void delete(ExternalNodeInfo node) {
        Bucket bucket = this.findBucket(node.getId());
        bucket.remove(node);
    }


    public Bucket findBucket(BigInteger id) {
        BigInteger xorNumber = this.getDistance(id);
        int prefix = this.getNodePrefix(xorNumber);
        return buckets.get(prefix);
    }


    public int getNodePrefix(BigInteger id) {
        for (int j = 0; j < this.nodeSettings.getIdentifierSize(); j++) {
            BigInteger xor = id.xor(BigInteger.valueOf(j));
            if (!xor.shiftRight(this.nodeSettings.getIdentifierSize() - 1 - j).and(BigInteger.valueOf(0x1L)).equals(BigInteger.valueOf(0))) {
                return this.nodeSettings.getIdentifierSize() - j;
            }
        }
        return 0;
    }

    /**
     * 计算两个节点之间的距离
     * @param id
     * @return
     */
    public BigInteger getDistance(BigInteger id) {
        return id.xor(this.localNodeId);
    }

    public boolean contains(BigInteger nodeId) {
        Bucket bucket = this.findBucket(nodeId);
        return bucket.contains(nodeId);
    }

    /**
     * 从节点列表恢复路由表
     */
    public void recoverFromNodeList() {
        log.info("开始从节点列表恢复路由表");
        // 从存储获取所有路由表节点
        List<ExternalNodeInfo> nodeList = NodeInfoStorageService.getInstance().iterateAllRouteTableNodes();
        if (nodeList == null || nodeList.isEmpty()) {
            log.info("恢复路由表：节点列表为空，无需处理");
            return;
        }
        log.info("开始从节点列表恢复路由表，共 {} 个节点", nodeList.size());
        int successCount = 0;
        for (ExternalNodeInfo node : nodeList) {
            try {
                // 跳过本地节点（避免添加自身到路由表）
                if (node.getId().equals(localNodeId)) {
                    continue;
                }
                // 强制更新路由表（桶满时会替换最老节点）
                forceUpdate(node);
                successCount++;
            } catch (Exception e) {
                log.error("恢复节点失败：nodeId={}", node.getId(), e);
            }
        }
        log.info("路由表恢复完成，成功添加 {} 个节点，失败 {} 个节点",
                successCount, nodeList.size() - successCount);
    }

    // 在RoutingTable类中添加以下方法
    /**
     * 清理所有过期节点（lastSeen超过expirationTime毫秒的节点）
     * @param expirationTime 过期阈值（毫秒），即NODE_EXPIRATION_TIME
     */
    public void cleanExpiredNodes(long expirationTime) {
        if (expirationTime <= 0) {
            log.warn("过期时间阈值无效，跳过清理");
            return;
        }
        NodeInfoStorageService storage = NodeInfoStorageService.getInstance();
        long now = System.currentTimeMillis();
        int deletedCount = 0;

        // 获取写锁，确保清理过程线程安全
        lock.writeLock().lock();
        try {
            // 遍历所有桶
            for (Bucket bucket : buckets) {
                // 遍历桶中所有节点ID（需Bucket提供获取所有节点的方法）
                List<BigInteger> nodeIds = new ArrayList<>(bucket.getNodeIds()); // 避免遍历中修改引发并发异常
                for (BigInteger nodeId : nodeIds) {
                    // 跳过本地节点
                    if (nodeId.equals(localNodeId)) {
                        continue;
                    }
                    ExternalNodeInfo node = bucket.getNode(nodeId);
                    if (node == null) {
                        continue; // 节点已被移除，跳过
                    }
                    // 计算节点不活跃时间（当前时间 - 最后活跃时间）
                    long inactiveTime = now - node.getLastSeen().getTime();
                    if (inactiveTime > expirationTime) {
                        // 节点已过期，从桶中删除
                        bucket.remove(nodeId);
                        // 从存储中删除
                        storage.deleteRouteTableNode(nodeId);
                        deletedCount++;
                        log.debug("删除过期节点：{}（不活跃时间：{}ms）", nodeId, inactiveTime);
                    }
                }
            }
            log.info("过期节点清理完成，共删除 {} 个节点", deletedCount);
        } finally {
            lock.writeLock().unlock();
        }
    }


    /**
     * 将当前路由表中的所有节点信息持久化到存储系统
     */
    public void persistToStorage() {
        log.info("开始将路由表节点持久化到存储系统");
        // 获取存储实例
        NodeInfoStorageService storage = NodeInfoStorageService.getInstance();
        // 收集所有需要持久化的节点
        List<ExternalNodeInfo> nodesToPersist = new ArrayList<>();
        lock.readLock().lock();
        try {
            // 遍历所有桶
            for (Bucket bucket : buckets) {
                // 遍历桶中的所有节点ID
                for (BigInteger nodeId : bucket.getNodeIds()) {
                    // 跳过本地节点（不需要存储自身信息）
                    if (nodeId.equals(localNodeId)) {
                        continue;
                    }
                    // 获取节点信息并添加到待持久化列表
                    ExternalNodeInfo node = bucket.getNode(nodeId);
                    if (node != null) {
                        // 更新最后保存时间
                        node.setLastSeen(new Date());
                        nodesToPersist.add(node);
                    }
                }
            }

            log.info("准备持久化 {} 个路由表节点", nodesToPersist.size());
            log.info("待持久化的节点信息：{}", nodesToPersist);

            if (!nodesToPersist.isEmpty()) {
                // 批量保存节点信息，提高效率
                storage.addOrUpdateRouteTableNodeBatch(nodesToPersist);
                log.info("路由表节点持久化完成，成功保存 {} 个节点", nodesToPersist.size());
            } else {
                log.info("路由表为空，无需持久化");
            }

        } catch (Exception e) {
            log.error("路由表节点持久化失败", e);
            throw new RuntimeException("路由表节点持久化失败", e);
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<ExternalNodeInfo> getAllNodes() {
        // 初始化存储所有节点的列表
        List<ExternalNodeInfo> allNodes = new ArrayList<>();
        // 获取读锁，确保线程安全
        lock.readLock().lock();
        try {
            // 遍历所有桶
            for (Bucket bucket : buckets) {
                // 遍历当前桶中的所有节点ID
                for (BigInteger nodeId : bucket.getNodeIds()) {
                    // 跳过本地节点（不返回自身节点信息）
                    if (nodeId.equals(localNodeId)) {
                        continue;
                    }
                    // 获取节点信息并添加到列表
                    ExternalNodeInfo node = bucket.getNode(nodeId);
                    if (node != null) {
                        allNodes.add(node);
                    }
                }
            }
        } finally {
            // 确保锁被释放
            lock.readLock().unlock();
        }
        return allNodes;
    }


    public List<ExternalNodeInfo> getAllActiveNodes() {
        // 初始化存储所有节点的列表
        List<ExternalNodeInfo> allNodes = new ArrayList<>();
        // 获取读锁，确保线程安全
        lock.readLock().lock();
        try {
            // 遍历所有桶
            for (Bucket bucket : buckets) {
                // 遍历当前桶中的所有节点ID
                for (BigInteger nodeId : bucket.getNodeIds()) {
                    // 跳过本地节点（不返回自身节点信息）
                    if (nodeId.equals(localNodeId)) {
                        continue;
                    }
                    // 获取节点信息并添加到列表
                    ExternalNodeInfo node = bucket.getNode(nodeId);
                    if (node != null && node.getNodeStatus() == NodeStatus.ACTIVE) {
                        allNodes.add(node);
                    }
                }
            }
        } finally {
            // 确保锁被释放
            lock.readLock().unlock();
        }
        return allNodes;
    }



    public ExternalNodeInfo findNode (BigInteger id) {
        Bucket bucket = findBucket(id);
        return bucket.getNode(id);
    }

    public NodeInfo getNodeInfo(BigInteger nodeId) {
        ExternalNodeInfo node = findNode(nodeId);
        if (node == null){
            return null;
        }
        return node.extractNodeInfo();
    }

    public void removeNode(BigInteger id) {
        lock.writeLock().lock();
        try {
            Bucket bucket = findBucket(id);
            bucket.remove(id);
        }
        finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 处理节点下线：标记为离线状态、扣减分数、更新最后活跃时间并持久化
     * @param id 下线节点的ID
     */
    public void offlineNode(BigInteger id) {
        // 1. 校验节点ID合法性
        if (id == null) {
            log.warn("下线节点失败：节点ID为null");
            return;
        }
        // 2. 跳过本地节点（自身不会下线）
        if (id.equals(localNodeId)) {
            log.debug("忽略本地节点下线操作：{}", id);
            return;
        }
        // 4. 查找节点所在的桶并获取节点
        Bucket bucket = findBucket(id);
        ExternalNodeInfo node = bucket.getNode(id);
        if (node == null) {
            log.debug("节点不存在于路由表中，无需处理下线：{}", id);
            return;
        }
        //标记节点为离线状态
        node.setNodeStatus(NodeStatus.INACTIVE);
        //扣减分数
        node.onFailureResponse(false);
        //放在桶子的最后面
        bucket.pushToAfter(node);
        log.info("节点下线扣分：{}，当前分数：{}", id, node.getScore());
    }

    /**
     * 节点是否在线
     * @param targetNode
     * @return
     */
    public boolean isNodeAvailable(NodeInfo targetNode) {
        ExternalNodeInfo node = findNode(targetNode.getId());
        return node != null && node.getNodeStatus() == NodeStatus.ACTIVE;
    }

    /**
     * 从全节点中查询
     * @return
     */
    public NodeInfo findAvailableNode() {
        List<ExternalNodeInfo> nodesByType = findNodesByType(NodeType.FULL, 1);
        return nodesByType.isEmpty() ? null : nodesByType.getFirst().extractNodeInfo();
    }
}
