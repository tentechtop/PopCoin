package com.pop.popcoinsystem.data.script;

import com.pop.popcoinsystem.util.CryptoUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import java.util.stream.Collectors;

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

            case OP_0:
                // OP_0：将空字节数组压入栈（代表0或false）
                stack.add(new byte[0]);  // 空数组表示0
                return true;
                // ... 类似处理OP_1到OP_16
            case OP_1:
                stack.add(new byte[]{1});
                return true;
            case OP_2:
                stack.add(new byte[]{2});  // 将数值2压入栈（表示需要2个有效签名）
                return true;
            case OP_3:
                stack.add(new byte[]{3});
                return true;
            case OP_4:
                stack.add(new byte[]{4});
                return true;
            case OP_5:
                stack.add(new byte[]{5});
                return true;
            case OP_6:
                stack.add(new byte[]{6});
                return true;
            case OP_7:
                stack.add(new byte[]{7});
                return true;
            case OP_8:
                stack.add(new byte[]{8});
                return true;
            case OP_9:
                stack.add(new byte[]{9});
                return true;
            case OP_10:
                stack.add(new byte[]{10});
                return true;
            case OP_11:
                stack.add(new byte[]{11});
                return true;
            case OP_12:
                stack.add(new byte[]{12});
                return true;
            case OP_13:
                stack.add(new byte[]{13});
                return true;
            case OP_14:
                stack.add(new byte[]{14});
                return true;
            case OP_15:
                stack.add(new byte[]{15});
                return true;
            case OP_16:
                stack.add(new byte[]{16});
                return true;




            case OP_1NEGATE:  // 表示-1
                stack.add(new byte[]{(byte) 0x81});  // 负数表示
                return true;

            case OP_EQUAL:
                if (stack.size() < 2) return false;
                byte[] topOne = stack.remove(stack.size() - 1);
                byte[] topTwo = stack.remove(stack.size() - 1);
                log.info("OP_EQUAL:"+"比较两个栈顶元素是否相等"+"栈顶元素是: "+CryptoUtil.bytesToHex(topOne)+"栈顶元素是: "+CryptoUtil.bytesToHex(topTwo));
                boolean equalOPEQUAL = Arrays.equals(topOne, topTwo);
                stack.add(equalOPEQUAL ? new byte[]{1} : new byte[0]);  // 结果压栈：真为0x01，假为空数组
                return true;


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
                byte[] bytes = CryptoUtil.applyRIPEMD160(CryptoUtil.applySHA256(data));
                //byte[] bytes = CryptoUtil.ECDSASigner.publicKeyHash256And160Byte(data);
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
                log.info("OP_EQUALVERIFY:"+"比较结果是: "+equal);
                if (opCode == OP_EQUAL) {
                    stack.add(equal ? new byte[]{1} : new byte[0]);
                } else {
                    if (!equal) return false;
                }
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
                //用于简单的单签名验证，如普通比特币地址（P2PKH）的支出。
                //1. OP_CHECKSIGVERIFY
                //功能：验证单个数字签名是否与公钥匹配，并立即验证结果（失败时终止脚本）。
                //执行过程：
                //从栈中弹出一个公钥和一个签名。
                //验证签名是否由对应私钥生成，且针对当前交易的特定部分（哈希值）有效。
                //如果验证失败，脚本立即终止（交易无效）；如果成功，继续执行后续操作码。
                //与 OP_CHECKSIG 的区别：OP_CHECKSIG 将验证结果（真 / 假）压入栈，而 OP_CHECKSIGVERIFY 直接验证结果，不保留返回值。


            case OP_CHECKMULTISIG:
                //用于多重签名钱包，如 2-of-3 签名方案（需 2 个签名来自 3 个公钥）。
                //2. OP_CHECKMULTISIG
                //功能：验证多重签名（m-of-n），即需要至少 m 个有效签名来自 n 个公钥中的一部分。
                //执行过程：
                //从栈中弹出数字 m（所需签名数）、n 个公钥、数字 n，最后弹出 m 个签名。
                //验证至少 m 个签名与对应的公钥匹配，且针对当前交易有效。
                //如果验证成功，将 true 压入栈；否则压入 false。
                //注意：比特币脚本中存在一个历史漏洞，执行时会多弹出一个栈顶元素（通常为 0），需额外注意。
                // 遍历公钥列表



            case OP_CHECKMULTISIGVERIFY:
                //用于更严格的多重签名验证，如智能合约中必须满足条件才能支出的场景。
                //3. OP_CHECKMULTISIGVERIFY
                //功能：与 OP_CHECKMULTISIG 类似，但直接验证结果，失败时终止脚本。
                //执行过程：
                //与 OP_CHECKMULTISIG 相同，验证多重签名。
                //如果验证失败，脚本立即终止；如果成功，继续执行后续操作码。
                //与 OP_CHECKMULTISIG 的区别：类似 OP_CHECKSIGVERIFY 与 OP_CHECKSIG 的关系，OP_CHECKMULTISIGVERIFY 不保留验证结果，直接决定脚本是否继续执行。

                if (stack.isEmpty()) return false;
                log.info("Checking multi-signature...");

                //打印栈中的元素
                for (byte[] element : stack){
                    log.info("Checking multi-signature:"+"栈中的元素是: "+CryptoUtil.bytesToHex(element));
                }

                // 获取需要的公钥数量
                int pkCount = readInt(stack.remove(stack.size() - 1));
                log.info("Checking multi-signature:"+"需要的公钥数量是: "+pkCount);
                if (pkCount < 0 || pkCount > 20) return false;

                // 获取公钥列表
                List<byte[]> pubKeys = new ArrayList<>();
                for (int i = 0; i < pkCount; i++) {
                    log.info("Checking multi-signature:"+"获取公钥列表中第: "+(i+1)+" 个公钥 :"+CryptoUtil.bytesToHex(stack.get(stack.size() - 1)));
                    if (stack.isEmpty()) return false;
                    pubKeys.add(stack.remove(stack.size() - 1));
                }

                // 获取实际提供的签名数量
                if (stack.isEmpty()) return false;
                int sigCount = readInt(stack.remove(stack.size() - 1));
                log.info("Checking multi-signature:"+"实际提供的签名数量是: "+sigCount);
                if (sigCount < 0 || sigCount > pkCount) return false;

                // 获取签名列表
                List<byte[]> signatures = new ArrayList<>();
                for (int i = 0; i < sigCount; i++) {
                    log.info("Checking multi-signature:"+"获取签名列表中第: "+(i+1)+" 个签名 :"+CryptoUtil.bytesToHex(stack.get(stack.size() - 1)));
                    if (stack.isEmpty()) return false;
                    //栈中还有谁
                    signatures.add(stack.remove(stack.size() - 1));
                }

                log.info("移除元素");
                // 移除一个额外的元素（BIP62要求）
                if (!stack.isEmpty()) {
                    log.info("移除元素是:"+CryptoUtil.bytesToHex(stack.get(stack.size() - 1)));
                    stack.remove(stack.size() - 1);
                } else {
                    log.info("空的");
                    return false;
                }

                // 验证所有签名
                boolean allVerified = true;
                int sigIndex = 0;
                for (byte[] pubKeyByte : pubKeys) {
                    if (sigIndex >= signatures.size()) break;

                    byte[] signatureBytes = signatures.get(sigIndex);
                    log.info("验证签名:"+"验证签名中第: "+(sigIndex+1)+" 个签名 :"+CryptoUtil.bytesToHex(signatureBytes));

                    // 检查签名格式
                    if (!isValidSignatureEncoding(signatureBytes)) {
                        allVerified = false;
                        break;
                    }

                    // 提取签名类型
                    int hashType = signatureBytes[signatureBytes.length - 1] & 0xff;
                    log.info("验证签名:"+"签名类型是: "+hashType);

                    try {
                        // 从字节恢复公钥
                        KeyFactory keyFactory = KeyFactory.getInstance("EC");
                        EncodedKeySpec publicKeySpec = new X509EncodedKeySpec(pubKeyByte);
                        PublicKey publicKey = keyFactory.generatePublic(publicKeySpec);

                        // 生成要签名的哈希
                        //byte[] hashToSign = getHashToSign(txToSign, inputIndex, this, hashType);

                        //boolean verified =  CryptoUtil.ECDSASigner.verifySignature(publicKey, txToSign, signature);

                        // 验证签名
                        boolean verified = CryptoUtil.ECDSASigner.verifySignature(publicKey,
                                txToSign,
                                signatureBytes);
                        if (verified) {
                            log.info("签名通过");
                            sigIndex++;
                        }else {
                            log.info("签名不通过");
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

    //脚本格式输出
    public String toScripString() {
        return elements.stream().map(element -> {
            if (element.isOpCode()) {
                return OP_CODE_NAMES.get(element.getOpCode());
            } else {
                return CryptoUtil.bytesToHex(element.getData());
            }
        }).collect(Collectors.joining(" "));
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



    // 操作码名称映射
    private static final Map<Integer, String> OP_CODE_NAMES = new HashMap<>();

    static {
// 初始化常见操作码的名称
        OP_CODE_NAMES.put(OP_0, "OP_0");
        OP_CODE_NAMES.put(OP_1, "OP_1");
        OP_CODE_NAMES.put(OP_2, "OP_2");
        OP_CODE_NAMES.put(OP_3, "OP_3");
        OP_CODE_NAMES.put(OP_4, "OP_4");
        OP_CODE_NAMES.put(OP_5, "OP_5");
        OP_CODE_NAMES.put(OP_6, "OP_6");
        OP_CODE_NAMES.put(OP_7, "OP_7");
        OP_CODE_NAMES.put(OP_8, "OP_8");
        OP_CODE_NAMES.put(OP_9, "OP_9");
        OP_CODE_NAMES.put(OP_10, "OP_10");
        OP_CODE_NAMES.put(OP_11, "OP_11");
        OP_CODE_NAMES.put(OP_12, "OP_12");
        OP_CODE_NAMES.put(OP_13, "OP_13");
        OP_CODE_NAMES.put(OP_14, "OP_14");
        OP_CODE_NAMES.put(OP_15, "OP_15");
        OP_CODE_NAMES.put(OP_16, "OP_16");
        OP_CODE_NAMES.put(OP_1NEGATE, "OP_1NEGATE");
        OP_CODE_NAMES.put(OP_PUSHDATA1, "OP_PUSHDATA1");
        OP_CODE_NAMES.put(OP_PUSHDATA2, "OP_PUSHDATA2");
        OP_CODE_NAMES.put(OP_PUSHDATA4, "OP_PUSHDATA4");

        // 控制流操作码
        OP_CODE_NAMES.put(OP_IF, "OP_IF");
        OP_CODE_NAMES.put(OP_NOTIF, "OP_NOTIF");
        OP_CODE_NAMES.put(OP_ELSE, "OP_ELSE");
        OP_CODE_NAMES.put(OP_ENDIF, "OP_ENDIF");
        OP_CODE_NAMES.put(OP_RETURN, "OP_RETURN");

        // 栈操作码
        OP_CODE_NAMES.put(OP_DUP, "OP_DUP");
        OP_CODE_NAMES.put(OP_DROP, "OP_DROP");
        OP_CODE_NAMES.put(OP_SWAP, "OP_SWAP");
        OP_CODE_NAMES.put(OP_OVER, "OP_OVER");
        OP_CODE_NAMES.put(OP_ROT, "OP_ROT");
        OP_CODE_NAMES.put(OP_TUCK, "OP_TUCK");
        OP_CODE_NAMES.put(OP_PICK, "OP_PICK");
        OP_CODE_NAMES.put(OP_ROLL, "OP_ROLL");
        OP_CODE_NAMES.put(OP_TOALTSTACK, "OP_TOALTSTACK");
        OP_CODE_NAMES.put(OP_FROMALTSTACK, "OP_FROMALTSTACK");
        OP_CODE_NAMES.put(OP_2DROP, "OP_2DROP");
        OP_CODE_NAMES.put(OP_2DUP, "OP_2DUP");
        OP_CODE_NAMES.put(OP_3DUP, "OP_3DUP");
        OP_CODE_NAMES.put(OP_2OVER, "OP_2OVER");
        OP_CODE_NAMES.put(OP_2ROT, "OP_2ROT");
        OP_CODE_NAMES.put(OP_2SWAP, "OP_2SWAP");
        OP_CODE_NAMES.put(OP_IFDUP, "OP_IFDUP");
        OP_CODE_NAMES.put(OP_DEPTH, "OP_DEPTH");
        OP_CODE_NAMES.put(OP_NIP, "OP_NIP");
        OP_CODE_NAMES.put(OP_DUP2, "OP_DUP2");

        // 切片操作码
        OP_CODE_NAMES.put(OP_SIZE, "OP_SIZE");

        // 位逻辑操作码
        OP_CODE_NAMES.put(OP_INVERT, "OP_INVERT");
        OP_CODE_NAMES.put(OP_AND, "OP_AND");
        OP_CODE_NAMES.put(OP_OR, "OP_OR");
        OP_CODE_NAMES.put(OP_XOR, "OP_XOR");
        OP_CODE_NAMES.put(OP_EQUAL, "OP_EQUAL");
        OP_CODE_NAMES.put(OP_EQUALVERIFY, "OP_EQUALVERIFY");

        // 数值操作码
        OP_CODE_NAMES.put(OP_1ADD, "OP_1ADD");
        OP_CODE_NAMES.put(OP_1SUB, "OP_1SUB");
        OP_CODE_NAMES.put(OP_2MUL, "OP_2MUL");
        OP_CODE_NAMES.put(OP_2DIV, "OP_2DIV");
        OP_CODE_NAMES.put(OP_NEGATE, "OP_NEGATE");
        OP_CODE_NAMES.put(OP_ABS, "OP_ABS");
        OP_CODE_NAMES.put(OP_NOT, "OP_NOT");
        OP_CODE_NAMES.put(OP_0NOTEQUAL, "OP_0NOTEQUAL");
        OP_CODE_NAMES.put(OP_ADD, "OP_ADD");
        OP_CODE_NAMES.put(OP_SUB, "OP_SUB");
        OP_CODE_NAMES.put(OP_MUL, "OP_MUL");
        OP_CODE_NAMES.put(OP_DIV, "OP_DIV");
        OP_CODE_NAMES.put(OP_MOD, "OP_MOD");
        OP_CODE_NAMES.put(OP_LSHIFT, "OP_LSHIFT");
        OP_CODE_NAMES.put(OP_RSHIFT, "OP_RSHIFT");
        OP_CODE_NAMES.put(OP_BOOLAND, "OP_BOOLAND");
        OP_CODE_NAMES.put(OP_BOOLOR, "OP_BOOLOR");
        OP_CODE_NAMES.put(OP_NUMEQUAL, "OP_NUMEQUAL");
        OP_CODE_NAMES.put(OP_NUMEQUALVERIFY, "OP_NUMEQUALVERIFY");
        OP_CODE_NAMES.put(OP_NUMNOTEQUAL, "OP_NUMNOTEQUAL");
        OP_CODE_NAMES.put(OP_LESSTHAN, "OP_LESSTHAN");
        OP_CODE_NAMES.put(OP_GREATERTHAN, "OP_GREATERTHAN");
        OP_CODE_NAMES.put(OP_LESSTHANOREQUAL, "OP_LESSTHANOREQUAL");
        OP_CODE_NAMES.put(OP_GREATERTHANOREQUAL, "OP_GREATERTHANOREQUAL");
        OP_CODE_NAMES.put(OP_MIN, "OP_MIN");
        OP_CODE_NAMES.put(OP_MAX, "OP_MAX");
        OP_CODE_NAMES.put(OP_WITHIN, "OP_WITHIN");

        // 密码学操作码
        OP_CODE_NAMES.put(OP_RIPEMD160, "OP_RIPEMD160");
        OP_CODE_NAMES.put(OP_SHA1, "OP_SHA1");
        OP_CODE_NAMES.put(OP_SHA256, "OP_SHA256");
        OP_CODE_NAMES.put(OP_HASH160, "OP_HASH160");
        OP_CODE_NAMES.put(OP_HASH256, "OP_HASH256");
        OP_CODE_NAMES.put(OP_CODESEPARATOR, "OP_CODESEPARATOR");
        OP_CODE_NAMES.put(OP_CHECKSIG, "OP_CHECKSIG");
        OP_CODE_NAMES.put(OP_CHECKSIGVERIFY, "OP_CHECKSIGVERIFY");
        OP_CODE_NAMES.put(OP_CHECKMULTISIG, "OP_CHECKMULTISIG");
        OP_CODE_NAMES.put(OP_CHECKMULTISIGVERIFY, "OP_CHECKMULTISIGVERIFY");

        // 锁定时间操作码
        OP_CODE_NAMES.put(OP_NOP, "OP_NOP");
        OP_CODE_NAMES.put(OP_CHECKLOCKTIMEVERIFY, "OP_CHECKLOCKTIMEVERIFY");
        OP_CODE_NAMES.put(OP_CHECKSEQUENCEVERIFY, "OP_CHECKSEQUENCEVERIFY");


    }

}