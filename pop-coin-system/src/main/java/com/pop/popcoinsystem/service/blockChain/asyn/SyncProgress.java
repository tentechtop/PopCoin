package com.pop.popcoinsystem.service.blockChain.asyn;


import com.pop.popcoinsystem.network.common.NodeInfo;
import lombok.Data;

import java.io.Serializable;
import java.math.BigInteger;
import java.time.LocalDateTime;

/**
 * 任务下的分片
 */
@Data
public class SyncProgress implements Serializable {
    private static final long serialVersionUID = 1L;
    // 用Task[1000-2000]_PROGRESS_[0-100] 做ID
    private String progressId;
    // 同步任务ID（唯一标识）
    private String taskId;
    // 远程节点ID
    private BigInteger nodeId;
    // 同步状态：INIT(初始化), RUNNING(运行中), PAUSED(暂停), COMPLETED(完成), FAILED(失败),CANCELLED(取消)
    private SyncStatus status;
    // 起始高度
    private long startHeight;
    // 目标高度
    private long targetHeight;
    // 当前已同步高度
    private long currentHeight;
    // 已同步区块数
    private long syncedBlocks;
    // 进度百分比（保留2位小数）最大100
    private double progressPercent;
    // 连续错误区块头计数
    private int continuousInvalidHeaderCount;
    // 有效区块头总数
    private int totalValidHeaderCount;
    // 无效区块头总数
    private int totalInvalidHeaderCount;
    // 最后同步时间
    private LocalDateTime lastSyncTime;
    // 错误信息（失败时）
    private String errorMsg;

}