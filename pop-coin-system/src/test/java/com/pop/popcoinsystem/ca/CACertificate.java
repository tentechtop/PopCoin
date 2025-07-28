package com.pop.popcoinsystem.ca;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.io.ByteArrayOutputStream;
import java.util.Date;

/**
 * CA证书实体
 * 包含证书核心字段及数字签名
 */
@Data
@AllArgsConstructor
public class CACertificate {
    private String version;               // 证书版本
    private String serialNumber;          // 唯一序列号
    private String issuer;                // 颁发者(CA标识)
    private String subject;               // 证书持有者标识
    private Date notBefore;               // 生效时间
    private Date notAfter;                // 过期时间
    private byte[] subjectPublicKey;      // 持有者公钥
    private String signatureAlgorithm;    // 签名算法
    private byte[] signature;             // CA签名结果

    /**
     * 获取用于签名的证书内容(排除签名本身)
     */
    public byte[] getSignContent() {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            out.write(version.getBytes());
            out.write(serialNumber.getBytes());
            out.write(issuer.getBytes());
            out.write(subject.getBytes());
            out.write(Long.toString(notBefore.getTime()).getBytes());
            out.write(Long.toString(notAfter.getTime()).getBytes());
            out.write(subjectPublicKey);
            out.write(signatureAlgorithm.getBytes());
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("证书内容序列化失败", e);
        }
    }
}