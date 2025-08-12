package com.pop.popcoinsystem.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.*;
import java.util.Enumeration;

/**
 * 网络工具类，提供IP、端口、MAC地址等网络相关操作
 * @author wangxiaorui
 * @date 2025/2/8
 */
public class NetworkUtil {
    private static final Logger logger = LoggerFactory.getLogger(NetworkUtil.class);

    /**
     * 获取本地非环回IPv4地址
     * @return 本地IP地址，获取失败返回空字符串
     */
    public static String getLocalIp() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                // 过滤掉127.0.0.1和非活动网卡
                if (iface.isLoopback() || !iface.isUp()) {
                    continue;
                }

                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    // 只返回IPv4地址
                    if (addr instanceof Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (SocketException e) {
            logger.error("获取本地IP地址失败", e);
        }
        return "";
    }

    /**
     * 判断地址是否是IPv4格式
     */
    public static boolean isIpv4(InetAddress address) {
        return address instanceof Inet4Address;
    }

    /**
     * 判断地址是否是IPv6格式
     */
    public static boolean isIpv6(InetAddress address) {
        return address instanceof Inet6Address;
    }

    /**
     * 获取本机活动网络接口的MAC地址
     * @return MAC地址，格式如XX-XX-XX-XX-XX-XX，获取失败返回null
     */
    public static String getMacAddress() {
        try {
            Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
            while (networkInterfaces.hasMoreElements()) {
                NetworkInterface networkInterface = networkInterfaces.nextElement();

                // 跳过非活动接口和环回接口
                if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                    continue;
                }

                byte[] mac = networkInterface.getHardwareAddress();
                if (mac != null && mac.length == 6) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < mac.length; i++) {
                        sb.append(String.format("%02X%s", mac[i], (i < mac.length - 1) ? "-" : ""));
                    }
                    return sb.toString();
                }
            }
        } catch (SocketException e) {
            logger.error("获取MAC地址失败", e);
        }
        return null;
    }

    /**
     * 获取可用端口
     * @param startPort 起始端口
     * @param endPort   结束端口
     * @return 可用端口
     * @throws IOException 如果在指定范围内没有可用端口
     */
    public static int findAvailablePort(int startPort, int endPort) throws IOException {
        if (startPort < 1 || endPort > 65535 || startPort > endPort) {
            throw new IllegalArgumentException("无效的端口范围: " + startPort + " 到 " + endPort);
        }

        for (int port = startPort; port <= endPort; port++) {
            try (ServerSocket serverSocket = new ServerSocket(port, 1, InetAddress.getByName("localhost"))) {
                return port;
            } catch (BindException e) {
                // 端口已被占用，尝试下一个
                continue;
            }
        }
        throw new IOException("在端口范围 " + startPort + " 到 " + endPort + " 中未找到可用端口");
    }

    /**
     * 检查指定地址的端口是否开放
     * @param address 要检查的地址
     * @param port 要检查的端口
     * @return 如果端口开放返回true，否则返回false
     */
    public static boolean isPortOpen(InetAddress address, int port) {
        if (port < 1 || port > 65535) {
            return false;
        }

        try (Socket socket = new Socket()) {
            // 设置连接超时时间为1秒
            socket.connect(new InetSocketAddress(address, port), 1000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 判断IP地址或端口是否改变
     * @param oldIp   旧IP地址
     * @param oldPort 旧端口
     * @param newIp   新IP地址
     * @param newPort 新端口
     * @return IP地址或端口是否改变
     */
    public static boolean ipOrPortIsChanged(String oldIp, int oldPort, String newIp, int newPort) {
        // 处理空值情况
        if (oldIp == null && newIp == null) {
            return oldPort != newPort;
        }
        if (oldIp == null || newIp == null) {
            return true;
        }
        return !oldIp.equals(newIp) || oldPort != newPort;
    }

    /**
     * 获取局域网IPv4地址
     * @return 局域网IP地址
     * @throws SocketException 如果没有找到合适的网络接口
     */
    public static InetAddress getLocalInetAddress() throws SocketException {
        Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
        while (networkInterfaces.hasMoreElements()) {
            NetworkInterface ni = networkInterfaces.nextElement();

            // 跳过非活动接口和环回接口
            if (!ni.isUp() || ni.isLoopback()) {
                continue;
            }

            Enumeration<InetAddress> inetAddresses = ni.getInetAddresses();
            while (inetAddresses.hasMoreElements()) {
                InetAddress ia = inetAddresses.nextElement();
                if (!ia.isLoopbackAddress() && ia.isSiteLocalAddress() && ia instanceof Inet4Address) {
                    return ia;
                }
            }
        }
        throw new SocketException("未找到合适的网络接口");
    }
}
