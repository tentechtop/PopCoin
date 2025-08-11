package com.pop.popcoinsystem.network.service;

import com.pop.popcoinsystem.util.NetworkUtil;
import lombok.extern.slf4j.Slf4j;
import org.bitlet.weupnp.GatewayDevice;
import org.bitlet.weupnp.GatewayDiscover;
import org.bitlet.weupnp.PortMappingEntry;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.net.InetAddress;
import java.util.Enumeration;
import java.util.Scanner;

/**
 * UPnP端口映射工具类
 * 用于自动配置路由器端口映射，实现内网穿透
 */
@Slf4j
public class UPnPHelper {
    private GatewayDevice gatewayDevice;
    private String localIp; // 本地IP地址
    private int localPort;  // 本地端口
    private int externalPort; // 映射的外部端口
    private String protocol = "TCP"; // 协议类型
    private String description = "P2P节点端口映射";

    public UPnPHelper(int localPort, int externalPort) {
        this.localPort = localPort;
        this.externalPort = externalPort;
        try {
            this.localIp = getLocalIpAddress();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 发现局域网内的UPnP网关设备
     */
    public boolean discoverGateway() throws IOException, SAXException, ParserConfigurationException {
        GatewayDiscover gatewayDiscover = new GatewayDiscover();
        gatewayDiscover.discover();

        // 获取第一个可用的网关设备
        gatewayDevice = gatewayDiscover.getValidGateway();
        if (gatewayDevice != null) {
            System.out.println("发现UPnP网关: " + gatewayDevice.getFriendlyName());
            System.out.println("网关外部IP: " + gatewayDevice.getExternalIPAddress());
            return true;
        } else {
            System.out.println("未发现支持UPnP的网关设备");
            return false;
        }
    }

    /**
     * 添加端口映射
     * 将外部端口映射到本地IP和端口
     */
    public boolean addPortMapping() throws Exception {
        if (gatewayDevice == null) {
            if (!discoverGateway()) {
                return false;
            }
        }

        // 检查端口映射是否已存在
        PortMappingEntry existing = new PortMappingEntry();
        if (gatewayDevice.getSpecificPortMappingEntry(externalPort, protocol, existing)) {
            System.out.println("端口映射已存在: " + externalPort);
            return true;
        }

        // 添加新的端口映射
        boolean success = gatewayDevice.addPortMapping(
                externalPort,        // 外部端口
                localPort,           // 内部端口
                localIp,             // 内部IP地址
                protocol,            // 协议
                description          // 描述
        );

        if (success) {
            System.out.println("端口映射成功: " + externalPort + " -> " + localIp + ":" + localPort);
            return true;
        } else {
            System.out.println("端口映射失败");
            return false;
        }
    }

    /**
     * 删除端口映射
     */
    public boolean deletePortMapping() throws Exception {
        if (gatewayDevice == null) {
            return false;
        }

        boolean success = gatewayDevice.deletePortMapping(externalPort, protocol);
        if (success) {
            System.out.println("已删除端口映射: " + externalPort);
        }
        return success;
    }

    /**
     * 获取本地IP地址（非127.0.0.1）
     */
    private String getLocalIpAddress() throws Exception {
        String localIp1 = NetworkUtil.getLocalIp();
        log.info("localIp1: {}", localIp1);
        return localIp1;
    }

    /**
     * 获取网关的外部IP地址
     */
    public String getExternalIp() throws Exception {
        if (gatewayDevice != null) {
            return gatewayDevice.getExternalIPAddress();
        }
        return null;
    }

    /**
     * 主方法用于测试UPnP功能
     */
    public static void main(String[] args) {
        // 测试用的本地端口和外部端口
        int localPort = 9999;
        int externalPort = 50000;

        UPnPHelper upnpHelper = new UPnPHelper(localPort, externalPort);

        try {
            System.out.println("=== 开始UPnP功能测试 ===");

            // 发现网关
            System.out.println("\n1. 发现UPnP网关...");
            boolean gatewayFound = upnpHelper.discoverGateway();
            if (!gatewayFound) {
                System.out.println("未找到网关，测试结束");
                return;
            }

            // 添加端口映射
            System.out.println("\n2. 添加端口映射...");
            boolean mappingAdded = upnpHelper.addPortMapping();
            if (!mappingAdded) {
                System.out.println("端口映射添加失败，测试结束");
                return;
            }

            // 显示外部IP
            System.out.println("\n3. 外部IP地址: " + upnpHelper.getExternalIp());
            System.out.println("   可以使用外部IP:" + externalPort + " 测试连接");

            // 等待用户确认，给时间进行测试
            System.out.println("\n4. 按Enter键继续删除端口映射...");
            Scanner scanner = new Scanner(System.in);
            scanner.nextLine();

            // 删除端口映射
            System.out.println("\n5. 删除端口映射...");
            boolean mappingDeleted = upnpHelper.deletePortMapping();
            if (mappingDeleted) {
                System.out.println("端口映射已删除");
            } else {
                System.out.println("端口映射删除失败，请手动检查路由器设置");
            }

            System.out.println("\n=== UPnP功能测试完成 ===");

        } catch (Exception e) {
            System.out.println("测试过程中发生错误: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
