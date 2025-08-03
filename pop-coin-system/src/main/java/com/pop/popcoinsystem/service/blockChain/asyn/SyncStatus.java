package com.pop.popcoinsystem.service.blockChain.asyn;

/**
 * 同步任务状态枚举
 * 描述同步任务在生命周期中的各种状态
 */
public enum SyncStatus {

    /**
     * 初始化状态：任务已创建创建但尚未开始执行
     */
    INIT,

    /**
     * 运行中：任务正在执行同步操作
     */
    RUNNING,

    /**
     * 暂停：任务被手动暂停，可通过恢复操作继续执行
     */
    PAUSED,

    /**
     * 完成：任务成功执行完毕（所有区块同步完成）
     */
    COMPLETED,

    /**
     * 失败：任务因错误（如网络异常、验证失败）终止
     */
    FAILED,

    /**
     * 取消：任务被主动终止（如用户触发取消操作）
     */
    CANCELLED



}