package com.pop.popcoinsystem.service;

import com.pop.popcoinsystem.storage.StorageService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StorageConfig {
    @Bean
    public StorageService popStorage() {
        return StorageService.getInstance(); // 保持单例，同时交给 Spring 管理
    }
}

