package com.pop.popcoinsystem.service.blockChain.asyn;


import com.pop.popcoinsystem.network.common.NodeInfo;
import lombok.Data;
import java.math.BigInteger;
import java.time.LocalDateTime;

/**
 * 同步进度跟踪实体
 */
@Data
public class SyncProgress {
    // 远程节点ID
    private BigInteger nodeId;
    // 远程节点信息
    private NodeInfo nodeInfo;
    // 同步任务ID（唯一标识）
    private String taskId;
    // 同步状态：INIT(初始化), RUNNING(运行中), PAUSED(暂停), COMPLETED(完成), FAILED(失败)
    private SyncStatus status;
    // 起始区块哈希
    private String startHash;
    // 目标区块哈希
    private String endHash;
    // 起始高度
    private long startHeight;
    // 目标高度
    private long targetHeight;
    // 当前已同步高度
    private long currentHeight;
    // 总区块数（预估）
    private long totalBlocks;
    // 已同步区块数
    private long syncedBlocks;
    // 进度百分比（保留2位小数）
    private double progressPercent;
    // 最后同步时间
    private LocalDateTime lastSyncTime;
    // 错误信息（失败时）
    private String errorMsg;

    public enum SyncStatus {
        INIT, RUNNING, PAUSED, COMPLETED, FAILED
    }
}