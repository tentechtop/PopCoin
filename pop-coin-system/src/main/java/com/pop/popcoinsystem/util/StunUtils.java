package com.pop.popcoinsystem.util;

import java.net.*;
import java.nio.ByteBuffer;

/**
 * STUN工具类，用于获取设备的公网IP地址和端口
 */
public class StunUtils {
    private static final int STUN_PORT = 19302;
    private static final String DEFAULT_STUN_SERVER = "stun.l.google.com";
    private static final int TIMEOUT = 5000; // 5秒超时

    /**
     * 存储STUN服务器返回的公网地址信息
     */
    public static class StunInfo {
        private String publicIp;
        private int publicPort;

        public StunInfo(String publicIp, int publicPort) {
            this.publicIp = publicIp;
            this.publicPort = publicPort;
        }

        public String getPublicIp() {
            return publicIp;
        }

        public int getPublicPort() {
            return publicPort;
        }

        @Override
        public String toString() {
            return "公网IP: " + publicIp + ", 公网端口: " + publicPort;
        }
    }

    /**
     * 使用默认STUN服务器获取公网IP和端口
     * @return 包含公网IP和端口的StunInfo对象，如果失败则返回null
     */
    public static StunInfo getPublicAddress() {
        return getPublicAddress(DEFAULT_STUN_SERVER);
    }

    /**
     * 使用指定的STUN服务器获取公网IP和端口
     * @param stunServer STUN服务器地址
     * @return 包含公网IP和端口的StunInfo对象，如果失败则返回null
     */
    public static StunInfo getPublicAddress(String stunServer) {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(TIMEOUT);

            // 创建STUN绑定请求
            byte[] request = createStunRequest();

            // 发送请求
            InetAddress serverAddress = InetAddress.getByName(stunServer);
            DatagramPacket packet = new DatagramPacket(request, request.length, serverAddress, STUN_PORT);
            socket.send(packet);

            // 接收响应
            byte[] response = new byte[1024];
            DatagramPacket responsePacket = new DatagramPacket(response, response.length);
            socket.receive(responsePacket);

            // 解析响应并返回结果
            return parseStunResponse(response, responsePacket.getLength());

        } catch (Exception e) {
            System.err.println("STUN操作失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 创建STUN绑定请求数据包
     */
    private static byte[] createStunRequest() {
        ByteBuffer buffer = ByteBuffer.allocate(20);
        // STUN消息类型: 0x0001 (绑定请求)
        buffer.putShort((short) 0x0001);
        // 消息长度 (不包含消息头)
        buffer.putShort((short) 0x0000);
        // 事务ID (96位随机数)
        byte[] transactionId = new byte[12];
        new java.util.Random().nextBytes(transactionId);
        buffer.put(transactionId);
        return buffer.array();
    }

    /**
     * 解析STUN响应，提取公网IP和端口
     */
    private static StunInfo parseStunResponse(byte[] data, int length) {
        if (length < 20) {
            System.out.println("无效的STUN响应: 长度不足");
            return null;
        }

        ByteBuffer buffer = ByteBuffer.wrap(data, 0, length);
        short type = buffer.getShort();

        // 检查是否是绑定响应 (0x0101)
        if (type != 0x0101) {
            System.out.println("不是STUN绑定响应，类型: 0x" + Integer.toHexString(type));
            return null;
        }

        short messageLength = buffer.getShort();
        // 验证消息长度是否合理
        if (messageLength + 20 > length) {
            System.out.println("无效的消息长度: " + messageLength);
            return null;
        }

        byte[] transactionId = new byte[12];
        buffer.get(transactionId);

        // 解析属性
        int remaining = messageLength;
        while (remaining > 0 && buffer.hasRemaining()) {
            // 检查是否有足够的字节解析属性头
            if (buffer.remaining() < 4) {
                System.out.println("属性头字节不足，剩余: " + buffer.remaining());
                break;
            }

            short attrType = buffer.getShort();
            short attrLength = buffer.getShort();

            // 检查属性长度是否合理
            if (attrLength < 0 || attrLength > buffer.remaining()) {
                System.out.println("无效的属性长度: " + attrLength + ", 剩余字节: " + buffer.remaining());
                break;
            }

            if (attrType == 0x0001) { // Mapped Address属性
                StunInfo info = parseMappedAddress(buffer, attrLength);
                if (info != null) {
                    return info;
                }
            } else {
                // 对于其他属性，仅移动指针
                buffer.position(buffer.position() + attrLength);
            }

            remaining -= (4 + attrLength);
        }

        return null;
    }

    /**
     * 解析映射地址属性，提取IP和端口
     */
    private static StunInfo parseMappedAddress(ByteBuffer buffer, int length) {
        if (length < 4) {
            System.out.println("映射地址属性长度不足");
            return null;
        }

        // 保存当前位置，以便出错时可以恢复
        int position = buffer.position();

        try {
            buffer.get(); // 忽略第一个字节 (保留)
            byte family = buffer.get(); // 地址族: 0x01=IPv4, 0x02=IPv6

            if (family == 0x01) { // IPv4
                if (buffer.remaining() < 6) { // 2字节端口 + 4字节IP
                    System.out.println("IPv4地址数据不足");
                    return null;
                }

                short port = buffer.getShort();
                byte[] ipBytes = new byte[4];
                buffer.get(ipBytes);

                InetAddress address = InetAddress.getByAddress(ipBytes);
                return new StunInfo(address.getHostAddress(), port);
            } else if (family == 0x02) { // IPv6
                System.out.println("暂不支持IPv6地址解析");
                // 跳过IPv6地址数据
                buffer.position(position + length);
            } else {
                System.out.println("未知地址族: " + family);
                // 跳过未知地址族的数据
                buffer.position(position + length);
            }
        } catch (Exception e) {
            System.err.println("解析映射地址失败: " + e.getMessage());
            // 恢复位置指针
            buffer.position(position);
        }

        return null;
    }
}
