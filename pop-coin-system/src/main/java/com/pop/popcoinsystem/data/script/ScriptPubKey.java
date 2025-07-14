package com.pop.popcoinsystem.data.script;

import com.pop.popcoinsystem.util.CryptoUtil;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;



/**
 * 锁定脚本 - 定义花费输出所需的条件
 */
@Slf4j
@Data
public class ScriptPubKey extends Script {

    // 收款人地址
    private List<String> addresses;

    // 类型
    private String type;

    // hex表示
    private String hex;

    // 所需签名数量
    private int reqSigs;

    // 脚本类型常量
    public static final String TYPE_P2PKH = "pubkeyhash";
    public static final String TYPE_P2SH = "scripthash";
    public static final String TYPE_P2WPKH = "witness_v0_keyhash";
    public static final String TYPE_P2WSH = "witness_v0_scripthash";
    public static final String TYPE_OP_RETURN = "nulldata";
    public static final String TYPE_MULTISIG = "multisig";
    public static final String TYPE_NONSTANDARD = "nonstandard";

    // P2PKH (Pay-to-Public-Key-Hash) 类型的锁定脚本
    public ScriptPubKey(byte[] pubKeyHash) {
        super();
        this.addresses = new ArrayList<>();
        this.type = TYPE_P2PKH;
        this.reqSigs = 1;
        // OP_DUP OP_HASH160 <pubKeyHash> OP_EQUALVERIFY OP_CHECKSIG
        addOpCode(OP_DUP);
        addOpCode(OP_HASH160);
        addData(pubKeyHash);
        addOpCode(OP_EQUALVERIFY);
        addOpCode(OP_CHECKSIG);
        // 计算hex表示
        this.hex = bytesToHex(serialize());
    }

    // 从公钥生成P2PKH锁定脚本
    public static ScriptPubKey createP2PKH(byte[] publicKey) {
/*        byte[] pubKeyHash = CryptoUtil.applyRIPEMD160(CryptoUtil.applySHA256(publicKey));
        log.info("锁定脚本公钥哈希: " + CryptoUtil.bytesToHex(pubKeyHash));*/
        byte[] bytes = CryptoUtil.ECDSASigner.publicKeyHash256And160Byte(publicKey);
        return new ScriptPubKey(bytes);
    }



    /**
     * 给定公钥哈希创建P2PKH锁定脚本
     * @param publicKeyHash
     * @return
     */
    public static ScriptPubKey createP2PKHByPublicKeyHash(byte[] publicKeyHash) {
        return new ScriptPubKey(publicKeyHash);
    }


    /**
     * 给定公钥哈希Hex 创建P2PKH锁定脚本
     * @return
     */
    public static ScriptPubKey createP2PKHByPublicKeyHash(String publicKeyHash) {
        return new ScriptPubKey(CryptoUtil.hexToBytes(publicKeyHash));
    }

    /**
     * 给定地址创建锁定脚本
     * @param address
     * @return
     */
    public static ScriptPubKey createP2PKHByAddress(String address) {
        return new ScriptPubKey(CryptoUtil.hexToBytes(CryptoUtil.ECDSASigner.addressToPublicKeyHash(address)));
    }




    // 创建P2SH (Pay-to-Script-Hash) 类型的锁定脚本
    public static ScriptPubKey createP2SH(byte[] scriptHash) {
        ScriptPubKey script = new ScriptPubKey();
        script.type = TYPE_P2SH;
        script.reqSigs = 1;
        // OP_HASH160 <scriptHash> OP_EQUAL
        script.addOpCode(OP_HASH160);
        script.addData(scriptHash);
        script.addOpCode(OP_EQUAL);
        script.hex = bytesToHex(script.serialize());
        return script;
    }

    // 创建P2WPKH (Pay-to-Witness-Public-Key-Hash) 类型的锁定脚本
    public static ScriptPubKey createP2WPKH(byte[] pubKeyHash) {
        ScriptPubKey script = new ScriptPubKey();
        script.type = TYPE_P2WPKH;
        script.reqSigs = 1;

        // 0 <pubKeyHash>
        script.addOpCode(0); // OP_0
        script.addData(pubKeyHash);

        script.hex = bytesToHex(script.serialize());
        return script;
    }

    // 创建P2WSH (Pay-to-Witness-Script-Hash) 类型的锁定脚本
    public static ScriptPubKey createP2WSH(byte[] scriptHash) {
        ScriptPubKey script = new ScriptPubKey();
        script.type = TYPE_P2WSH;
        script.reqSigs = 1;

        // 0 <scriptHash>
        script.addOpCode(0); // OP_0
        script.addData(scriptHash);

        script.hex = bytesToHex(script.serialize());
        return script;
    }

    // 创建多重签名锁定脚本
    public static ScriptPubKey createMultisig(int m, List<byte[]> publicKeys) {
        if (m < 1 || m > publicKeys.size() || publicKeys.size() > 16) {
            throw new IllegalArgumentException("无效的多重签名参数");
        }
        ScriptPubKey script = new ScriptPubKey();
        script.type = TYPE_MULTISIG;
        script.reqSigs = m;
        // 添加M
        script.addOpCode(OP_1 + m - 1);
        // 添加所有公钥
        for (byte[] pubKey : publicKeys) {
            script.addData(pubKey);
        }
        // 添加N
        script.addOpCode(OP_1 + publicKeys.size() - 1);
        // 添加OP_CHECKMULTISIG
        script.addOpCode(OP_CHECKMULTISIG);
        script.hex = bytesToHex(script.serialize());
        return script;
    }

    // 创建OP_RETURN数据输出
    public static ScriptPubKey createOpReturn(byte[] data) {
        ScriptPubKey script = new ScriptPubKey();
        script.type = TYPE_OP_RETURN;
        script.reqSigs = 0;

        script.addOpCode(OP_RETURN);
        script.addData(data);

        script.hex = bytesToHex(script.serialize());
        return script;
    }

    // 私有构造函数
    private ScriptPubKey() {
        super();
        this.addresses = new ArrayList<>();
    }

    // 验证解锁脚本是否能解锁此锁定脚本
    public boolean verify(ScriptSig scriptSig, byte[] txToSign, int inputIndex, boolean isGenesisBlock) {
        // 将解锁脚本和锁定脚本连接起来执行
        List<ScriptElement> combinedElements = new ArrayList<>();
        combinedElements.addAll(scriptSig.getElements());
        combinedElements.addAll(this.getElements());
        // 创建新的脚本并执行
        Script combinedScript = new Script(combinedElements);
        // 执行组合脚本
        return combinedScript.execute(new ArrayList<>(), txToSign, inputIndex, isGenesisBlock);
    }

    // 检查是否为P2PKH（Pay-to-Public-Key-Hash）脚本
    public boolean isPayToPublicKeyHash() {
        List<ScriptElement> elements = getElements();
        if (elements.size() != 5) return false;
        return elements.get(0).getOpCode() == OP_DUP &&
                elements.get(1).getOpCode() == OP_HASH160 &&
                elements.get(3).getOpCode() == OP_EQUALVERIFY &&
                elements.get(4).getOpCode() == OP_CHECKSIG;
    }

    // 检查是否为P2SH（Pay-to-Script-Hash）脚本
    public boolean isPayToScriptHash() {
        List<ScriptElement> elements = getElements();
        if (elements.size() != 3) return false;

        return elements.get(0).getOpCode() == OP_HASH160 &&
                elements.get(2).getOpCode() == OP_EQUAL;
    }

    // 检查是否为P2WPKH（Pay-to-Witness-Public-Key-Hash）脚本
    public boolean isPayToWitnessPublicKeyHash() {
        List<ScriptElement> elements = getElements();
        if (elements.size() != 2) return false;

        return elements.get(0).getOpCode() == OP_0 &&
                elements.get(1).getData().length == 20;
    }

    // 检查是否为P2WSH（Pay-to-Witness-Script-Hash）脚本
    public boolean isPayToWitnessScriptHash() {
        List<ScriptElement> elements = getElements();
        if (elements.size() != 2) return false;

        return elements.get(0).getOpCode() == OP_0 &&
                elements.get(1).getData().length == 32;
    }

    // 检查是否为多重签名脚本
    public boolean isMultisig() {
        List<ScriptElement> elements = getElements();
        if (elements.size() < 4) return false;

        int m = elements.get(0).getOpCode() - OP_1 + 1;
        int n = elements.get(elements.size() - 2).getOpCode() - OP_1 + 1;

        if (m < 1 || n < m || n > 16) return false;

        // 检查中间是否都是公钥
        for (int i = 1; i < elements.size() - 2; i++) {
            if (elements.get(i).isOpCode()) return false;
        }

        return elements.get(elements.size() - 1).getOpCode() == OP_CHECKMULTISIG;
    }

    // Getters
    public List<String> getAddresses() {
        return addresses;
    }

    public String getType() {
        return type;
    }

    public String getHex() {
        return hex;
    }

    public int getReqSigs() {
        return reqSigs;
    }

    // 辅助方法：字节数组转十六进制字符串
    private static String bytesToHex(byte[] bytes) {
        return CryptoUtil.bytesToHex(bytes);
    }


}
