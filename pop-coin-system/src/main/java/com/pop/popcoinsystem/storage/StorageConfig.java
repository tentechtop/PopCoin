package com.pop.popcoinsystem.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class StorageConfig {
    @Bean
    public StorageService popStorage() {
        return StorageService.getInstance(); // 保持单例，同时交给 Spring 管理
    }


    @Bean
    public MiningStorageService miningStorageService() {
        return MiningStorageService.getInstance(); // 保持单例，同时交给 Spring 管理
    }

    @Bean
    public NodeInfoStorageService nodoInfoStorageService() {
        return NodeInfoStorageService.getInstance(); // 保持单例，同时交给 Spring 管理
    }

}

