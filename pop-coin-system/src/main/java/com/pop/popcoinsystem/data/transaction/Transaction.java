package com.pop.popcoinsystem.data.transaction;

import com.pop.popcoinsystem.data.script.Script;
import com.pop.popcoinsystem.data.script.ScriptPubKey;
import com.pop.popcoinsystem.data.script.ScriptSig;
import com.pop.popcoinsystem.util.CryptoUtil;
import com.pop.popcoinsystem.util.SerializeUtils;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.commons.codec.digest.DigestUtils;

import java.math.BigDecimal;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.pop.popcoinsystem.data.transaction.constant.SUBSIDY;
import static com.pop.popcoinsystem.data.transaction.constant.VERSION_1;

/**
 * 交易信息
 */
//隔离见证（SegWit） 通过将签名数据（见证数据）与交易的其他部分分离，解决了上述问题。交易被分为：
//
//非见证数据：包含版本号、输入输出结构、锁定时间等固定信息。
//见证数据：包含签名、公钥等证明交易合法性的信息。
@Data
@AllArgsConstructor
@NoArgsConstructor
public class Transaction {
    /**
     * 交易的Hash
     */
    private byte[] txId;

    /**
     * 交易版本
     */
    private long version = 1;

    /**
     * 交易数据大小
     */
    private long size;

    /**
     * 权重
     */
    private long weight;

    /**
     * 锁定时间
     */
    private long lockTime  = 0;

    /**
     * 交易创建时间
     */
    private long time = System.currentTimeMillis();

    /**
     * 交易输入
     */
    private List<TXInput> inputs = new ArrayList<>();;//交易的输入。可以有多个输入，每一个输入都说明了他是引用的哪一比交易的输出。这里可以理解为 我本次交易的钱是从哪来的。

    /**
     * 交易输出
     */
    private List<TXOutput> outputs = new ArrayList<>();;//交易的输出，可以有多个，本次交易的钱我可以转给多个不同的地址，包括给自己找零的钱。可以理解为 我本次交易的钱都给了哪些人。
    /**
     * 见证数据
     */
    private List<Witness> witnesses = new ArrayList<>(); // 每个输入对应一个Witness



    //设置交易大小
    public void setSize() {
        size = calculateBaseSize();
        if (isSegWit()) {
            size += calculateWitnessSize();
        }
    }




    /**
     * 计算普通交易的实际字节大小（非隔离见证交易总大小）
     * 普通交易无见证数据，大小等于非见证数据大小
     */
    public long getRegularTransactionSize() {
        if (isSegWit()) {
            throw new IllegalStateException("该方法仅适用于非隔离见证交易");
        }
        return calculateBaseSize();
    }

    /**
     * 计算隔离见证交易的实际总字节大小
     * 隔离见证交易总大小 = 非见证数据大小 + 见证数据大小
     */
    public long getSegWitTransactionTotalSize() {
        if (!isSegWit()) {
            throw new IllegalStateException("该方法仅适用于隔离见证交易");
        }
        return calculateBaseSize() + calculateWitnessSize();
    }

    /**
     * 获取交易实际总字节大小（自动区分交易类型）
     */
    public long getActualTotalSize() {
        if (isSegWit()) {
            return getSegWitTransactionTotalSize();
        } else {
            return getRegularTransactionSize();
        }
    }



    // 判断是否为隔离见证交易
    public boolean isSegWit() {
        return !witnesses.isEmpty();
    }

    // 判断是否为CoinBase交易
    public boolean isCoinBase() {
        //仅有一个占位输入 且ID是全零 索引是-1  和一个输出
        byte[] zeroTxId = new byte[32]; // 32字节 = 256位
        Arrays.fill(zeroTxId, (byte) 0);
        return inputs.size() == 1 && Arrays.equals(inputs.get(0).getTxId(), zeroTxId) && inputs.get(0).getVout() == -1 && outputs.size() == 1;
    }


    //创建一笔CoinBase交易
    public static  Transaction createCoinBaseTransaction(String to) {
        // CoinBase交易只有一个输入，没有引用任何输出
        //coinBase的输入的交易ID
        byte[] zeroTxId = new byte[32]; // 32字节 = 256位
        Arrays.fill(zeroTxId, (byte) 0);
        TXInput input = new TXInput(zeroTxId, -1, null);
        // 创建输出，将奖励发送到指定地址
        TXOutput output = new TXOutput(SUBSIDY, new ScriptPubKey(to.getBytes()));
        Transaction coinbaseTx = new Transaction();
        coinbaseTx.setVersion(VERSION_1);
        coinbaseTx.getInputs().add(input);
        coinbaseTx.getOutputs().add(output);
        coinbaseTx.setTime(System.currentTimeMillis());
        // 计算并设置交易ID
        coinbaseTx.setTxId(Transaction.calculateTxId(coinbaseTx));
        coinbaseTx.calculateWeight();
        return coinbaseTx;
    }


    // 序列化交易用于签名
    public byte[] serializeForSigning(int inputIndex, ScriptPubKey scriptPubKey) {

        return null;
    }



    /**
     * 计算交易ID
     * 后面改成隔离见证
     */
    public static byte[] calculateTxId(Transaction transaction) {
        if (transaction.isSegWit()) {
            // 隔离见证交易ID计算：仅使用非见证部分
            byte[] nonWitnessData = SerializeUtils.serialize(removeWitnessData(transaction));
            return doubleHash256(nonWitnessData);
        } else {
            // 普通交易ID计算
            byte[] transactionData = SerializeUtils.serialize(transaction);
            return doubleHash256(transactionData);
        }
    }

    // 移除见证数据的辅助方法
    private static Transaction removeWitnessData(Transaction tx) {
        Transaction copy = new Transaction();
        copy.setVersion(tx.getVersion());
        copy.setLockTime(tx.getLockTime());
        copy.setTime(tx.getTime());
        // 复制输入（不包含见证数据）
        List<TXInput> inputs = new ArrayList<>();
        for (TXInput input : tx.getInputs()) {
            TXInput copyInput = new TXInput();
            copyInput.setTxId(input.getTxId());
            copyInput.setVout(input.getVout());
            copyInput.setScriptSig(null);
            copyInput.setSequence(input.getSequence());
            inputs.add(copyInput);
        }
        copy.setInputs(inputs);
        // 复制输出
        copy.setOutputs(new ArrayList<>(tx.getOutputs()));
        // 清空见证数据
        copy.setWitnesses(new ArrayList<>());
        return copy;
    }


    // 双重SHA256哈希计算
    private static byte[] doubleHash256(byte[] data) {
        byte[] firstHash = DigestUtils.sha256(data);
        return DigestUtils.sha256(firstHash);
    }


    /**
     * 计算交易权重
     * 权重 = 非见证数据大小 × 4 + 见证数据大小
     */
    public void calculateWeight() {
        // 计算非见证数据大小
        long baseSize = calculateBaseSize();

        // 计算见证数据大小（签名数据）
        long witnessSize = calculateWitnessSize();

        // 计算权重
        this.weight = baseSize * 4 + witnessSize;

        // 计算总大小（VBytes）
        this.size = (long) Math.ceil((double) this.weight / 4);
    }

    /**
     * 计算非见证数据大小
     */
    private long calculateBaseSize() {
        long size = 0;

        // 交易版本大小
        size += 4; // 32位整数

        // 输入数量大小（VarInt）
        size += getVarIntSize(inputs.size());

        // 每个输入的大小（不包括见证数据）
        for (TXInput input : inputs) {
            // txOutId (32字节)
            size += 32;
            // txOutIndex (4字节)
            size += 4;
            // scriptSig大小（VarInt + 脚本数据）
            if (input.getScriptSig() != null && input.getScriptSig().getData() != null) {
                size += getVarIntSize(input.getScriptSig().getData().size());
                size += input.getScriptSig().getData().size();
            } else {
                size += 1; // 空脚本
            }
            // sequence (4字节)
            size += 4;
        }

        // 输出数量大小（VarInt）
        size += getVarIntSize(outputs.size());

        // 每个输出的大小
        for (TXOutput output : outputs) {
            // value (8字节)
            size += 8;
            // scriptPubKey大小（VarInt + 脚本数据）
            if (output.getScriptPubKey() != null && output.getScriptPubKey().getData() != null) {
                size += getVarIntSize(output.getScriptPubKey().getData().size());
                size += output.getScriptPubKey().getData().size();
            } else {
                size += 1; // 空脚本
            }
        }
        // lockTime (4字节)
        size += 4;
        return size;
    }


    /**
     * 计算见证数据大小
     * 1. 权重（Weight）与虚拟大小（vsize）计算
     * SegWit 中，交易权重和虚拟大小的计算公式为：
     *
     * 权重 = 非见证数据大小 × 4 + 见证数据大小
     * 虚拟大小（vsize）= 权重 ÷ 4（向上取整）
     *
     * 代码中calculateWeight()方法严格遵循该公式，通过baseSize * 4 + witnessSize计算权重，并通过ceil(weight / 4)计算 vsize，公式应用正确。
     */
    private long calculateWitnessSize() {
        if (!isSegWit()) {
            return 0;
        }
        long size = 0;
        // 隔离见证标记和标志（2字节）
        size += 2;
        // 每个输入的见证数据
        for (Witness witness : witnesses) {
            if (witness == null) {
                // 空见证
                size += getVarIntSize(0);
                continue;
            }
            // 见证数据项数量（VarInt）
            size += getVarIntSize(witness.getSize());
            // 每个见证数据项
            for (byte[] item : witness.getStack()) {
                if (item == null) {
                    size += getVarIntSize(0); // 空项
                } else {
                    size += getVarIntSize(item.length);
                    size += item.length;
                }
            }
        }
        return size;
    }


    /**
     * 计算VarInt的大小（以字节为单位）
     */
    private long getVarIntSize(int value) {
        if (value < 0xfd) {
            return 1;
        } else if (value <= 0xffff) {
            return 3;
        } else if (value <= 0xffffffffL) {
            return 5;
        } else {
            return 9;
        }
    }


    public long getFeePerByte() {
        return calculateFee() / size;
    }


    // 在创建交易时计算手续费  coinBase 不需要计算  CoinBase交易不会进入交易池
    public long calculateFee() {
        long fee = 0;
        long inputSum = 0;
        for (TXInput input : inputs) {
            // 获取该输入引用的输出金额（需要从UTXO集获取）
            TXOutput referencedOutput = getReferencedOutput(input);
            if (referencedOutput != null) {
                inputSum += referencedOutput.getValue();
            }
        }
        long outputSum = 0;
        for (TXOutput output : outputs) {
            outputSum += output.getValue();
        }
        fee = inputSum - outputSum;
        if (fee < 0) {
            throw new IllegalArgumentException("交易输出总额超过输入总额");
        }
        return fee;
    }



    // 获取引用的输出
    private TXOutput getReferencedOutput(TXInput input) {

        return null;
    }


    public static void main(String[] args) {
        try {
            System.out.println("===== 测试普通交易 =====");
            Transaction regularTx = createRegularTransaction();
            System.out.println("交易ID: " + CryptoUtil.bytesToHex(regularTx.getTxId()));
            System.out.println("是否隔离见证: " + regularTx.isSegWit());
            System.out.println("是否 CoinBase: " + regularTx.isCoinBase());
            System.out.println("总输入: " + regularTx.getTotalInput() + " 聪"); // 新增
            System.out.println("总输出: " + regularTx.getTotalOutput() + " 聪"); // 新增
            System.out.println("基础大小: " + regularTx.calculateBaseSize() + " 字节");
            System.out.println("见证大小: " + regularTx.calculateWitnessSize() + " 字节");
            regularTx.calculateWeight();
            System.out.println("权重: " + regularTx.getWeight());
            System.out.println("虚拟大小: " + regularTx.getSize() + " vBytes");
            /*System.out.println("交易手续费: " + regularTx.calculateFee() + " 聪"); // 手续费=总输入-总输出*/
            /*System.out.println("每字节手续费: " + regularTx.getFeePerByte() + " 聪/字节");*/

            System.out.println("\n===== 测试 CoinBase 交易 =====");
            Transaction coinbaseTx = createCoinBaseTransaction("miner-address");
            System.out.println("CoinBase 交易ID: " + CryptoUtil.bytesToHex(coinbaseTx.getTxId()));
            System.out.println("是否 CoinBase: " + coinbaseTx.isCoinBase());
            System.out.println("总输入: " + coinbaseTx.getTotalInput() + " 聪"); // 新增（应为0）
            System.out.println("总输出: " + coinbaseTx.getTotalOutput() + " 聪"); // 新增（应为补贴金额）
            System.out.println("是否隔离见证: " + coinbaseTx.isSegWit());
            coinbaseTx.calculateWeight();
            System.out.println("CoinBase 权重: " + coinbaseTx.getWeight());
            System.out.println("CoinBase 虚拟大小: " + coinbaseTx.getSize() + " vBytes");

            System.out.println("\n===== 测试隔离见证交易 =====");
            Transaction segwitTx = createSegWitTransaction();
            System.out.println("隔离见证交易ID: " + CryptoUtil.bytesToHex(segwitTx.getTxId()));
            System.out.println("是否 CoinBase: " + segwitTx.isCoinBase());
            System.out.println("是否隔离见证: " + segwitTx.isSegWit());
            System.out.println("总输入: " + segwitTx.getTotalInput() + " 聪"); // 新增
            System.out.println("总输出: " + segwitTx.getTotalOutput() + " 聪"); // 新增
            segwitTx.calculateWeight();
            System.out.println("隔离见证权重: " + segwitTx.getWeight());
            System.out.println("隔离见证虚拟大小: " + segwitTx.getSize() + " vBytes");


            try {
                System.out.println("===== 测试普通交易 =====");

                // 新增普通交易大小输出
                System.out.println("普通交易实际大小: " + regularTx.getRegularTransactionSize() + " 字节");
                System.out.println("普通交易虚拟大小(vSize): " + regularTx.getSize() + " vBytes");

                System.out.println("\n===== 测试隔离见证交易 =====");
                // 新增隔离见证交易大小输出
                System.out.println("隔离见证交易非见证数据大小: " + segwitTx.calculateBaseSize() + " 字节");
                System.out.println("隔离见证交易见证数据大小: " + segwitTx.calculateWitnessSize() + " 字节");
                System.out.println("隔离见证交易实际总大小: " + segwitTx.getSegWitTransactionTotalSize() + " 字节");
                System.out.println("隔离见证交易虚拟大小(vSize): " + segwitTx.getSize() + " vBytes");

            } catch (Exception e) {
                e.printStackTrace();
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private long getTotalOutput() {
        long outputSum = getOutputs().stream()
                .mapToLong(output -> output.getValue())
                .sum();
        return outputSum;
    }

    private long getTotalInput() {
        long inputSum = getInputs().stream()
                .map(input -> {
                    UTXO utxo = null;
                    return utxo != null ? utxo.getValue() : 0;
                })
                .mapToLong(Long::longValue)
                .sum();
        return inputSum;
    }





    // 创建一个普通交易用于测试
    private static Transaction createRegularTransaction() {
        KeyPair keyPair = CryptoUtil.ECDSASigner.generateKeyPair();
        PrivateKey privateKey = keyPair.getPrivate();
        PublicKey publicKey = keyPair.getPublic();
        // 模拟输入：引用之前的交易输出
        TXInput input = new TXInput(
                publicKey.getEncoded(),
                0,
                new ScriptSig()
        );
        input.setSequence(0xFFFFFFFF);

        // 模拟输出：支付给接收方和找零
        TXOutput output1 = new TXOutput(
                95000000, // 95000000 聪 = 0.95 BTC
                new ScriptPubKey(publicKey.getEncoded())
        );
        TXOutput output2 = new TXOutput(
                4900000, // 4900000 聪 = 0.049 BTC (找零)
                new ScriptPubKey(publicKey.getEncoded())
        );

        Transaction tx = new Transaction();
        tx.setVersion(1);
        tx.getInputs().add(input);
        tx.getOutputs().add(output1);
        tx.getOutputs().add(output2);
        tx.setTxId(calculateTxId(tx));

        return tx;
    }

    // 创建一个隔离见证交易用于测试
    private static Transaction createSegWitTransaction() {
        KeyPair keyPair = CryptoUtil.ECDSASigner.generateKeyPair();
        PrivateKey privateKey = keyPair.getPrivate();
        PublicKey publicKey = keyPair.getPublic();

        Transaction tx = createRegularTransaction();

        // 添加见证数据
        Witness witness = new Witness();
        witness.addItem(privateKey.getEncoded());
        witness.addItem(publicKey.getEncoded());
        tx.getWitnesses().add(witness);

        // 重新计算交易ID（隔离见证交易的ID计算方式不同）
        tx.setTxId(calculateTxId(tx));

        return tx;
    }


}
