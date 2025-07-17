package com.pop.popcoinsystem.util;
import lombok.extern.slf4j.Slf4j;
import org.yaml.snakeyaml.Yaml;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

@Slf4j
public class YamlReaderUtils {
    public static Map<String, Object> loadYaml(String filePath) {
        try (InputStream inputStream = YamlReaderUtils.class.getClassLoader().getResourceAsStream(filePath)) {
            if (inputStream == null) {
                throw new IOException("文件未找到: " + filePath);
            }
            Yaml yaml = new Yaml();
            return yaml.load(inputStream);
        } catch (IOException e) {
            System.err.println("读取YAML文件时出错: " + e.getMessage());
            return null;
        }
    }

    public static void main(String[] args) {
        // 从类路径根目录加载application.yml
        Map<String, Object> config = loadYaml("application.yml");
        if (config != null) {
            System.out.println("数据库URL: " + getNestedValue(config, "spring.datasource.url"));
            System.out.println("服务器端口: " + getNestedValue(config, "server.port"));
        }
    }

    // 辅助方法：获取嵌套值
    public static Object getNestedValue(Map<String, Object> map, String path) {
        String[] keys = path.split("\\.");
        Object current = map;
        for (String key : keys) {
            if (!(current instanceof Map)) {
                return null;
            }
            current = ((Map<String, Object>) current).get(key);
            if (current == null) {
                return null;
            }
        }
        return current;
    }
}
