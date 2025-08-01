package com.pop.popcoinsystem.service;

import com.pop.popcoinsystem.storage.POPStorage;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StorageConfig {
    @Bean
    public POPStorage popStorage() {
        return POPStorage.getInstance(); // 保持单例，同时交给 Spring 管理
    }
}

