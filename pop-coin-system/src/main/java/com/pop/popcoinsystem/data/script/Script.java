package com.pop.popcoinsystem.data.script;

import com.pop.popcoinsystem.util.CryptoUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static com.pop.popcoinsystem.util.CryptoUtil.bytesToHex;

/**
 * 脚本类 - 表示比特币交易中的脚本（解锁脚本或锁定脚本）
 */


@Slf4j
public class Script {
    // 脚本元素类
    public static class ScriptElement {
        private final int opCode;
        private final byte[] data;
        private final boolean isOpCode;

        public ScriptElement(int opCode) {
            this.opCode = opCode;
            this.data = null;
            this.isOpCode = true;
        }

        public ScriptElement(byte[] data) {
            this.opCode = 0;
            this.data = data != null ? data.clone() : null;
            this.isOpCode = false;
        }

        public boolean isOpCode() {
            return isOpCode;
        }

        public int getOpCode() {
            if (!isOpCode) throw new IllegalStateException("不是操作码");
            return opCode;
        }

        public byte[] getData() {
            if (isOpCode) throw new IllegalStateException("不是数据");
            return data != null ? data.clone() : null;
        }
    }
    private List<ScriptElement> elements;

    public Script() {
        this.elements = new ArrayList<>();
    }

    public Script(List<ScriptElement> elements) {
        this.elements = new ArrayList<>(elements);
    }

    // 添加操作码
    public void addOpCode(int opCode) {
        elements.add(new ScriptElement(opCode));
    }

    // 添加数据
    public void addData(byte[] data) {
        elements.add(new ScriptElement(data));
    }

    // 执行脚本
    public boolean execute(List<byte[]> initialStack, byte[] txToSign, int inputIndex, boolean isGenesisBlock) {
        List<byte[]> stack = new ArrayList<>(initialStack);
        List<byte[]> altStack = new ArrayList<>();

        boolean inIf = false;
        boolean skip = false;
        int ifDepth = 0;
        boolean failed = false;

        // 执行每个脚本元素
        for (int i = 0; i < elements.size() && !failed; i++) {
            ScriptElement element = elements.get(i);
            if (element.isOpCode()){
                log.info("执行操作码：" + element.getOpCode());
            }else {
                log.info("数据：" + i+" 位置  "+bytesToHex(element.getData()));
            }


            if (element.isOpCode()) {
                int opCode = element.getOpCode();

                // 处理条件语句
                if (opCode == OP_IF || opCode == OP_NOTIF) {
                    ifDepth++;
                    inIf = true;
                    // 如果在条件语句中且已跳过，则继续跳过
                    if (skip) {
                        continue;
                    }
                    // 确定是否跳过条件块
                    if (opCode == OP_IF) {
                        if (stack.isEmpty() || !isTruthy(stack.get(stack.size() - 1))) {
                            skip = true;
                        }
                    } else { // OP_NOTIF
                        if (!stack.isEmpty() && isTruthy(stack.get(stack.size() - 1))) {
                            skip = true;
                        }
                    }

                    if (skip) {
                        // 寻找匹配的OP_ELSE或OP_ENDIF
                        int elseCount = 0;
                        boolean found = false;
                        for (int j = i + 1; j < elements.size(); j++) {
                            ScriptElement nextElement = elements.get(j);
                            if (!nextElement.isOpCode()) continue;

                            int nextOpCode = nextElement.getOpCode();
                            if (nextOpCode == OP_IF || nextOpCode == OP_NOTIF) {
                                elseCount++;
                            } else if (nextOpCode == OP_ELSE) {
                                if (elseCount == 0) {
                                    i = j; // 跳到OP_ELSE
                                    skip = false;
                                    found = true;
                                    break;
                                }
                            } else if (nextOpCode == OP_ENDIF) {
                                if (elseCount == 0) {
                                    i = j; // 跳到OP_ENDIF
                                    skip = false;
                                    inIf = false;
                                    ifDepth--;
                                    found = true;
                                    break;
                                } else {
                                    elseCount--;
                                }
                            }
                        }

                        if (!found) {
                            failed = true;
                        }
                    }
                }
                else if (opCode == OP_ELSE) {
                    if (!inIf || ifDepth == 0) {
                        failed = true;
                        continue;
                    }
                    if (skip) {
                        // 寻找匹配的OP_ENDIF
                        int elseCount = 0;
                        boolean found = false;
                        for (int j = i + 1; j < elements.size(); j++) {
                            ScriptElement nextElement = elements.get(j);
                            if (!nextElement.isOpCode()) continue;

                            int nextOpCode = nextElement.getOpCode();
                            if (nextOpCode == OP_ELSE) {
                                elseCount++;
                            } else if (nextOpCode == OP_ENDIF) {
                                if (elseCount == 0) {
                                    i = j; // 跳到OP_ENDIF
                                    found = true;
                                    break;
                                } else {
                                    elseCount--;
                                }
                            }
                        }

                        if (!found) {
                            failed = true;
                        }
                    } else {
                        // 寻找匹配的OP_ENDIF
                        int elseCount = 0;
                        boolean found = false;
                        for (int j = i + 1; j < elements.size(); j++) {
                            ScriptElement nextElement = elements.get(j);
                            if (!nextElement.isOpCode()) continue;

                            int nextOpCode = nextElement.getOpCode();
                            if (nextOpCode == OP_ELSE) {
                                elseCount++;
                            } else if (nextOpCode == OP_ENDIF) {
                                if (elseCount == 0) {
                                    i = j; // 跳到OP_ENDIF
                                    found = true;
                                    break;
                                } else {
                                    elseCount--;
                                }
                            }
                        }
                        if (!found) {
                            failed = true;
                        }
                    }
                }
                else if (opCode == OP_ENDIF) {
                    if (!inIf || ifDepth == 0) {
                        failed = true;
                        continue;
                    }
                    inIf = false;
                    ifDepth--;
                }
                else {
                    // 处理其他操作码
                    if (skip && inIf) {
                        continue;
                    }
                    if (!executeOpCode(opCode, stack, altStack, txToSign, inputIndex, isGenesisBlock)) {
                        failed = true;
                    }
                }
            } else {
                // 添加数据到栈
                if (skip && inIf) {
                    continue;
                }
                stack.add(element.getData());
            }
        }
        // 确保所有条件语句都已结束
        if (ifDepth != 0) {
            failed = true;
        }
        // 执行完毕后，栈顶元素为真则验证通过
        return !failed && !stack.isEmpty() && isTruthy(stack.get(stack.size() - 1));
    }


    // 执行操作码
    private boolean executeOpCode(int opCode, List<byte[]> stack, List<byte[]> altStack,
                                  byte[] txToSign, int inputIndex, boolean isGenesisBlock) {
        switch (opCode) {
            // 栈操作
            case OP_DUP:
                log.info("OP_DUP:"+"复制栈顶元素"+"栈顶元素是: "+CryptoUtil.bytesToHex(stack.get(stack.size() - 1)));
                if (stack.isEmpty()) return false;
                stack.add(stack.get(stack.size() - 1).clone());
                return true;

            case OP_DROP:
                if (stack.isEmpty()) return false;
                stack.remove(stack.size() - 1);
                return true;

            case OP_DUP2:
                log.info("OP_DUP2:"+"复制栈顶两个元素");
                if (stack.size() < 2) return false;
                byte[] top1 = stack.get(stack.size() - 1);
                byte[] top2 = stack.get(stack.size() - 2);
                stack.add(top2.clone());
                stack.add(top1.clone());
                return true;

            case OP_2DROP:
                if (stack.size() < 2) return false;
                stack.remove(stack.size() - 1);
                stack.remove(stack.size() - 1);
                return true;

            case OP_SWAP:
                if (stack.size() < 2) return false;
                Collections.swap(stack, stack.size() - 1, stack.size() - 2);
                return true;

            case OP_ROT:
                if (stack.size() < 3) return false;
                Collections.rotate(stack.subList(stack.size() - 3, stack.size()), 1);
                return true;

            case OP_TUCK:
                if (stack.size() < 2) return false;
                byte[] top = stack.remove(stack.size() - 1);
                stack.add(stack.size() - 1, top);
                return true;

            case OP_OVER:
                if (stack.size() < 2) return false;
                stack.add(stack.get(stack.size() - 2).clone());
                return true;

            case OP_PICK:
            case OP_ROLL:
                if (stack.size() < 2) return false;
                int n = readInt(stack.remove(stack.size() - 1));
                if (n < 0 || n >= stack.size()) return false;
                byte[] value = stack.get(stack.size() - 1 - n);
                if (opCode == OP_ROLL) {
                    stack.remove(stack.size() - 1 - n);
                }
                stack.add(value.clone());
                return true;

            // 数据操作
            case OP_SIZE:
                if (stack.isEmpty()) return false;
                byte[] data = stack.get(stack.size() - 1);
                stack.add(encodeNumber(data.length));
                return true;

            // 加密操作
            case OP_HASH160:
                if (stack.isEmpty()) return false;
                data = stack.remove(stack.size() - 1);
                //byte[] bytes = CryptoUtil.applyRIPEMD160(CryptoUtil.applySHA256(data));
                byte[] bytes = CryptoUtil.ECDSASigner.publicKeyHash256And160Byte(data);
                stack.add(bytes);
                log.info("OP_HASH160:"+"计算RIPEMD160(SHA256(data))"+"计算结果是: "+CryptoUtil.bytesToHex(stack.get(stack.size() - 1)));
                return true;

            case OP_SHA256:
                if (stack.isEmpty()) return false;
                data = stack.remove(stack.size() - 1);
                stack.add(CryptoUtil.applySHA256(data));
                return true;

            case OP_HASH256:
                if (stack.isEmpty()) return false;
                data = stack.remove(stack.size() - 1);
                stack.add(CryptoUtil.applySHA256(CryptoUtil.applySHA256(data)));
                return true;

            // 比较操作 第一位 和 第二位是否相等
            case OP_EQUALVERIFY:
                log.info("OP_EQUALVERIFY:"+"比较两个数据是否相等");
                if (stack.size() < 2) return false;
                byte[] a = stack.remove(stack.size() - 1);
                byte[] b = stack.remove(stack.size() - 1);
                boolean equal = Arrays.equals(a, b);
                if (opCode == OP_EQUAL) {
                    stack.add(equal ? new byte[]{1} : new byte[0]);
                } else {
                    if (!equal) return false;
                }
                log.info("OP_EQUALVERIFY:"+"比较结果是: "+equal);
                return true;
            case OP_CHECKSIG:
                log.info("验证Checking signature...");
                if (stack.size() < 2) return false;
                // 从栈中获取签名和公钥
                byte[] signature = stack.remove(stack.size() - 2);
                byte[] pubKeyBytes = stack.remove(stack.size() - 1);

                log.info("Checking signature:"+"签名是: "+CryptoUtil.bytesToHex(signature)+" 公钥是: "+CryptoUtil.bytesToHex(pubKeyBytes));
                boolean validSignatureEncoding = isValidSignatureEncoding(signature);
                log.info("Checking signature:"+"签名格式是否正确: "+validSignatureEncoding);
                // 检查签名格式
                if (!validSignatureEncoding){
                    log.info("Checking signature:"+"签名格式不正确");
                    return false;
                }
                // 提取签名类型
                int sigHashType = signature[signature.length - 1] & 0xff;
                log.info("Checking signature:"+"签名类型是: "+sigHashType);
                // 验证签名类型是否有效
                boolean signatureIsTrue =  sigHashType < 1 || sigHashType > 3;
                log.info("Checking signature:"+"签名类型是否有效: "+signatureIsTrue);
                if (!signatureIsTrue) {
                    return false;
                }
                try {
                    KeyFactory keyFactory = KeyFactory.getInstance("EC");
                    EncodedKeySpec publicKeySpec = new X509EncodedKeySpec(pubKeyBytes);
                    PublicKey publicKey = keyFactory.generatePublic(publicKeySpec);
                    boolean verified =  CryptoUtil.ECDSASigner.verifySignature(publicKey, txToSign, signature);
                    log.info("Checking signature:"+"验证签名结果是: "+verified);
                    if (opCode == OP_CHECKSIG) {
                        stack.add(verified ? new byte[]{1} : new byte[0]);
                    } else {
                        if (!verified) return false;
                    }
                    return true;
                } catch (Exception e) {
                    e.printStackTrace();
                    return false;
                }
            case OP_CHECKSIGVERIFY:

            case OP_CHECKMULTISIG:
            case OP_CHECKMULTISIGVERIFY:
                if (stack.size() < 1) return false;

                // 获取需要的公钥数量
                int m = readInt(stack.remove(stack.size() - 1));
                if (m < 0 || m > 20) return false;

                // 获取公钥列表
                List<byte[]> pubKeys = new ArrayList<>();
                for (int i = 0; i < m; i++) {
                    if (stack.isEmpty()) return false;
                    pubKeys.add(stack.remove(stack.size() - 1));
                }

                // 获取实际提供的签名数量
                if (stack.isEmpty()) return false;
                n = readInt(stack.remove(stack.size() - 1));
                if (n < 0 || n > m) return false;

                // 获取签名列表
                List<byte[]> signatures = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    if (stack.isEmpty()) return false;
                    signatures.add(stack.remove(stack.size() - 1));
                }

                // 移除一个额外的元素（BIP62要求）
                if (!stack.isEmpty()) {
                    stack.remove(stack.size() - 1);
                } else {
                    return false;
                }

                // 验证所有签名
                boolean allVerified = true;
                int sigIndex = 0;
                for (byte[] pubKeyByte : pubKeys) {
                    if (sigIndex >= signatures.size()) break;

                    byte[] signatureBytes = signatures.get(sigIndex);

                    // 检查签名格式
                    if (!isValidSignatureEncoding(signatureBytes)) {
                        allVerified = false;
                        break;
                    }

                    // 提取签名类型
                    int hashType = signatureBytes[signatureBytes.length - 1] & 0xff;

                    try {
                        // 从字节恢复公钥
                        KeyFactory keyFactory = KeyFactory.getInstance("EC");
                        EncodedKeySpec publicKeySpec = new X509EncodedKeySpec(pubKeyByte);
                        PublicKey publicKey = keyFactory.generatePublic(publicKeySpec);

                        // 生成要签名的哈希
                        byte[] hashToSign = getHashToSign(txToSign, inputIndex, this, hashType);

                        // 验证签名
                        boolean verified = CryptoUtil.ECDSASigner.verifySignature(publicKey,
                                Arrays.copyOf(signatureBytes, signatureBytes.length - 1),
                                hashToSign);
                        if (verified) {
                            sigIndex++;
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        allVerified = false;
                        break;
                    }
                }

                // 检查是否所有需要的签名都已验证
                boolean verified = allVerified && sigIndex == signatures.size();

                if (opCode == OP_CHECKMULTISIG) {
                    stack.add(verified ? new byte[]{1} : new byte[0]);
                } else { // OP_CHECKMULTISIGVERIFY
                    if (!verified) return false;
                }
                return true;

            // 数字操作
            case OP_1ADD:
            case OP_1SUB:
            case OP_NEGATE:
            case OP_ABS:
            case OP_NOT:
            case OP_0NOTEQUAL:
                if (stack.isEmpty()) return false;
                byte[] num = stack.remove(stack.size() - 1);
                long valueL = decodeNumber(num);
                switch (opCode) {
                    case OP_1ADD:
                        valueL += 1;
                        break;
                    case OP_1SUB:
                        valueL -= 1;
                        break;
                    case OP_NEGATE:
                        valueL = -valueL;
                        break;
                    case OP_ABS:
                        valueL = Math.abs(valueL);
                        break;
                    case OP_NOT:
                        valueL = (valueL == 0) ? 1 : 0;
                        break;
                    case OP_0NOTEQUAL:
                        valueL = (valueL != 0) ? 1 : 0;
                        break;
                }

                stack.add(encodeNumber(valueL));
                return true;

            case OP_ADD:
            case OP_SUB:
            case OP_MUL:
                if (stack.size() < 2) return false;
                byte[] num1 = stack.remove(stack.size() - 1);
                byte[] num2 = stack.remove(stack.size() - 1);
                long val1 = decodeNumber(num1);
                long val2 = decodeNumber(num2);
                long result;

                switch (opCode) {
                    case OP_ADD:
                        result = val1 + val2;
                        break;
                    case OP_SUB:
                        result = val2 - val1;
                        break;
                    case OP_MUL:
                        result = val1 * val2;
                        break;
                    default:
                        return false;
                }

                stack.add(encodeNumber(result));
                return true;
            case OP_LESSTHAN:
            case OP_GREATERTHAN:
            case OP_LESSTHANOREQUAL:
            case OP_GREATERTHANOREQUAL:
                if (stack.size() < 2) return false;
                num1 = stack.remove(stack.size() - 1);
                num2 = stack.remove(stack.size() - 1);
                val1 = decodeNumber(num1);
                val2 = decodeNumber(num2);
                boolean cmpResult;

                switch (opCode) {
                    case OP_LESSTHAN:
                        cmpResult = val2 < val1;
                        break;
                    case OP_GREATERTHAN:
                        cmpResult = val2 > val1;
                        break;
                    case OP_LESSTHANOREQUAL:
                        cmpResult = val2 <= val1;
                        break;
                    case OP_GREATERTHANOREQUAL:
                        cmpResult = val2 >= val1;
                        break;
                    default:
                        return false;
                }

                stack.add(cmpResult ? new byte[]{1} : new byte[0]);
                return true;

            case OP_MIN:
            case OP_MAX:
                if (stack.size() < 2) return false;
                num1 = stack.remove(stack.size() - 1);
                num2 = stack.remove(stack.size() - 1);
                val1 = decodeNumber(num1);
                val2 = decodeNumber(num2);
                long minMax;

                switch (opCode) {
                    case OP_MIN:
                        minMax = Math.min(val1, val2);
                        break;
                    case OP_MAX:
                        minMax = Math.max(val1, val2);
                        break;
                    default:
                        return false;
                }

                stack.add(encodeNumber(minMax));
                return true;

            // 辅助栈操作
            case OP_TOALTSTACK:
                if (stack.isEmpty()) return false;
                altStack.add(stack.remove(stack.size() - 1));
                return true;

            case OP_FROMALTSTACK:
                if (altStack.isEmpty()) return false;
                stack.add(altStack.remove(altStack.size() - 1));
                return true;

            // 其他操作
            case OP_RETURN:
                return false;

            default:
                // 未知操作码，视为失败
                System.err.println("未知操作码: " + opCode);
                return false;
        }
    }

    // 验证签名编码是否有效
    private boolean isValidSignatureEncoding(byte[] signature) {
        // 检查基本格式
        if (signature.length < 9 || signature.length > 73) {
            return false;
        }

        // 检查DER格式
        if (signature[0] != 0x30) {
            return false;
        }

        // 检查总长度
        if (signature[1] != signature.length - 2) {
            return false;
        }

        // 检查R部分
        if (signature[2] != 0x02) {
            return false;
        }

        int rLength = signature[3];
        if (rLength <= 0 || rLength > 33 || (rLength > 1 && (signature[4] & 0x80) != 0)) {
            return false;
        }

        // 检查S部分
        int sOffset = 4 + rLength;
        if (sOffset >= signature.length || signature[sOffset] != 0x02) {
            return false;
        }

        int sLength = signature[sOffset + 1];
        if (sLength <= 0 || sLength > 33 || (sLength > 1 && (signature[sOffset + 2] & 0x80) != 0)) {
            return false;
        }

        // 检查总长度是否匹配
        if (4 + rLength + 2 + sLength != signature.length) {
            return false;
        }

        return true;
    }

    // 生成要签名的哈希
    private byte[] getHashToSign(byte[] txToSign, int inputIndex, Script scriptCode, int sigHashType) {
        // 这个方法需要根据实际交易和签名类型生成要签名的哈希
        // 这是比特币脚本系统中最复杂的部分之一
        // 简化实现，实际中需要更复杂的处理
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            // 复制原始交易
            bos.write(txToSign);

            // 修改交易的输入脚本
            // 这里需要根据sigHashType修改交易的输入和输出
            // 简化实现，实际中需要更复杂的处理
            // 添加签名类型
            bos.write(sigHashType);
            // 计算两次SHA-256哈希
            byte[] hash1 = CryptoUtil.applySHA256(bos.toByteArray());
            return CryptoUtil.applySHA256(hash1);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // 检查字节数组是否表示真值
    private boolean isTruthy(byte[] data) {
        if (data == null || data.length == 0) {
            return false;
        }

        for (byte b : data) {
            if (b != 0) {
                return true;
            }
        }

        return false;
    }

    // 将数字编码为字节数组
    private byte[] encodeNumber(long num) {
        if (num == 0) {
            return new byte[0];
        }

        boolean isNegative = num < 0;
        long absValue = Math.abs(num);

        List<Byte> bytes = new ArrayList<>();
        while (absValue != 0) {
            bytes.add((byte) (absValue & 0xff));
            absValue >>= 8;
        }

        // 检查是否需要添加符号位
        if ((bytes.get(bytes.size() - 1) & 0x80) != 0) {
            bytes.add(isNegative ? (byte) 0x80 : (byte) 0x00);
        } else if (isNegative) {
            bytes.set(bytes.size() - 1, (byte) (bytes.get(bytes.size() - 1) | 0x80));
        }

        // 转换为数组
        byte[] result = new byte[bytes.size()];
        for (int i = 0; i < bytes.size(); i++) {
            result[i] = bytes.get(i);
        }

        return result;
    }

    // 将字节数组解码为数字
    private long decodeNumber(byte[] data) {
        if (data == null || data.length == 0) {
            return 0;
        }

        long result = 0;
        boolean isNegative = (data[data.length - 1] & 0x80) != 0;

        for (int i = 0; i < data.length; i++) {
            if (i == data.length - 1 && isNegative) {
                result |= (long) (data[i] & 0x7f) << (i * 8);
            } else {
                result |= (long) (data[i] & 0xff) << (i * 8);
            }
        }

        return isNegative ? -result : result;
    }

    // 将字节数组解释为整数
    private int readInt(byte[] data) {
        return (int) decodeNumber(data);
    }



    // 常见操作码定义
    public static final int OP_0 = 0x00;
    public static final int OP_FALSE = OP_0;
    public static final int OP_PUSHDATA1 = 0x4c;
    public static final int OP_PUSHDATA2 = 0x4d;
    public static final int OP_PUSHDATA4 = 0x4e;
    public static final int OP_1NEGATE = 0x4f;
    public static final int OP_1 = 0x51;
    public static final int OP_TRUE = OP_1;
    public static final int OP_2 = 0x52;
    public static final int OP_3 = 0x53;
    public static final int OP_4 = 0x54;
    public static final int OP_5 = 0x55;
    public static final int OP_6 = 0x56;
    public static final int OP_7 = 0x57;
    public static final int OP_8 = 0x58;
    public static final int OP_9 = 0x59;
    public static final int OP_10 = 0x5a;
    public static final int OP_11 = 0x5b;
    public static final int OP_12 = 0x5c;
    public static final int OP_13 = 0x5d;
    public static final int OP_14 = 0x5e;
    public static final int OP_15 = 0x5f;
    public static final int OP_16 = 0x60;

    // 控制流操作码
    public static final int OP_IF = 0x63;// 条件判断
    public static final int OP_NOTIF = 0x64;// 逻辑非
    public static final int OP_ELSE = 0x67;
    public static final int OP_ENDIF = 0x68;
    public static final int OP_RETURN = 0x6a;

    // 栈操作码
    public static final int OP_TOALTSTACK = 0x6b;
    public static final int OP_FROMALTSTACK = 0x6c;
    public static final int OP_2DROP = 0x6d;
    public static final int OP_2DUP = 0x6e;
    public static final int OP_3DUP = 0x6f;
    public static final int OP_2OVER = 0x70;
    public static final int OP_2ROT = 0x71;
    public static final int OP_2SWAP = 0x72;
    public static final int OP_IFDUP = 0x73;
    public static final int OP_DEPTH = 0x74;
    public static final int OP_DROP = 0x75;

    public static final int OP_DUP2 = 0x7e;
    public static final int OP_DUP = 0x76;
    public static final int OP_NIP = 0x77;
    public static final int OP_OVER = 0x78;
    public static final int OP_PICK = 0x79;
    public static final int OP_ROLL = 0x7a;
    public static final int OP_ROT = 0x7b;
    public static final int OP_SWAP = 0x7c;
    public static final int OP_TUCK = 0x7d;

    // 切片操作码
    public static final int OP_SIZE = 0x82;

    // 位逻辑操作码
    public static final int OP_INVERT = 0x83;
    public static final int OP_AND = 0x84;
    public static final int OP_OR = 0x85;
    public static final int OP_XOR = 0x86;
    public static final int OP_EQUAL = 0x87;
    public static final int OP_EQUALVERIFY = 0x88;

    // 数值操作码
    public static final int OP_1ADD = 0x8b;
    public static final int OP_1SUB = 0x8c;
    public static final int OP_2MUL = 0x8d;
    public static final int OP_2DIV = 0x8e;
    public static final int OP_NEGATE = 0x8f;
    public static final int OP_ABS = 0x90;
    public static final int OP_NOT = 0x91;
    public static final int OP_0NOTEQUAL = 0x92;
    public static final int OP_ADD = 0x93;
    public static final int OP_SUB = 0x94;
    public static final int OP_MUL = 0x95;
    public static final int OP_DIV = 0x96;
    public static final int OP_MOD = 0x97;
    public static final int OP_LSHIFT = 0x98;
    public static final int OP_RSHIFT = 0x99;
    public static final int OP_BOOLAND = 0x9a;
    public static final int OP_BOOLOR = 0x9b;
    public static final int OP_NUMEQUAL = 0x9c;
    public static final int OP_NUMEQUALVERIFY = 0x9d;
    public static final int OP_NUMNOTEQUAL = 0x9e;
    public static final int OP_LESSTHAN = 0x9f;
    public static final int OP_GREATERTHAN = 0xa0;
    public static final int OP_LESSTHANOREQUAL = 0xa1;
    public static final int OP_GREATERTHANOREQUAL = 0xa2;
    public static final int OP_MIN = 0xa3;
    public static final int OP_MAX = 0xa4;
    public static final int OP_WITHIN = 0xa5;

    // 密码学操作码
    public static final int OP_RIPEMD160 = 0xa6;
    public static final int OP_SHA1 = 0xa7;
    public static final int OP_SHA256 = 0xa8;
    public static final int OP_HASH160 = 0xa9;
    public static final int OP_HASH256 = 0xaa;
    public static final int OP_CODESEPARATOR = 0xab;
    public static final int OP_CHECKSIG = 0xac;
    public static final int OP_CHECKSIGVERIFY = 0xad;
    public static final int OP_CHECKMULTISIG = 0xae;
    public static final int OP_CHECKMULTISIGVERIFY = 0xaf;

    // 锁定时间操作码
    public static final int OP_NOP = 0x61;
    public static final int OP_CHECKLOCKTIMEVERIFY = 0xb1;
    public static final int OP_CHECKSEQUENCEVERIFY = 0xb2;

    // Getter
    public List<ScriptElement> getElements() {
        return new ArrayList<>(elements);
    }

    // 序列化脚本
    public byte[] serialize() {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();

            for (ScriptElement element : elements) {
                if (element.isOpCode()) {
                    bos.write(element.getOpCode());
                } else {
                    byte[] data = element.getData();
                    int length = data.length;

                    if (length < OP_PUSHDATA1) {
                        bos.write(length);
                    } else if (length <= 0xff) {
                        bos.write(OP_PUSHDATA1);
                        bos.write(length);
                    } else if (length <= 0xffff) {
                        bos.write(OP_PUSHDATA2);
                        bos.write(length & 0xff);
                        bos.write((length >> 8) & 0xff);
                    } else {
                        bos.write(OP_PUSHDATA4);
                        bos.write(length & 0xff);
                        bos.write((length >> 8) & 0xff);
                        bos.write((length >> 16) & 0xff);
                        bos.write((length >> 24) & 0xff);
                    }

                    bos.write(data);
                }
            }

            return bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // 从字节数组解析脚本
    public static Script parse(byte[] scriptBytes) {
        Script script = new Script();
        int i = 0;

        while (i < scriptBytes.length) {
            int opcode = scriptBytes[i] & 0xff;
            i++;

            if (opcode >= 1 && opcode <= 75) {
                // 直接压入数据
                int dataLength = opcode;
                if (i + dataLength > scriptBytes.length) {
                    throw new IllegalArgumentException("脚本格式错误");
                }

                byte[] data = Arrays.copyOfRange(scriptBytes, i, i + dataLength);
                script.addData(data);
                i += dataLength;
            } else if (opcode == OP_PUSHDATA1) {
                if (i >= scriptBytes.length) {
                    throw new IllegalArgumentException("脚本格式错误");
                }

                int dataLength = scriptBytes[i] & 0xff;
                i++;

                if (i + dataLength > scriptBytes.length) {
                    throw new IllegalArgumentException("脚本格式错误");
                }

                byte[] data = Arrays.copyOfRange(scriptBytes, i, i + dataLength);
                script.addData(data);
                i += dataLength;
            } else if (opcode == OP_PUSHDATA2) {
                if (i + 1 >= scriptBytes.length) {
                    throw new IllegalArgumentException("脚本格式错误");
                }

                int dataLength = (scriptBytes[i] & 0xff) | ((scriptBytes[i + 1] & 0xff) << 8);
                i += 2;

                if (i + dataLength > scriptBytes.length) {
                    throw new IllegalArgumentException("脚本格式错误");
                }

                byte[] data = Arrays.copyOfRange(scriptBytes, i, i + dataLength);
                script.addData(data);
                i += dataLength;
            } else if (opcode == OP_PUSHDATA4) {
                if (i + 3 >= scriptBytes.length) {
                    throw new IllegalArgumentException("脚本格式错误");
                }

                int dataLength = (scriptBytes[i] & 0xff) |
                        ((scriptBytes[i + 1] & 0xff) << 8) |
                        ((scriptBytes[i + 2] & 0xff) << 16) |
                        ((scriptBytes[i + 3] & 0xff) << 24);
                i += 4;

                if (i + dataLength > scriptBytes.length) {
                    throw new IllegalArgumentException("脚本格式错误");
                }

                byte[] data = Arrays.copyOfRange(scriptBytes, i, i + dataLength);
                script.addData(data);
                i += dataLength;
            } else {
                // 操作码
                script.addOpCode(opcode);
            }
        }

        return script;
    }
}