package com.pop.popcoinsystem.service;

import com.pop.popcoinsystem.network.enums.NETVersion;
import com.pop.popcoinsystem.util.YamlReaderUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

@Slf4j
@Service
public class NodeService {
    public static int NODE_TYPE = 1;
    static {
        // 从类路径根目录加载application.yml
        Map<String, Object> config = YamlReaderUtils.loadYaml("application.yml");
        if (config != null) {
            NODE_TYPE = (int)YamlReaderUtils.getNestedValue(config, "popcoin.nodetype");
        }
    }











}
