package com.pop.popcoinsystem.ca;

import com.pop.popcoinsystem.util.CryptoUtil;
import lombok.extern.slf4j.Slf4j;

import java.security.KeyPair;
import java.security.PublicKey;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * CA证书颁发机构实现
 * 负责证书签发、验证及节点身份管理
 */
@Slf4j
public class CertificateAuthority {
    // CA根密钥对(实际应用中需安全存储)
    private final KeyPair caKeyPair;
    // 已颁发证书缓存(序列号->证书)
    private final Map<String, CACertificate> issuedCertificates = new HashMap<>();
    // 吊销证书列表(序列号)
    private final Map<String, Date> revokedCertificates = new HashMap<>();

    public CertificateAuthority() {
        // 生成CA根密钥对(secp256k1曲线)
        this.caKeyPair = CryptoUtil.ECDSASigner.generateKeyPair();
        log.info("CA根密钥对生成完成，公钥: {}",
                CryptoUtil.bytesToHex(caKeyPair.getPublic().getEncoded()));
    }

    /**
     * 颁发新证书
     * @param subject 申请者标识(如节点ID)
     * @param subjectPublicKey 申请者公钥
     * @param validDays 有效期(天)
     * @return 签署的证书
     */
    public CACertificate issueCertificate(String subject, PublicKey subjectPublicKey, int validDays) {
        // 构建证书基础信息
        String serial = UUID.randomUUID().toString().replace("-", "");
        Date now = new Date();
        Date notAfter = new Date(now.getTime() + (long) validDays * 24 * 60 * 60 * 1000);

        CACertificate cert = new CACertificate(
                "v1",
                serial,
                "POP-CA-Root",  // CA标识
                subject,
                now,
                notAfter,
                subjectPublicKey.getEncoded(),
                "SHA256withECDSA",
                new byte[0]  // 临时占位
        );

        // CA用私钥签名证书内容
        byte[] signature = CryptoUtil.ECDSASigner.applySignature(
                caKeyPair.getPrivate(),
                cert.getSignContent()
        );
        cert.setSignature(signature);

        // 缓存证书
        issuedCertificates.put(serial, cert);
        return cert;
    }

    /**
     * 验证证书有效性
     * @param cert 待验证证书
     * @return 验证结果
     */
    public boolean verifyCertificate(CACertificate cert) {
        // 1. 检查是否吊销
        if (revokedCertificates.containsKey(cert.getSerialNumber())) {
            log.warn("证书已吊销: {}", cert.getSerialNumber());
            return false;
        }

        // 2. 检查有效期
        Date now = new Date();
        if (now.before(cert.getNotBefore()) || now.after(cert.getNotAfter())) {
            log.warn("证书不在有效期内: {}", cert.getSerialNumber());
            return false;
        }

        // 3. 验证签名(使用CA公钥)
        boolean sigValid = CryptoUtil.ECDSASigner.verifySignature(
                caKeyPair.getPublic(),
                cert.getSignContent(),
                cert.getSignature()
        );

        if (!sigValid) {
            log.warn("证书签名验证失败: {}", cert.getSerialNumber());
        }
        return sigValid;
    }

    /**
     * 吊销证书
     * @param serialNumber 证书序列号
     */
    public void revokeCertificate(String serialNumber) {
        if (issuedCertificates.containsKey(serialNumber)) {
            revokedCertificates.put(serialNumber, new Date());
            log.info("证书已吊销: {}", serialNumber);
        }
    }

    // getter
    public PublicKey getCaPublicKey() {
        return caKeyPair.getPublic();
    }
}