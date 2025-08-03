package com.pop.popcoinsystem.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class DiskManagement {

    private String storagePath;

    // 磁盘阈值配置（百分比）
    private static final double DISK_WARN_THRESHOLD = 80.0;   // 预警阈值：80%
    private static final double DISK_PROTECT_THRESHOLD = 90.0; // 保护阈值：90%
    private static final double DISK_READONLY_THRESHOLD = 95.0; // 只读阈值：95%








}
