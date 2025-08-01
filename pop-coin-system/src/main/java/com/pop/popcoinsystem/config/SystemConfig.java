package com.pop.popcoinsystem.config;


import com.pop.popcoinsystem.constant.BlockChainConstants;
import com.pop.popcoinsystem.util.CryptoUtil;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "system")
public class SystemConfig {
    // 引导节点列表（从配置文件读取）
    private List<String> publicKey;

    private int netVersion;

    private String storagePath;

    @PostConstruct
    public void init(){
        log.info("网络版本:{}", netVersion);
        log.info("公钥{}", publicKey);
        log.info("存储路径:{}", storagePath);
        // 关键：初始化时将网络版本传递给CryptoUtil
        BlockChainConstants.setNetVersion(netVersion);
        BlockChainConstants.setStoragePath(storagePath);
    }
}
