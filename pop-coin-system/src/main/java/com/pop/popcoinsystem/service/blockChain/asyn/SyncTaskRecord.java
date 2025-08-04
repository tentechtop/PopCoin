package com.pop.popcoinsystem.service.blockChain.asyn;

import com.pop.popcoinsystem.network.common.NodeInfo;
import lombok.Data;

import java.io.Serializable;
import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 同步任务
 */
@Data
public class SyncTaskRecord implements Serializable {
    private static final long serialVersionUID = 1L;
    // 任务ID 用 Task[1000-2000]做为ID  在添加新的任务时检查是否已经被覆盖
    private String taskId;
    // 同步状态：INIT(初始化), RUNNING(运行中), PAUSED(暂停), COMPLETED(完成), FAILED(失败),CANCELLED(取消)
    private SyncStatus status;
    // 开始高度
    private long startHeight;
    // 目标高度
    private long targetHeight;
    // 已同步区块数
    private long syncedBlocks;
    // 创建时间
    private LocalDateTime createTime;
    // 更新时间
    private LocalDateTime updateTime;
    // 错误信息（失败时）
    private String errorMsg;

    //默认是500个区块一个分片 所以知道自己的 开始和目标就知道自己有多少分片
    private List<SyncProgress> syncProgressList;


}
