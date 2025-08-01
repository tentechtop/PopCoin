package com.pop.popcoinsystem;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

import java.net.InetAddress;
import java.net.UnknownHostException;

@Slf4j
@SpringBootApplication
public class PopCoinSystemApplication {



    public static void main(String[] args) throws UnknownHostException {
        ConfigurableApplicationContext application = SpringApplication.run(PopCoinSystemApplication.class, args);
        Environment env = application.getEnvironment();
        String ip = InetAddress.getLocalHost().getHostAddress();
        String port = env.getProperty("server.port");

        log.info("\n----------------------------------------------------------\n\t" +
                "Application POPCoinSystem is running! Access URLs:\n\t" +
                "Local: \t\thttp://localhost:" + port + "/\n\t" +
                "Net: "  + "\t\thttp://" + ip + ":" + port + "/\n\t" +
                "----------------------------------------------------------");
    }



    //           pipeline.addLast(new LengthFieldBasedFrameDecoder(
    //                                    10 * 1024 * 1024,  // 最大帧长度
    //                                    4,                 // 长度字段偏移量（跳过类型字段）
    //                                    4,                 // 长度字段长度（总长度字段）
    //                                    -8,                // 长度调整值 = 内容长度 - 总长度 = -8
    //                                    0                 // 跳过前12字节（类型+总长度+内容长度）  目前不跳过
    //                            ));





    //logging:
    //  level:
    //    root: ERROR  # 全局日志级别设为ERROR
    //  file:
    //    name: ../logs/popcoin.log  # 主日志文件路径
    //  logback:
    //    rollingpolicy:
    //      max-file-size: 10MB  # 单个日志文件的最大容量
    //      max-history: 30  # 保留历史日志的天数
    //  pattern:
    //    console: "%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %highlight(%-5level) %cyan(%logger{50}:%L) - %msg%n"  # 控制台输出格式
    //    file: "%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{50}:%L - %msg%n"  # 文件输出格式
    //  # 自定义appender配置
    //  config: classpath:logback-spring.xml  # 引用自定义的logback配置文件


    //          Bucket bucket = routingTable.findBucket(id);
    //            // 为每个节点创建超时任务：若超时未收到Pong，则标记为不活跃 清楚掉节点
    //            ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    //            for (BigInteger nodeId : bucket.getNodeIds()) {
    //                ExternalNodeInfo oldNode = bucket.getNode(nodeId);
    //                PingKademliaMessage pingKademliaMessage = new PingKademliaMessage();
    //                pingKademliaMessage.setSender(kademliaNodeServer.getNodeInfo());
    //                pingKademliaMessage.setReceiver(message.getSender());
    //                kademliaNodeServer.getTcpClient().sendMessage(pingKademliaMessage);
    //                // 超时任务：5秒未收到Pong，则认为不活跃，从桶中移除
    //                scheduler.schedule(() -> {
    //                    Date now = new Date();
    //                    // 计算时间差（毫秒）
    //                    long timeDiff = now.getTime() - oldNode.getLastSeen().getTime();
    //                    // 若最后活跃时间超过阈值（如5秒），则判定为不活跃
    //                    if (timeDiff > 5000) {
    //                        log.info("节点 {} 不活跃，从路由表移除", oldNode.getId());
    //                        bucket.remove(oldNode.getId()); // 从桶中移除
    //                    }
    //                }, 5, TimeUnit.SECONDS); // 超时时间设为3秒（可调整）
    //            }




}
