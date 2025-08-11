package com.pop.popcoinsystem.util;

import java.io.IOException;
import java.net.*;
import java.util.Enumeration;

/**
 * @author wangxiaorui
 * @date 2025/2/8
 * @desc
 */
public class NetworkUtil {

    /**
     * 获取本地ip
     */
    public static String getLocalIp() {
        try {
            InetAddress ip4 = InetAddress.getLocalHost();
            return ip4.getHostAddress();
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }
        return "";
    }

    /**
     * 判断地址是否是IPv4格式
     */
    public static boolean isIpv4(byte[] data) {
        //如果地址长度是4个字节，或者前10个字节时00,11-12个字节是ff则为ipv4
        if (data.length == 4) {
            return true;
        }
        if (data.length == 16) {
            for (int i = 0; i < 12; i++) {
                if (i < 10) {
                    if (data[i] != (byte) 0x00) {
                        return false;
                    }
                } else {
                    if (data[i] != (byte) 0xff) {
                        return false;
                    }
                }
            }
            return true;
        }
        return false;
    }

    /**
     * 获取本机MAC地址
     */
    public static String getMacAddress() {
        try {
            Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
            while (networkInterfaces.hasMoreElements()) {
                NetworkInterface networkInterface = networkInterfaces.nextElement();
                byte[] mac = networkInterface.getHardwareAddress();
                if (mac != null) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < mac.length; i++) {
                        sb.append(String.format("%02X%s", mac[i], (i < mac.length - 1) ? "-" : ""));
                    }
                    return sb.toString();
                }
            }
        } catch (SocketException e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * 获取可用端口
     * @param startPort 起始端口
     * @param endPort   结束端口
     * @return 可用端口
     */
    public static int findAvailablePort(int startPort, int endPort) {
        for (int port = startPort; port <= endPort; port++) {
            try (ServerSocket serverSocket = new ServerSocket(port)) {
                return port;
            } catch (IOException e) {
                // Port is not available, continue to the next port
            }
        }
        throw new RuntimeException("No available port found in the range: " + startPort + " to " + endPort);
    }

    public static boolean isPortOpen(InetAddress address, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(address, port), 1000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 判断IP地址或端口是否改变
     *
     * @param oldIp   旧IP地址
     * @param oldPort 旧端口
     * @param newIp   新IP地址
     * @param newPort 新端口
     * @return IP地址或端口是否改变
     */
    public static boolean ipOrPortIsChanged(String oldIp, int oldPort, String newIp, int newPort) {
        return !oldIp.equals(newIp) || oldPort != newPort;
    }

    /**
     * 获取局域网IP地址
     *
     * @return 局域网IP地址
     * @throws SocketException
     */
    public static InetAddress getLocalInetAddress() throws SocketException {
        Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
        while (networkInterfaces.hasMoreElements()) {
            NetworkInterface ni = networkInterfaces.nextElement();
            Enumeration<InetAddress> inetAddresses = ni.getInetAddresses();
            while (inetAddresses.hasMoreElements()) {
                InetAddress ia = inetAddresses.nextElement();
                if (!ia.isLoopbackAddress() && ia.isSiteLocalAddress()) {
                    return ia;
                }
            }
        }
        throw new RuntimeException("No suitable network interface found.");
    }
}
