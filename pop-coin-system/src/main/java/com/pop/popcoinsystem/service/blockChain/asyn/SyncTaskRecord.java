package com.pop.popcoinsystem.service.blockChain.asyn;

import lombok.Data;

import java.math.BigInteger;
import java.time.LocalDateTime;

@Data
public class SyncTaskRecord {
    private String taskId; // 任务ID
    private BigInteger nodeId; // 关联节点ID
    private long startHeight; // 开始高度
    private long targetHeight; // 目标高度
    private long currentHeight; // 当前进度高度
    private double progressPercent; // 进度百分比
    private SyncStatus status; // 任务状态（枚举：RUNNING/FAILED/CANCELLED）
    private String errorMsg; // 错误信息（失败时）
    private LocalDateTime createTime; // 创建时间
    private LocalDateTime updateTime; // 更新时间
    // 已同步区块数
    private long syncedBlocks;
}
