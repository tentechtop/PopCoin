package com.pop.popcoinsystem.service.blockChain.asyn;

import java.math.BigInteger;
import java.util.List;

public interface AsyncBlockSynchronizer {
    /**
     * 获取所有同步任务进度
     */
    List<SyncProgress> getAllSyncProgress();

    /**
     * 获取指定任务ID的同步进度
     */
    SyncProgress getSyncProgressByTaskId(String taskId);

    /**
     * 获取指定节点的同步进度
     */
    SyncProgress getSyncProgressByNodeId(BigInteger nodeId);

    /**
     * 取消同步任务
     */
    boolean cancelSyncTask(String taskId);
}
