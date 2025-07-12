package com.pop.popcoinsystem.util;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;


/**
 * 加密工具类 - 提供哈希、签名和密钥管理功能
 */

public class CryptoUtil {


    public static String publicKeyToAddress(PublicKey publicKey) {
        return bytesToHex(publicKey.getEncoded());
    }

    /**
     * 椭圆曲线
     */
    public static class ECDSASigner {
        // 静态初始化Bouncy Castle提供者
        static {
            Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
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
         * 对原始数据的hash进行签名
         */
        public static byte[] applyHashSignature(PrivateKey privateKey, byte[] data) {
            try {
                // 显式指定BC提供者
                Signature dsa = Signature.getInstance("SHA256withECDSA", "BC");
                dsa.initSign(privateKey);
                dsa.update(CryptoUtil.applySHA256(data)); // 传入原始数据的 hash
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
         * 将公钥转换为地址（类似比特币地址格式）
         * 流程：公钥 → SHA-256 → RIPEMD-160 → 添加版本前缀 → 双重SHA-256 → 取校验和 → 拼接 → Base58编码
         */
        public static String publicKeyToAddress(PublicKey publicKey) {
            try {
                // 1. 获取公钥字节（去除开头的0x04字节，保留64字节的坐标）
                byte[] publicKeyBytes = publicKey.getEncoded();
                // 移除X.509编码头，获取原始EC公钥字节
                // 这里简化处理，实际使用时可能需要根据具体编码调整
                byte[] rawPublicKey = Arrays.copyOfRange(publicKeyBytes, 27, publicKeyBytes.length);

                // 2. 对公钥进行SHA-256哈希
                byte[] sha256Hash = applySHA256(rawPublicKey);

                // 3. 对SHA-256结果进行RIPEMD-160哈希
                byte[] ripeMD160Hash = applyRIPEMD160(sha256Hash);

                // 4. 添加版本字节（0x00代表 主网地址）
                byte[] versionedHash = new byte[ripeMD160Hash.length + 1];
                versionedHash[0] = 0x00; // 主网地址前缀
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
                throw new RuntimeException("生成地址失败", e);
            }
        }

        // 验证示例：检查生成的地址是否符合标准
        public static boolean isValidAddress(String address) {
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

                return Arrays.equals(checksum, calculatedChecksum) && version == 0x00; // 主网版本校验
            } catch (Exception e) {
                return false; // 解码失败或校验不通过
            }
        }




        // 公钥哈希（修改后）
        public static String publicKeyHash256And160String(PublicKey publicKey) {
            try {
                // 获取X.509编码的公钥字节（包含头部信息）
                byte[] publicKeyBytes = publicKey.getEncoded();
                // 截取原始公钥字节（与生成地址时使用相同的源数据）
                byte[] rawPublicKey = Arrays.copyOfRange(publicKeyBytes, 27, publicKeyBytes.length);
                // 对原始公钥进行哈希计算
                byte[] sha256Hash = applySHA256(rawPublicKey);
                byte[] bytes = applyRIPEMD160(sha256Hash);
                return CryptoUtil.bytesToHex(bytes);
            } catch (Exception e) {
                throw new RuntimeException("公钥hash失败", e);
            }
        }

        public static byte[] publicKeyHash256And160Byte(PublicKey publicKey) {
            try {
                // 获取X.509编码的公钥字节（包含头部信息）
                byte[] publicKeyBytes = publicKey.getEncoded();
                // 截取原始公钥字节（与生成地址时使用相同的源数据）
                byte[] rawPublicKey = Arrays.copyOfRange(publicKeyBytes, 27, publicKeyBytes.length);
                // 对原始公钥进行哈希计算
                byte[] sha256Hash = applySHA256(rawPublicKey);
                return applyRIPEMD160(sha256Hash);
            } catch (Exception e) {
                throw new RuntimeException("公钥hash失败", e);
            }
        }


        public static byte[] publicKeyHash256And160Byte(byte[] publicKeyBytes) {
            try {
                // 获取X.509编码的公钥字节（包含头部信息）
                // 截取原始公钥字节（与生成地址时使用相同的源数据）
                byte[] rawPublicKey = Arrays.copyOfRange(publicKeyBytes, 27, publicKeyBytes.length);
                // 对原始公钥进行哈希计算
                byte[] sha256Hash = applySHA256(rawPublicKey);
                return applyRIPEMD160(sha256Hash);
            } catch (Exception e) {
                throw new RuntimeException("公钥hash失败", e);
            }
        }




        //将地址解码成公钥hash
        public static String addressToPublicKeyHash(String address) {
            try {
                byte[] decoded = Base58.decode(address);
                byte[] bytes = Arrays.copyOfRange(decoded, 1, 21);
                return CryptoUtil.bytesToHex(bytes);
            } catch (Exception e) {
                throw new RuntimeException("地址转换公钥hash失败", e);
            }
        }

        public static byte[] addressToPublicKeyHashByte(String address) {
            try {
                byte[] decoded = Base58.decode(address);
                return Arrays.copyOfRange(decoded, 1, 21);
            } catch (Exception e) {
                throw new RuntimeException("地址转换公钥hash失败", e);
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
            System.out.println("PublicKey saved to: " + filename);
        }
        // 保存私钥到文件（以 PKCS#8 格式）
        public static void savePrivateKey(PrivateKey privateKey, String filename) throws IOException {
            PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(privateKey.getEncoded());
            try (FileOutputStream fos = new FileOutputStream(filename)) {
                fos.write(keySpec.getEncoded());
            }
            System.out.println("Private key saved to: " + filename);
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







    }





    /**
     * 应用SHA256哈希
     */
/*    public static byte[] applySHA256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA256算法不可用", e);
        }
    }*/

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
     * 应用RIPEMD160哈希  HASH160
     */
/*    public static byte[] applyRIPEMD160(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("RIPEMD160");
            return md.digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("RIPEMD160算法不可用", e);
        }
    }*/

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



}