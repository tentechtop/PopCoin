package com.pop.popcoinsystem.util;

import com.pop.popcoinsystem.data.script.AddressType;
import com.pop.popcoinsystem.data.script.Script;
import com.pop.popcoinsystem.data.script.ScriptPubKey;
import com.pop.popcoinsystem.network.enums.NETVersion;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.digests.SHA512Digest;
import org.bouncycastle.crypto.macs.HMac;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.jcajce.provider.digest.Keccak;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;
import org.bouncycastle.jce.spec.ECPrivateKeySpec;
import org.bouncycastle.jce.spec.ECPublicKeySpec;
import org.bouncycastle.math.ec.ECPoint;
import org.bouncycastle.math.ec.FixedPointCombMultiplier;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.*;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static com.pop.popcoinsystem.constant.BlockChainConstants.NET_VERSION;


/**
 * 加密工具类 - 提供哈希、签名和密钥管理功能
 */
@Slf4j

public class CryptoUtil {
    public static byte PRE_P2PKH = 0x00;
    public static byte PRE_P2SH = 0x05;
    static {
        PRE_P2PKH = NETVersion.getP2PKHPreAddress(NET_VERSION);
        PRE_P2SH = NETVersion.getP2SHPreAddress(NET_VERSION);
    }

    // secp256k1曲线参数（比特币/以太坊使用）
    private static final X9ECParameters SECP256K1_PARAMS = org.bouncycastle.asn1.sec.SECNamedCurves.getByName("secp256k1");
    private static final BigInteger CURVE_ORDER = SECP256K1_PARAMS.getN(); // 曲线阶数（私钥必须小于该值）
    private static final ECPoint G = SECP256K1_PARAMS.getG(); // 生成点

    // ------------------------------
    // 1. 实现HMAC-SHA512算法（BIP-32基础）
    // ------------------------------
    public static byte[] hmacSha512(byte[] key, byte[] data) {
        HMac hmac = new HMac(new SHA512Digest());
        hmac.init(new KeyParameter(key));
        hmac.update(data, 0, data.length);
        byte[] result = new byte[hmac.getMacSize()];
        hmac.doFinal(result, 0);
        return result;
    }
    // ------------------------------
    // 2. 从私钥派生公钥（secp256k1曲线）
    // ------------------------------
    public static PublicKey derivePublicKey(PrivateKey privateKey) {
        try {
            BigInteger privKey = ((java.security.interfaces.ECPrivateKey) privateKey).getS();
            ECPoint publicPoint = new FixedPointCombMultiplier().multiply(G, privKey);

            ECNamedCurveParameterSpec curveSpec = ECNamedCurveTable.getParameterSpec("secp256k1");
            // 直接使用SECP256K1_SPEC作为参数
            ECPublicKeySpec ecPublicKeySpec = new ECPublicKeySpec(publicPoint, curveSpec);

            KeyFactory keyFactory = KeyFactory.getInstance("EC", "BC");
            return keyFactory.generatePublic(ecPublicKeySpec);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException | NoSuchProviderException e) {
            throw new RuntimeException("公钥派生失败", e);
        }
    }

    // ------------------------------
    // 3. 派生子私钥（BIP-32分层派生）
    // 输入：父私钥、父链码、派生路径（如[44|0x80000000, 60|0x80000000, ...]）
    // 输出：子私钥
    // ------------------------------
    public static PrivateKey deriveChildPrivateKey(byte[] parentPrivKeyBytes, byte[] parentChainCode, List<Integer> path) {
        BigInteger parentPrivKey = new BigInteger(1, parentPrivKeyBytes);
        byte[] currentChainCode = parentChainCode;

        for (int index : path) {
            // 1. 构建HMAC-SHA512的输入数据（区分强化派生和普通派生）
            byte[] data = new byte[37]; // 32字节公钥/私钥 + 4字节索引
            if ((index & 0x80000000) != 0) {
                // 强化派生（索引 >= 0x80000000）：输入父私钥（需补0x00前缀）
                data[0] = 0x00;
                System.arraycopy(parentPrivKey.toByteArray(), 0, data, 1, 32);
            } else {
                // 普通派生：输入父公钥
                PublicKey parentPubKey = derivePublicKey(
                        bytesToPrivateKey(parentPrivKeyBytes)
                );
                byte[] pubKeyBytes = parentPubKey.getEncoded();
                System.arraycopy(pubKeyBytes, 0, data, 0, 33); // 33字节压缩公钥
            }
            // 写入4字节索引（大端模式）
            data[33] = (byte) (index >> 24);
            data[34] = (byte) (index >> 16);
            data[35] = (byte) (index >> 8);
            data[36] = (byte) index;

            // 2. 计算HMAC-SHA512得到派生结果（64字节：前32字节=k，后32字节=新链码）
            byte[] hmacResult = hmacSha512(currentChainCode, data);
            byte[] k = Arrays.copyOfRange(hmacResult, 0, 32);
            currentChainCode = Arrays.copyOfRange(hmacResult, 32, 64);

            // 3. 计算子私钥 = (父私钥 + k) mod 曲线阶数
            BigInteger kInt = new BigInteger(1, k);
            BigInteger childPrivKey = parentPrivKey.add(kInt).mod(CURVE_ORDER);

            // 4. 更新父私钥为当前子私钥（用于下一层派生）
            parentPrivKey = childPrivKey;
        }

        // 5. 转换为PrivateKey对象
        return bytesToPrivateKey(parentPrivKey.toByteArray());
    }


    // ------------------------------
    // 辅助方法：字节数组转私钥对象
    // ------------------------------
    // 修正的字节转私钥方法
    public static PrivateKey bytesToPrivateKey(byte[] privKeyBytes) {
        try {
            byte[] normalized = new byte[32];
            System.arraycopy(privKeyBytes, Math.max(0, privKeyBytes.length - 32), normalized, Math.max(0, 32 - privKeyBytes.length), Math.min(32, privKeyBytes.length));

            BigInteger privKey = new BigInteger(1, normalized);

            // 获取secp256k1曲线参数（使用Bouncy Castle的ECNamedCurveParameterSpec）
            ECNamedCurveParameterSpec curveSpec = ECNamedCurveTable.getParameterSpec("secp256k1");

            // 使用SECP256K1_SPEC作为参数
            ECPrivateKeySpec privKeySpec = new ECPrivateKeySpec(privKey, curveSpec);

            KeyFactory keyFactory = KeyFactory.getInstance("EC", "BC");
            return keyFactory.generatePrivate(privKeySpec);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException | NoSuchProviderException e) {
            throw new RuntimeException("私钥转换失败", e);
        }
    }



    //main
    public static void main(String[] args) {
        KeyPair keyPair = CryptoUtil.ECDSASigner.generateKeyPair();
        PrivateKey privateKey = keyPair.getPrivate();
        PublicKey publicKey = keyPair.getPublic();
        byte[] encoded = publicKey.getEncoded();
        PublicKey publicKey1 = ECDSASigner.bytesToPublicKey(encoded);
        byte[] encoded1 = privateKey.getEncoded();
        PrivateKey privateKey1 = ECDSASigner.bytesToPrivateKey(encoded1);
        byte[] txToSign = new byte[32];
        Arrays.fill(txToSign, (byte)0x01);
        byte[] signature = CryptoUtil.ECDSASigner.applySignature(privateKey1, txToSign);
        boolean b = ECDSASigner.verifySignature(publicKey1, txToSign, signature);
        log.info("signature: {}",b);
    }

    /**
     * 椭圆曲线
     */
    public static class ECDSASigner {
        /**
         * 将字节数组转换为EC公钥对象
         * @param publicKeyBytes X.509编码的公钥字节数组（与exportPublicKey导出格式对应）
         * @return 转换后的EC公钥对象
         */
        public static PublicKey bytesToPublicKey(byte[] publicKeyBytes) {
            try {
                // 使用BC提供者确保与密钥生成逻辑兼容
                KeyFactory keyFactory = KeyFactory.getInstance("EC", "BC");
                X509EncodedKeySpec keySpec = new X509EncodedKeySpec(publicKeyBytes);
                return keyFactory.generatePublic(keySpec);
            } catch (Exception e) {
                throw new RuntimeException("字节数组转换为公钥失败", e);
            }
        }
        /**
         * 将字节数组转换为EC私钥对象
         * @param privateKeyBytes PKCS#8编码的私钥字节数组（与exportPrivateKey导出格式对应）
         * @return 转换后的EC私钥对象
         */
        public static PrivateKey bytesToPrivateKey(byte[] privateKeyBytes) {
            try {
                // 使用BC提供者确保与密钥生成逻辑兼容
                KeyFactory keyFactory = KeyFactory.getInstance("EC", "BC");
                PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(privateKeyBytes);
                return keyFactory.generatePrivate(keySpec);
            } catch (Exception e) {
                throw new RuntimeException("字节数组转换为私钥失败", e);
            }
        }
        // 静态初始化Bouncy Castle提供者
        static {
            Security.addProvider(new BouncyCastleProvider());
        }
        /**
         * 生成ECDSA密钥对（secp256k1曲线）
         */
        public static KeyPair generateKeyPair() {
            try {
                KeyPairGenerator keyGen = KeyPairGenerator.getInstance("EC", "BC");
                SecureRandom random = SecureRandom.getInstanceStrong();
                ECGenParameterSpec ecSpec = new ECGenParameterSpec("secp256k1");
                keyGen.initialize(ecSpec, random);
                return keyGen.generateKeyPair();
            } catch (Exception e) {
                throw new RuntimeException("生成密钥对失败", e);
            }
        }
        /**
         * 应用ECDSA签名 - 对原始数据进行签名
         */
        public static byte[] applySignature(PrivateKey privateKey, byte[] data) {
            try {
                // 显式指定BC提供者
                Signature dsa = Signature.getInstance("SHA256withECDSA", "BC");
                dsa.initSign(privateKey);
                dsa.update(data); // 传入原始数据
                return dsa.sign();
            } catch (Exception e) {
                throw new RuntimeException("应用签名失败", e);
            }
        }
        /**
         * 验证ECDSA签名
         */
        public static boolean verifySignature(PublicKey publicKey, byte[] data, byte[] signature) {
            try {
                // 显式指定BC提供者
                Signature dsa = Signature.getInstance("SHA256withECDSA", "BC");
                dsa.initVerify(publicKey);
                dsa.update(data);//明文
                return dsa.verify(signature);
            } catch (Exception e) {
                throw new RuntimeException("验证签名失败", e);
            }
        }
        /**
         * 从种子(seed)生成ECDSA密钥对（遵循BIP-32派生规则，基于secp256k1曲线）
         * @param seed 种子字节数组（通常由助记词生成）
         * @return 密钥对（包含公钥和私钥）
         */
        public static KeyPair generateKeyPairFromSeed(byte[] seed) {
            try {
                // 1. 使用HMAC-SHA512从种子派生私钥（BIP-32标准）
                Mac hmacSha512 = Mac.getInstance("HmacSHA512", "BC");
                SecretKeySpec hmacKey = new SecretKeySpec("Bitcoin seed".getBytes(StandardCharsets.UTF_8), "HmacSHA512");
                hmacSha512.init(hmacKey);
                byte[] hmacResult = hmacSha512.doFinal(seed); // 64字节结果：前32字节=私钥，后32字节=链码

                // 2. 提取前32字节作为私钥字节
                byte[] privateKeyBytes = Arrays.copyOfRange(hmacResult, 0, 32);
                BigInteger privateKeyInt = new BigInteger(1, privateKeyBytes); // 转换为正整数

                // 3. 获取secp256k1曲线参数（使用Bouncy Castle的ECNamedCurveParameterSpec）
                ECNamedCurveParameterSpec curveSpec = ECNamedCurveTable.getParameterSpec("secp256k1");
                BigInteger n = curveSpec.getN(); // 曲线的阶（order）

                // 4. 验证私钥有效性（必须在[1, n-1]范围内）
                if (privateKeyInt.compareTo(BigInteger.ONE) < 0 || privateKeyInt.compareTo(n.subtract(BigInteger.ONE)) > 0) {
                    throw new IllegalArgumentException("派生的私钥超出secp256k1曲线的有效范围");
                }
                // 5. 生成私钥（确保参数类型正确）
                org.bouncycastle.jce.spec.ECParameterSpec ecParams = curveSpec; // ECNamedCurveParameterSpec extends ECParameterSpec
                ECPrivateKeySpec privateKeySpec = new ECPrivateKeySpec(privateKeyInt, ecParams);
                KeyFactory keyFactory = KeyFactory.getInstance("EC", "BC");
                PrivateKey privateKey = keyFactory.generatePrivate(privateKeySpec);
                // 6. 从私钥推导公钥（公钥 = 私钥 * 基点G）
                ECPoint multiply = curveSpec.getG().multiply(privateKeyInt);// 计算公钥点
                org.bouncycastle.jce.spec.ECPublicKeySpec ecPublicKeySpec = new org.bouncycastle.jce.spec.ECPublicKeySpec(multiply, ecParams);
                PublicKey publicKey = keyFactory.generatePublic(ecPublicKeySpec);
                return new KeyPair(publicKey, privateKey);
            } catch (Exception e) {
                throw new RuntimeException("从种子生成密钥对失败", e);
            }
        }


        //根据公钥生成P2PKH类型地址
        public static String createP2PKHAddressByPK(byte[] publicKey) {
            try {
                // 1. 获取公钥字节（去除开头的0x04字节，保留64字节的坐标）
                // 移除X.509编码头，获取原始EC公钥字节
                // 这里简化处理，实际使用时可能需要根据具体编码调整
                byte[] ripeMD160Hash = createP2PKHByPK(publicKey);
                // 4. 添加版本字节（0x00代表 主网地址）
                byte[] versionedHash = new byte[ripeMD160Hash.length + 1];
                versionedHash[0] = PRE_P2PKH; // 主网地址前缀
                System.arraycopy(ripeMD160Hash, 0, versionedHash, 1, ripeMD160Hash.length);

                // 5. 计算校验和（对versionedHash进行两次SHA-256哈希，取前4字节）
                byte[] firstSHA = applySHA256(versionedHash);
                byte[] secondSHA = applySHA256(firstSHA);
                byte[] checksum = Arrays.copyOfRange(secondSHA, 0, 4);

                // 6. 将校验和添加到versionedHash后面
                byte[] addressBytes = new byte[versionedHash.length + checksum.length];
                System.arraycopy(versionedHash, 0, addressBytes, 0, versionedHash.length);
                System.arraycopy(checksum, 0, addressBytes, versionedHash.length, checksum.length);

                // 7. 使用Base58编码生成最终地址
                return Base58.encode(addressBytes);
            } catch (Exception e) {
                throw new RuntimeException("生成P2PKH地址失败", e);
            }
        }
        //检查生成的地址是否符合标准
        public static boolean isValidP2PKHAddress(String address) {
            try {
                // 1. Base58解码地址
                byte[] decoded = Base58.decode(address);

                // 2. 检查长度（版本1字节 + RIPEMD160哈希20字节 + 校验和4字节 = 25字节）
                if (decoded.length != 25) {
                    return false;
                }

                // 3. 分离版本、哈希、校验和
                byte version = decoded[0];
                byte[] hash = Arrays.copyOfRange(decoded, 1, 21);
                byte[] checksum = Arrays.copyOfRange(decoded, 21, 25);

                // 4. 重新计算校验和并比对
                byte[] versionedHash = new byte[21];
                versionedHash[0] = version;
                System.arraycopy(hash, 0, versionedHash, 1, 20);

                byte[] calculatedChecksum = Arrays.copyOfRange(applySHA256(applySHA256(versionedHash)), 0, 4);

                return Arrays.equals(checksum, calculatedChecksum) && version == PRE_P2PKH; // 主网版本校验
            } catch (Exception e) {
                return false; // 解码失败或校验不通过
            }
        }
        //根据公钥生成P2PKH类型公钥哈希
        public static byte[] createP2PKHByPK(byte[] publicKey) {
            try {
                // 对原始公钥进行哈希计算
                byte[] sha256Hash = applySHA256(publicKey);
                return applyRIPEMD160(sha256Hash);
            } catch (Exception e) {
                throw new RuntimeException("生成P2PKH地址失败", e);
            }
        }
        //地址转公钥哈希
        public static byte[] addressToP2PKH(String address) {
            byte[] decoded = Base58.decode(address);
            return Arrays.copyOfRange(decoded, 1, 21);
        }



        //根据公钥生成P2SH类型地址  2. 创建标准P2PKH赎回脚本: OP_DUP OP_HASH160 <20字节hash> OP_EQUALVERIFY OP_CHECKSIG
        public static String createP2SHAddressByPK(byte[] redeemScript) {
            try {
                //  计算脚本的HASH160（SHA256 -> RIPEMD160）
                byte[] scriptHash = applyRIPEMD160(applySHA256(redeemScript));

                //  添加P2SH版本字节(0x05)
                byte[] versionedHash = new byte[scriptHash.length + 1];
                versionedHash[0] = PRE_P2SH; // P2SH主网版本
                System.arraycopy(scriptHash, 0, versionedHash, 1, scriptHash.length);

                //  计算校验和并编码
                byte[] checksum = Arrays.copyOfRange(applySHA256(applySHA256(versionedHash)), 0, 4);
                byte[] addressBytes = new byte[versionedHash.length + checksum.length];
                System.arraycopy(versionedHash, 0, addressBytes, 0, versionedHash.length);
                System.arraycopy(checksum, 0, addressBytes, versionedHash.length, checksum.length);
                return Base58.encode(addressBytes);
            } catch (Exception e) {
                throw new RuntimeException("生成P2SH地址失败", e);
            }
        }
        public static boolean isValidP2SHAddress(String address) {
            byte[] decoded = Base58.decode(address);
            if (decoded.length != 25) return false; // 1字节版本 + 20字节哈希 + 4字节校验和

            byte version = decoded[0];
            byte[] hash = Arrays.copyOfRange(decoded, 1, 21);
            byte[] checksum = Arrays.copyOfRange(decoded, 21, 25);

            // 重新计算校验和
            byte[] versionedHash = new byte[21];
            versionedHash[0] = version;
            System.arraycopy(hash, 0, versionedHash, 1, 20);
            byte[] calculatedChecksum = Arrays.copyOfRange(applySHA256(applySHA256(versionedHash)), 0, 4);
            return Arrays.equals(checksum, calculatedChecksum) && version == PRE_P2SH; // P2SH主网版本
        }
        //根据公钥生成P2SH类型公钥哈希
        public static byte[] createP2SHByPK(byte[] publicKey) {
            try {
                byte[] pubKeyHash = createP2PKHByPK(publicKey);

                // 创建标准赎回脚本
                byte[] script = new byte[25];
                script[0] = 0x76; // OP_DUP
                script[1] = (byte) 0xA9; // OP_HASH160
                script[2] = 0x14; // 20字节
                System.arraycopy(pubKeyHash, 0, script, 3, 20);
                script[23] = (byte) 0x88; // OP_EQUALVERIFY
                script[24] = (byte) 0xAC; // OP_CHECKSIG

                // 返回脚本的HASH160
                return applyRIPEMD160(applySHA256(script));
            } catch (Exception e) {
                throw new RuntimeException("生成P2SH地址失败", e);
            }
        }
        //地址转公钥哈希
        public static byte[] addressToP2SH(String address) {
            try {
                byte[] decoded = Base58.decode(address);
                if (decoded.length != 25) {
                    return null;
                }
                return Arrays.copyOfRange(decoded, 1, 21);
            } catch (Exception e) {
                return null;
            }
        }



        //根据公钥生成P2WPKH类型地址 （隔离见证v0）
        public static String createP2WPKHAddressByPK(byte[] publicKey) {
            try {
                // 1. 获取公钥的HASH160（20字节）
                byte[] pubKeyHash = createP2PKHByPK(publicKey);
                // 2. 构建数据：版本字节(0x00) + 20字节哈希（共21字节）
                byte[] dataWithVersion = new byte[21];
                dataWithVersion[0] = PRE_P2PKH; // 隔离见证v0版本
                System.arraycopy(pubKeyHash, 0, dataWithVersion, 1, 20);
                // 3. Bech32编码（主网HRP为"bc"，测试网为"tb"）
                return Bech32.encode("bc", dataWithVersion);
            } catch (Exception e) {
                throw new RuntimeException("生成P2WPKH地址失败", e);
            }
        }

        public static boolean isValidP2WPKHAddress(String address) {
            try {
                Object[] decoded = Bech32.decode(address);
                if (decoded == null) return false;
                String hrp = (String) decoded[0];
                byte[] data = (byte[]) decoded[1];
                // 校验：主网HRP为"bc" + 总长度21字节（1字节版本+20字节哈希） + 版本0x00
                return "bc".equals(hrp)
                        && data.length == 21
                        && data[0] == PRE_P2PKH;
            } catch (Exception e) {
                return false;
            }
        }
        //根据公钥生成P2WPKH类型公钥哈希
        public static byte[] createP2WPKHByPK(byte[] publicKey) {
            try {
                // P2WPKH使用公钥的HASH160（20字节）
                return createP2PKHByPK(publicKey);
            } catch (Exception e) {
                throw new RuntimeException("生成P2WPKH哈希失败", e);
            }
        }
        //地址转公钥哈希
        public static byte[] addressToP2WPKH(String address) {
            try {
                Object[] decoded = Bech32.decode(address);
                if (decoded == null) return null;
                String hrp = (String) decoded[0];
                byte[] data = (byte[]) decoded[1];
                if (!"bc".equals(hrp) || data.length != 21) {
                    return null;
                }
                return Arrays.copyOfRange(data, 1, 21);
            } catch (Exception e) {
                return null;
            }
        }




        //根据赎回脚本生成P2WSH类型地址  赎回脚本（Redeem Script） → SHA-256哈希（32字节） → Bech32编码（带版本字节0x00和HRP前缀“bc”） → P2WSH地址
        public static String createP2WSHAddressByPK(byte[] redeemScript) {
            try {
                // 1. 生成32字节脚本哈希（SHA256(脚本)）
                byte[] scriptHash = applySHA256(redeemScript);

                // 2. 构建数据：版本字节(0x00) + 32字节哈希（共33字节）
                byte[] dataWithVersion = new byte[33];
                dataWithVersion[0] = PRE_P2PKH; // 隔离见证v0版本
                System.arraycopy(scriptHash, 0, dataWithVersion, 1, 32);
                // 3. Bech32编码
                return Bech32.encode("bc", dataWithVersion);
            } catch (Exception e) {
                throw new RuntimeException("生成P2WSH地址失败", e);
            }
        }
        public static boolean isValidP2WSHAddress(String address) {
            try {
                Object[] decoded = Bech32.decode(address);
                if (decoded == null) return false;

                String hrp = (String) decoded[0];
                byte[] data = (byte[]) decoded[1];

                // 校验：主网HRP为"bc" + 总长度33字节（1字节版本+32字节哈希） + 版本0x00
                return "bc".equals(hrp)
                        && data.length == 33
                        && data[0] == PRE_P2PKH;
            } catch (Exception e) {
                return false;
            }
        }

        //根据赎回脚本生成P2WSH类型哈希
        public static byte[] createP2WSHByPK(byte[] redeemScript) {
            try {
                return applySHA256(redeemScript);
            } catch (Exception e) {
                throw new RuntimeException("生成P2WSH哈希失败", e);
            }
        }
        public static byte[] addressToP2WSH(String address) {
            try {
                Object[] decoded = Bech32.decode(address);
                if (decoded == null) return null;
                String hrp = (String) decoded[0];
                byte[] data = (byte[]) decoded[1];
                if (!"bc".equals(hrp) || data.length != 33) {
                    return null;
                }
                return Arrays.copyOfRange(data, 1, 33);
            } catch (Exception e) {
                return null;
            }
        }



        // 导出公钥为 PEM 格式字符串
        public static String exportPublicKey(PublicKey publicKey) {
            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(publicKey.getEncoded());
            return Base64.getEncoder().encodeToString(keySpec.getEncoded());
        }
        //导出私钥为 PEM
        public static String exportPrivateKey(PrivateKey privateKey) {
            PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(privateKey.getEncoded());
            return Base64.getEncoder().encodeToString(keySpec.getEncoded());
        }
        // 保存公钥到文件（以 X.509 格式）
        public static void savePublicKey(PublicKey publicKey, String filename) throws IOException {
            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(publicKey.getEncoded());
            try (FileOutputStream fos = new FileOutputStream(filename)) {
                fos.write(keySpec.getEncoded());
            }
            log.info("保存公钥成功"+ filename);
        }
        // 保存私钥到文件（以 PKCS#8 格式）
        public static void savePrivateKey(PrivateKey privateKey, String filename) throws IOException {
            PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(privateKey.getEncoded());
            try (FileOutputStream fos = new FileOutputStream(filename)) {
                fos.write(keySpec.getEncoded());
            }
            log.info("保存私钥成功"+ filename);
        }

        // 从文件加载公钥
        public static PublicKey loadPublicKey(String filename) throws Exception {
            // 读取公钥文件内容
            byte[] keyBytes;
            try (FileInputStream fis = new FileInputStream(filename);
                 ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = fis.read(buffer)) != -1) {
                    bos.write(buffer, 0, bytesRead);
                }
                keyBytes = bos.toByteArray();
            }

            // 恢复公钥
            KeyFactory keyFactory = KeyFactory.getInstance("EC");
            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(keyBytes);
            return keyFactory.generatePublic(keySpec);
        }
        // 从文件加载私钥
        public static PrivateKey loadPrivateKey(String filename) throws Exception {
            // 读取私钥文件内容
            byte[] keyBytes;
            try (FileInputStream fis = new FileInputStream(filename);
                 ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = fis.read(buffer)) != -1) {
                    bos.write(buffer, 0, bytesRead);
                }
                keyBytes = bos.toByteArray();
            }

            // 恢复私钥
            KeyFactory keyFactory = KeyFactory.getInstance("EC");
            PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(keyBytes);
            return keyFactory.generatePrivate(keySpec);
        }


        /**
         * 根据地址字符串判断其类型（P2PKH/P2SH/P2WPKH/P2WSH）
         * @param address 待判断的地址字符串
         * @return 对应的地址类型枚举，无效地址返回null
         */
        public static AddressType getAddressType(String address) {
            if (address == null || address.trim().isEmpty()) {
                return null;
            }
            // 优先判断隔离见证地址（Bech32编码）
            if (ECDSASigner.isValidP2WPKHAddress(address)) {
                return AddressType.P2WPKH;
            }else if (ECDSASigner.isValidP2WSHAddress(address)) {
                return AddressType.P2WSH;
            }else if (ECDSASigner.isValidP2PKHAddress(address)) {
                // 再判断Base58编码的地址
                return AddressType.P2PKH;
            }else if (ECDSASigner.isValidP2SHAddress(address)) {
                return AddressType.P2SH;
            }else {
                // 所有类型均不匹配
                return null;
            }
        }

        /**
         * 获取地址哈希
         */
        public static byte[] getAddressHash(String address) {
            AddressType addressType = getAddressType(address);
            switch (addressType) {
                case P2PKH:
                    return addressToP2PKH(address);
                case P2SH:
                    return addressToP2SH(address);
                case P2WPKH:
                    return addressToP2WPKH(address);
                case P2WSH:
                    return addressToP2WSH(address);
            }
            return null;
        }

        /**
         * 根据地址获取对应的锁定脚本（ScriptPubKey）
         * 锁定脚本定义了花费该地址资金的条件
         * @param address 目标地址
         * @return 锁定脚本对象（ScriptPubKey）
         */
        public static ScriptPubKey getLockingScriptByAddress(String address) {
            if (address == null || address.trim().isEmpty()) {
                throw new IllegalArgumentException("地址不能为空");
            }

            // 1. 确定地址类型
            AddressType addressType = getAddressType(address);
            if (addressType == null) {
                throw new IllegalArgumentException("无效的地址格式: " + address);
            }

            // 2. 获取地址对应的哈希（公钥哈希或脚本哈希）
            byte[] hash = getAddressHash(address);
            if (hash == null) {
                throw new IllegalArgumentException("无法解析地址的哈希值: " + address);
            }
            ScriptPubKey scriptPubKey = new ScriptPubKey();
            switch (addressType) {
                case P2PKH:
                    byte[] bytes = addressToP2PKH(address);
                    scriptPubKey = ScriptPubKey.createP2PKHByPublicKeyHash(bytes);
                    break;
                case P2SH:
                    byte[] bytes1 = addressToP2SH(address);
                    scriptPubKey = ScriptPubKey.createP2SH(bytes1);
                    break;

                case P2WPKH:
                    byte[] bytes2 = addressToP2WPKH(address);
                    scriptPubKey = ScriptPubKey.createP2WPKH(bytes2);
                    break;
                case P2WSH:
                    byte[] bytes3 = addressToP2WSH(address);
                    scriptPubKey = ScriptPubKey.createP2WSH(bytes3);
                    break;

                default:
                    throw new UnsupportedOperationException("不支持的地址类型: " + addressType);
            }
            return scriptPubKey;
        }

        /**
         * 验证哈希长度是否符合地址类型要求
         * @param hash 待验证的哈希
         * @param expectedLength 预期长度（字节）
         * @param addressType 地址类型（用于错误提示）
         */
        private static void validateHashLength(byte[] hash, int expectedLength, String addressType) {
            if (hash.length != expectedLength) {
                throw new IllegalArgumentException(addressType + "地址的哈希长度必须为" + expectedLength + "字节，实际为" + hash.length + "字节");
            }
        }


    }

    /**
     * 生产级SHA-256实现（使用系统默认提供者，确保算法可用性）
     */
    public static byte[] applySHA256(byte[] data) {
        try {
            // 优先使用BouncyCastle确保一致性
            return MessageDigest.getInstance("SHA-256", BouncyCastleProvider.PROVIDER_NAME)
                    .digest(data);
        } catch (Exception e) {
            // 降级使用系统默认提供者
            try {
                return MessageDigest.getInstance("SHA-256").digest(data);
            } catch (Exception ex) {
                throw new RuntimeException("SHA-256算法不可用", ex);
            }
        }
    }

    /**
     * 生产级RIPEMD-160实现（依赖BouncyCastle，标准库不默认支持）
     */
    public static byte[] applyRIPEMD160(byte[] data) {
        try {
            return MessageDigest.getInstance("RIPEMD160", BouncyCastleProvider.PROVIDER_NAME)
                    .digest(data);
        } catch (Exception e) {
            throw new RuntimeException("RIPEMD-160算法不可用（需BouncyCastle支持）", e);
        }
    }

    /**
     * 字节数组转十六进制字符串
     */
    public static String bytesToHex(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }

    /**
     * 十六进制字符串转字节数组
     */
    public static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i+1), 16));
        }
        return data;
    }




    /**
     * 编码一个整数为VarInt格式（比特币协议中的可变长度整数）
     * @param size 要编码的整数
     * @return 编码后的字节数组
     */
    public static byte[] encodeVarInt(int size) {
        if (size < 0xfd) {
            // 0-252: 1字节
            return new byte[]{(byte) size};
        } else if (size <= 0xffff) {
            // 253-65535: 0xfd + 2字节
            byte[] result = new byte[3];
            result[0] = (byte) 0xfd;
            result[1] = (byte) (size & 0xff);
            result[2] = (byte) ((size >> 8) & 0xff);
            return result;
        } else if (size <= 0xffffffffL) {
            // 65536-4294967295: 0xfe + 4字节
            byte[] result = new byte[5];
            result[0] = (byte) 0xfe;
            result[1] = (byte) (size & 0xff);
            result[2] = (byte) ((size >> 8) & 0xff);
            result[3] = (byte) ((size >> 16) & 0xff);
            result[4] = (byte) ((size >> 24) & 0xff);
            return result;
        } else {
            // 超过int范围的情况，使用long处理（但encodeVarInt参数是int，理论上不会执行到这里）
            throw new IllegalArgumentException("Size too large for encodeVarInt: " + size);
        }
    }

    /**
     * 从字节数组中读取VarInt格式的整数
     * @param bytes 字节数组
     * @param offset 起始偏移量
     * @return 读取的整数值
     */
    public static long readVarInt(byte[] bytes, int offset) {
        int firstByte = bytes[offset] & 0xff;
        if (firstByte < 0xfd) {
            // 1字节直接返回
            return firstByte;
        } else if (firstByte == 0xfd) {
            // 2字节
            return (bytes[offset + 1] & 0xff) |
                    ((bytes[offset + 2] & 0xff) << 8);
        } else if (firstByte == 0xfe) {
            // 4字节
            return (bytes[offset + 1] & 0xff) |
                    ((bytes[offset + 2] & 0xff) << 8) |
                    ((bytes[offset + 3] & 0xff) << 16) |
                    ((bytes[offset + 4] & 0xff) << 24);
        } else {
            // 8字节（在readVarInt中不处理，因为返回类型是long，可能溢出）
            throw new UnsupportedOperationException("8-byte VarInt not supported in readVarInt");
        }
    }

    /**
     * 计算一个VarInt编码所需的字节数
     * @param count 要编码的数值
     * @return 编码所需的字节数
     */
    public static int varIntSize(long count) {
        if (count < 0xfd) {
            return 1;
        } else if (count <= 0xffff) {
            return 3; // 0xfd + 2字节
        } else if (count <= 0xffffffffL) {
            return 5; // 0xfe + 4字节
        } else {
            return 9; // 0xff + 8字节
        }
    }






    public static byte[] sha3(byte[] input) {
        Keccak.DigestKeccak kecc = new Keccak.Digest256();
        kecc.update(input, 0, input.length);
        return kecc.digest();
    }










    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/CBC/PKCS5Padding";
    private static final String KEY_DERIVATION_ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int ITERATIONS = 65536;
    private static final int KEY_LENGTH = 256; // in bits
    private static final int SALT_LENGTH = 16; // in bytes
    private static final int IV_LENGTH = 16; // in bytes for AES

    public static String encryptWithPassword(byte[] encoded, String password) {
        try {
            // 生成盐值
            SecureRandom random = SecureRandom.getInstanceStrong();
            byte[] salt = new byte[SALT_LENGTH];
            random.nextBytes(salt);

            // 从密码派生密钥
            SecretKeyFactory factory = SecretKeyFactory.getInstance(KEY_DERIVATION_ALGORITHM);
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_LENGTH);
            SecretKey tmp = factory.generateSecret(spec);
            SecretKeySpec secretKey = new SecretKeySpec(tmp.getEncoded(), ALGORITHM);

            // 生成随机IV
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            IvParameterSpec ivSpec = new IvParameterSpec(iv);

            // 初始化加密器
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec);

            // 执行加密
            byte[] encryptedBytes = cipher.doFinal(encoded);

            // 组合盐值、IV和密文
            byte[] combined = new byte[salt.length + iv.length + encryptedBytes.length];
            System.arraycopy(salt, 0, combined, 0, salt.length);
            System.arraycopy(iv, 0, combined, salt.length, iv.length);
            System.arraycopy(encryptedBytes, 0, combined, salt.length + iv.length, encryptedBytes.length);

            // Base64编码结果
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new RuntimeException("加密过程失败", e);
        }
    }

    // 对应的解密方法
    public static byte[] decryptWithPassword(String encryptedData, String password) {
        try {
            // 解码Base64数据
            byte[] combined = Base64.getDecoder().decode(encryptedData);

            // 提取盐值、IV和密文
            byte[] salt = new byte[SALT_LENGTH];
            byte[] iv = new byte[IV_LENGTH];
            byte[] encryptedBytes = new byte[combined.length - SALT_LENGTH - IV_LENGTH];

            System.arraycopy(combined, 0, salt, 0, SALT_LENGTH);
            System.arraycopy(combined, SALT_LENGTH, iv, 0, IV_LENGTH);
            System.arraycopy(combined, SALT_LENGTH + IV_LENGTH, encryptedBytes, 0, encryptedBytes.length);

            // 从密码派生密钥
            SecretKeyFactory factory = SecretKeyFactory.getInstance(KEY_DERIVATION_ALGORITHM);
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_LENGTH);
            SecretKey tmp = factory.generateSecret(spec);
            SecretKeySpec secretKey = new SecretKeySpec(tmp.getEncoded(), ALGORITHM);

            // 初始化解密器
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            IvParameterSpec ivSpec = new IvParameterSpec(iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec);

            // 执行解密
            return cipher.doFinal(encryptedBytes);
        } catch (Exception e) {
            throw new RuntimeException("解密过程失败", e);
        }
    }

    public static String hashPassword(String password) {
        return Base64.getEncoder().encodeToString(applySHA256(password.getBytes()));
    }




    /**
     * Base58编码/解码工具类，用于比特币等加密货币地址处理
     */
    public static class Base58 {
        // Base58字符表，去除易混淆字符
        public static final char[] ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz".toCharArray();
        private static final int[] INDEXES = new int[128];
        private static final BigInteger BASE = BigInteger.valueOf(58);

        static {
            // 初始化字符索引映射
            Arrays.fill(INDEXES, -1);
            for (int i = 0; i < ALPHABET.length; i++) {
                INDEXES[ALPHABET[i]] = i;
            }
        }

        /**
         * 字节数组编码为Base58字符串
         */
        public static String encode(byte[] input) {
            if (input.length == 0) {
                return "";
            }
            // 处理前导零
            int zeroCount = 0;
            while (zeroCount < input.length && input[zeroCount] == 0) {
                zeroCount++;
            }

            // 将字节数组转换为大整数
            BigInteger temp = new BigInteger(1, input);

            // 编码过程
            StringBuilder encoded = new StringBuilder();
            while (temp.compareTo(BigInteger.ZERO) > 0) {
                BigInteger[] divmod = temp.divideAndRemainder(BASE);
                encoded.append(ALPHABET[divmod[1].intValue()]);
                temp = divmod[0];
            }

            // 添加前导零对应的'1'字符
            while (zeroCount-- > 0) {
                encoded.append(ALPHABET[0]);
            }

            // 反转字符串得到最终结果
            return encoded.reverse().toString();
        }

        /**
         * Base58字符串解码为字节数组
         */
        public static byte[] decode(String input) {
            if (input.isEmpty()) {
                return new byte[0];
            }

            // 处理前导'1'字符
            int zeroCount = 0;
            while (zeroCount < input.length() && input.charAt(zeroCount) == ALPHABET[0]) {
                zeroCount++;
            }

            // 解码过程
            BigInteger decoded = BigInteger.ZERO;
            for (int i = zeroCount; i < input.length(); i++) {
                int digit = INDEXES[input.charAt(i)];
                if (digit < 0) {
                    throw new IllegalArgumentException("非法Base58字符: " + input.charAt(i));
                }
                decoded = decoded.multiply(BASE).add(BigInteger.valueOf(digit));
            }

            // 转换为字节数组
            byte[] bytes = decoded.toByteArray();

            // 处理符号位（toByteArray可能会添加额外的符号位字节）
            boolean stripSignByte = bytes.length > 1 && bytes[0] == 0 && bytes[1] < 0;
            int leadingZeros = zeroCount;

            // 分配结果数组并填充前导零
            byte[] result = new byte[leadingZeros + (bytes.length - (stripSignByte ? 1 : 0))];
            System.arraycopy(bytes, stripSignByte ? 1 : 0, result, leadingZeros, result.length - leadingZeros);

            return result;
        }

        /**
         * Base58Check编码（添加版本字节和校验和）
         */
        public static String encodeWithChecksum(byte[] input) {
            // 添加校验和
            byte[] checksum = calculateChecksum(input);
            byte[] output = new byte[input.length + checksum.length];
            System.arraycopy(input, 0, output, 0, input.length);
            System.arraycopy(checksum, 0, output, input.length, checksum.length);

            // 编码
            return encode(output);
        }

        /**
         * Base58Check解码（验证并移除校验和）
         */
        public static byte[] decodeWithChecksum(String input) throws IllegalArgumentException {
            byte[] decoded = decode(input);
            if (decoded.length < 4) {
                throw new IllegalArgumentException("非法Base58Check字符串: 长度过短");
            }

            byte[] data = Arrays.copyOfRange(decoded, 0, decoded.length - 4);
            byte[] actualChecksum = Arrays.copyOfRange(decoded, decoded.length - 4, decoded.length);
            byte[] expectedChecksum = calculateChecksum(data);

            if (!Arrays.equals(actualChecksum, expectedChecksum)) {
                throw new IllegalArgumentException("非法Base58Check字符串: 校验和不匹配");
            }

            return data;
        }

        /**
         * 计算校验和（双重SHA-256的前4字节）
         */
        private static byte[] calculateChecksum(byte[] data) {
            byte[] sha256 = applySHA256(data);
            byte[] sha256Twice = applySHA256(sha256);
            return Arrays.copyOfRange(sha256Twice, 0, 4);
        }


        /**
         * 字节数组转十六进制字符串
         */
        public static String bytesToHex(byte[] bytes) {
            StringBuilder result = new StringBuilder();
            for (byte b : bytes) {
                result.append(String.format("%02x", b));
            }
            return result.toString();
        }

        /**
         * 十六进制字符串转字节数组
         */
        public static byte[] hexToBytes(String hex) {
            int len = hex.length();
            byte[] data = new byte[len / 2];
            for (int i = 0; i < len; i += 2) {
                data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                        + Character.digit(hex.charAt(i+1), 16));
            }
            return data;
        }
    }

    public static class Bech32 {
        private static final String CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";
        private static final int[] VALUES = new int[128];

        static {
            Arrays.fill(VALUES, -1);
            for (int i = 0; i < CHARSET.length(); i++) {
                VALUES[CHARSET.charAt(i)] = i;
            }
        }

        /**
         * 编码：HRP + 数据（含版本字节）→ Bech32地址
         */
        public static String encode(String hrp, byte[] data) {
            // 将8位字节转换为5位整数数组
            int[] fiveBitData = convertBits(data, 8, 5, true);
            if (fiveBitData == null) return null;

            // 计算校验和
            int[] checksum = createChecksum(hrp, fiveBitData);
            int[] combined = new int[fiveBitData.length + checksum.length];
            System.arraycopy(fiveBitData, 0, combined, 0, fiveBitData.length);
            System.arraycopy(checksum, 0, combined, fiveBitData.length, checksum.length);

            // 拼接HRP和编码数据
            StringBuilder sb = new StringBuilder(hrp + "1");
            for (int b : combined) {
                sb.append(CHARSET.charAt(b));
            }
            return sb.toString();
        }

        /**
         * 解码：Bech32地址 → [HRP, 数据(含版本字节)]
         */
        public static Object[] decode(String str) {
            // 基本格式校验
            if (str.length() < 8 || str.length() > 90) return null;
            if (str.chars().anyMatch(c -> c < 33 || c > 126)) return null;

            // 检查是否有混合大小写（Bech32要求HRP必须全小写或全大写）
            boolean hasLower = false;
            boolean hasUpper = false;
            for (char c : str.toCharArray()) {
                if (Character.isLowerCase(c)) hasLower = true;
                if (Character.isUpperCase(c)) hasUpper = true;
            }
            if (hasLower && hasUpper) return null;

            // 统一转换为小写进行处理
            String lowerStr = str.toLowerCase();

            // 分离HRP和数据部分
            int splitIdx = lowerStr.lastIndexOf('1');
            if (splitIdx == -1 || splitIdx == 0 || splitIdx + 1 >= lowerStr.length()) return null;
            String hrp = lowerStr.substring(0, splitIdx);
            String dataPart = lowerStr.substring(splitIdx + 1);

            // 转换数据部分为5位整数数组
            int[] data = new int[dataPart.length()];
            for (int i = 0; i < dataPart.length(); i++) {
                char c = dataPart.charAt(i);
                if (c >= VALUES.length || VALUES[c] == -1) return null;
                data[i] = VALUES[c];
            }

            // 校验和验证（使用原始字符串进行验证）
            if (!verifyChecksum(hrp, data)) return null;

            // 提取有效数据（去除校验和）并转换为字节
            int[] payload = Arrays.copyOfRange(data, 0, data.length - 6);

            // 确保版本字节有效（隔离见证版本0-16）
            if (payload.length < 1 || payload[0] > 16) return null;

            // 位转换，不允许填充（严格转换）
            byte[] decoded = convertBits(payload, 5, 8, false);

            if (decoded == null) return null;

            // 检查隔离见证地址的长度
            int witnessVersion = decoded[0] & 0xFF;
            int witnessProgramLength = decoded.length - 1;

            // 验证长度是否符合规范
            if (witnessVersion == 0) {
                if (witnessProgramLength != 20 && witnessProgramLength != 32) {
                    return null; // 版本0必须是20字节(P2WPKH)或32字节(P2WSH)
                }
            } else if (witnessVersion >= 1 && witnessVersion <= 16) {
                if (witnessProgramLength < 2 || witnessProgramLength > 40) {
                    return null; // 其他版本必须在2-40字节之间
                }
            } else {
                return null; // 无效版本
            }

            return new Object[]{hrp, decoded};
        }
        /**
         * 位转换核心逻辑（标准实现）
         * 将srcBits位的数组转换为destBits位的数组
         */
        /**
         * 将字节数组（srcBits位）转换为整数数组（destBits位）
         * 适用于：8位字节 -> 5位整数（Bech32编码）
         */
        private static int[] convertBits(byte[] data, int srcBits, int destBits, boolean pad) {
            int[] result = new int[(data.length * srcBits + destBits - 1) / destBits];
            int buffer = 0;
            int bits = 0;
            int index = 0;
            int maxVal = (1 << destBits) - 1;

            for (byte b : data) {
                int value = b & 0xFF; // 转为无符号值
                // 检查输入值是否超过srcBits能表示的范围（例如srcBits=5时，值不能超过31）
                if ((value >>> srcBits) != 0) {
                    return null;
                }
                buffer = (buffer << srcBits) | value; // 累积位到缓冲区
                bits += srcBits;

                // 当缓冲区位数足够时，提取destBits位到结果
                while (bits >= destBits) {
                    result[index++] = (buffer >>> (bits - destBits)) & maxVal;
                    bits -= destBits;
                }
            }

            // 处理剩余位（根据pad参数决定是否填充）
            if (pad) {
                if (bits > 0) {
                    // 填充0以凑齐destBits位
                    result[index++] = (buffer << (destBits - bits)) & maxVal;
                }
            } else {
                // 不允许填充时，剩余位必须为0（即填充位），否则无效
                if (bits != 0 && (buffer & ((1 << bits) - 1)) != 0) {
                    return null;
                }
            }

            return Arrays.copyOf(result, index);
        }

        /**
         * 将整数数组（srcBits位）转换为字节数组（destBits位）
         * 适用于：5位整数 -> 8位字节（Bech32解码）
         */
        private static byte[] convertBits(int[] data, int srcBits, int destBits, boolean pad) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int buffer = 0;
            int bits = 0;
            int maxVal = (1 << destBits) - 1;

            for (int value : data) {
                if (value < 0 || (value >>> srcBits) != 0) {
                    return null; // 输入值超出srcBits范围
                }
                buffer = (buffer << srcBits) | value; // 累积位到缓冲区
                bits += srcBits;

                // 当缓冲区位数足够时，提取destBits位到结果
                while (bits >= destBits) {
                    out.write((buffer >>> (bits - destBits)) & maxVal);
                    bits -= destBits;
                }
            }

            // 处理剩余位（根据pad参数决定是否填充）
            if (pad) {
                if (bits > 0) {
                    // 填充0以凑齐destBits位
                    out.write((buffer << (destBits - bits)) & maxVal);
                }
            } else {
                // 不允许填充时，剩余位必须为0（即填充位），否则无效
                if (bits != 0 && (buffer & ((1 << bits) - 1)) != 0) {
                    return null;
                }
            }

            return out.toByteArray();
        }

        /**
         * 多项式校验和计算（标准Bech32实现）
         */
        private static int polymod(int[] values) {
            int checksum = 1;
            int[] generators = {0x3B6A57B2, 0x26508E6D, 0x1EA119FA, 0x3D4233DD, 0x2A1462B3};
            for (int value : values) {
                int top = checksum >>> 25;
                checksum = (checksum & 0x1FFFFFF) << 5 ^ value;
                for (int i = 0; i < 5; i++) {
                    if ((top >>> i & 1) == 1) {
                        checksum ^= generators[i];
                    }
                }
            }
            return checksum;
        }

        /**
         * 创建校验和（标准实现）
         */
        private static int[] createChecksum(String hrp, int[] data) {
            int[] hrpExpanded = expandHrp(hrp);
            int[] values = new int[hrpExpanded.length + data.length + 6];
            System.arraycopy(hrpExpanded, 0, values, 0, hrpExpanded.length);
            System.arraycopy(data, 0, values, hrpExpanded.length, data.length);
            int checksum = polymod(values) ^ 1; // 异或1作为最终校验和

            int[] result = new int[6];
            for (int i = 0; i < 6; i++) {
                result[i] = (checksum >>> (5 * (5 - i))) & 0x1F;
            }
            return result;
        }

        /**
         * 验证校验和（标准实现）
         */
        private static boolean verifyChecksum(String hrp, int[] data) {
            int[] hrpExpanded = expandHrp(hrp);
            int[] values = new int[hrpExpanded.length + data.length];
            System.arraycopy(hrpExpanded, 0, values, 0, hrpExpanded.length);
            System.arraycopy(data, 0, values, hrpExpanded.length, data.length);
            return polymod(values) == 1;
        }

        /**
         * 扩展HRP（人类可读前缀）为5位整数数组
         */
        private static int[] expandHrp(String hrp) {
            int[] result = new int[hrp.length() * 2 + 1];
            for (int i = 0; i < hrp.length(); i++) {
                int c = hrp.charAt(i);
                result[i] = c >>> 5; // 高5位
            }
            result[hrp.length()] = 0; // 分隔符
            for (int i = 0; i < hrp.length(); i++) {
                int c = hrp.charAt(i);
                result[hrp.length() + 1 + i] = c & 0x1F; // 低5位
            }
            return result;
        }
    }

}