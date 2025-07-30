package com.pop.popcoinsystem.data.transaction;

import com.pop.popcoinsystem.data.enums.SigHashType;
import com.pop.popcoinsystem.data.script.Script;
import com.pop.popcoinsystem.data.script.ScriptPubKey;
import com.pop.popcoinsystem.data.script.ScriptSig;
import com.pop.popcoinsystem.util.BeanCopyUtils;
import com.pop.popcoinsystem.util.CryptoUtil;
import com.pop.popcoinsystem.util.SerializeUtils;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;

import java.io.IOException;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;


import static com.pop.popcoinsystem.data.transaction.constant.VERSION_1;
import static com.pop.popcoinsystem.service.MiningService.*;

/**
 * 交易信息
 */
//隔离见证（SegWit） 通过将签名数据（见证数据）与交易的其他部分分离，解决了上述问题。交易被分为：
//
//非见证数据：包含版本号、输入输出结构、锁定时间等固定信息。
//见证数据：包含签名、公钥等证明交易合法性的信息。

@Slf4j
@Data
@AllArgsConstructor
@NoArgsConstructor
public class Transaction implements Cloneable{
    /**
     * 交易ID（txid）：
     * - 非隔离见证交易：整个交易数据（不含见证数据，因无见证数据）的双重SHA256哈希
     * - 隔离见证交易：仅非见证数据（版本、输入输出结构、锁定时间等）的双重SHA256哈希
     */
    private byte[] txId;  //基于非见证数据计算（版本、输入输出、锁定时间等，不含见证数据）

    /**
     * 见证交易ID（wtxid）：
     * - 非隔离见证交易：与txid相同
     * - 隔离见证交易：包含所有数据（非见证数据+见证数据）的双重SHA256哈希
     */
    private byte[] wtxId; //基于完整数据（含见证数据）计算，非隔离见证交易与txId一致

    /**
     * 交易版本
     */
    private int version = 1;

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
    public static  Transaction createCoinBaseTransaction(String to, long height,long totalFee) {
        //地址到公钥哈希
        byte[] bytes = CryptoUtil.ECDSASigner.addressToP2PKH(to);
        //coinBase的输入的交易ID
        byte[] zeroTxId = new byte[32]; // 32字节 = 256位
        Arrays.fill(zeroTxId, (byte) 0);
        TXInput input = new TXInput(zeroTxId, 0, null);
        // 创建输出，将奖励发送到指定地址
        TXOutput output = new TXOutput(calculateBlockReward(height)+totalFee, new ScriptPubKey(bytes));
        Transaction coinbaseTx = new Transaction();
        coinbaseTx.setVersion(VERSION_1);
        coinbaseTx.getInputs().add(input);
        coinbaseTx.getOutputs().add(output);
        coinbaseTx.setTime(System.currentTimeMillis());
        // 计算并设置交易ID
        coinbaseTx.setTxId(Transaction.calculateTxId(coinbaseTx));
        coinbaseTx.setSize(coinbaseTx.calculateBaseSize());
        coinbaseTx.calculateWeight();
        return coinbaseTx;
    }




    /**
     * 计算区块奖励（每2100万个区块奖励减半，与比特币机制一致）
     * @param blockHeight 当前区块高度
     * @return 区块奖励（包含基础奖励，单位为1e8）
     */
    public static long calculateBlockReward(long blockHeight) {
        // 1. 计算减半次数：当前高度 / 减半周期（2100万）
        long halvings = blockHeight / HALVING_PERIOD;
        // 2. 初始奖励为50，每减半一次除以2（最小为0）
        long reward = INITIAL_REWARD;
        for (long i = 0; i < halvings; i++) {
            reward /= 2;
            if (reward == 0) break; // 奖励为0时提前终止
        }
        // 3. 确保奖励非负（理论上不会出现负数，此处为防御性编程）
        reward = Math.max(reward, 0);
        // 4. 乘以单位（1e8），转换为最终奖励值
        return reward * BLOCK_REWARD_UNIT;
    }




    /**
     * 计算交易txid（核心方法，统一逻辑）
     * 无论是否为隔离见证交易，均基于非见证数据计算
     */
    public static byte[] calculateTxId(Transaction transaction) {
        // 复制交易并移除见证数据（确保计算基础为非见证数据）
        Transaction nonWitnessTx = copyWithoutWitness(transaction);
        // 序列化非见证数据
        byte[] nonWitnessData = SerializeUtils.serialize(nonWitnessTx);
        // 双重SHA256计算txid
        return doubleHash256(nonWitnessData);
    }

    /**
     * 计算交易wtxid（核心方法，统一逻辑）
     * 隔离见证交易包含见证数据，非隔离见证交易与txid一致
     */
    public static byte[] calculateWtxId(Transaction transaction) {
        if (!transaction.isSegWit()) {
            // 非隔离见证交易：wtxid = txid
            return calculateTxId(transaction);
        }
        Transaction copy = transaction.copy();
        copy.setWtxId(null);
        // 隔离见证交易：序列化完整交易（含见证数据）
        byte[] fullData = SerializeUtils.serialize(copy);
        // 双重SHA256计算wtxid
        return doubleHash256(fullData);
    }



    public static Transaction copyWithoutWitness(Transaction tx) {
        Transaction copy = new Transaction();
        copy.setVersion(tx.getVersion());
        copy.setLockTime(tx.getLockTime());
        copy.setTime(tx.getTime());
        // 复制输入（保留txId、vout、sequence，清空scriptSig）
        List<TXInput> inputs = new ArrayList<>();
        for (TXInput input : tx.getInputs()) {
            TXInput inputCopy = new TXInput();
            inputCopy.setTxId(input.getTxId() != null ? Arrays.copyOf(input.getTxId(), input.getTxId().length) : null);
            inputCopy.setVout(input.getVout());
            inputCopy.setSequence(input.getSequence());
            inputCopy.setScriptSig(null); // 排除scriptSig（见证数据相关）
            inputs.add(inputCopy);
        }
        copy.setInputs(inputs);
        // 复制输出（完整保留，输出不含见证数据）
        copy.setOutputs(new ArrayList<>(tx.getOutputs()));
        // 强制清空见证数据
        copy.setWitnesses(new ArrayList<>());
        return copy;
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
    public long calculateBaseSize() {
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


    public Transaction copy() {
        Transaction copy = new Transaction();
        // 复制基本类型字段
        copy.setVersion(this.version);
        copy.setSize(this.size);
        copy.setWeight(this.weight);
        copy.setLockTime(this.lockTime);
        copy.setTime(this.time);

        // 深拷贝 txId 数组（避免引用同一数组）
        if (this.txId != null) {
            copy.setTxId(Arrays.copyOf(this.txId, this.txId.length));
        }
        // 深拷贝输入列表（包含每个 TXInput 的拷贝）
        List<TXInput> copiedInputs = new ArrayList<>();
        for (TXInput input : this.inputs) {
            TXInput inputCopy = new TXInput();
            // 复制输入的 txId 数组
            if (input.getTxId() != null) {
                inputCopy.setTxId(Arrays.copyOf(input.getTxId(), input.getTxId().length));
            }
            inputCopy.setVout(input.getVout());
            inputCopy.setSequence(input.getSequence());
            // 深拷贝 ScriptSig（如果存在）
            if (input.getScriptSig() != null) {
                ScriptSig scriptSigCopy = new ScriptSig();
                ScriptSig scriptSig = input.getScriptSig();
                List<Script.ScriptElement> elements = scriptSig.getElements();
                for (Script.ScriptElement element : elements) {
                    if (element.isOpCode()) {
                        scriptSigCopy.addOpCode(element.getOpCode());
                    } else {
                        scriptSigCopy.addData(element.getData());
                    }
                }
                log.info("拷贝后scriptSigCopy:"+scriptSigCopy.toScripString());
                inputCopy.setScriptSig(scriptSigCopy);
            }
            copiedInputs.add(inputCopy);
        }
        copy.setInputs(copiedInputs);

        // 深拷贝输出列表（包含每个 TXOutput 的拷贝）
        List<TXOutput> copiedOutputs = new ArrayList<>();
        for (TXOutput output : this.outputs) {
            TXOutput outputCopy = new TXOutput();
            outputCopy.setValue(output.getValue());
            // 深拷贝 ScriptPubKey（如果存在）
            if (output.getScriptPubKey() != null) {
                ScriptPubKey scriptPubKey = output.getScriptPubKey();
                ScriptPubKey pubKeyCopy = new ScriptPubKey();
                List<Script.ScriptElement> elements = scriptPubKey.getElements();
                for (Script.ScriptElement element : elements){
                    if (element.isOpCode()){
                        pubKeyCopy.addOpCode(element.getOpCode());
                    }else {
                        pubKeyCopy.addData(element.getData());
                    }
                }
                log.info("拷贝后scriptPubKey: " + pubKeyCopy.toScripString());
                outputCopy.setScriptPubKey(pubKeyCopy);
            }
            copiedOutputs.add(outputCopy);
        }
        copy.setOutputs(copiedOutputs);

        // 深拷贝见证数据列表（包含每个 Witness 的拷贝）
        List<Witness> copiedWitnesses = new ArrayList<>();
        for (Witness witness : this.witnesses) {
            if (witness == null) {
                copiedWitnesses.add(null);
                continue;
            }
            Witness witnessCopy = new Witness();
            // 深拷贝见证数据中的每个字节数组
            List<byte[]> copiedStack = new ArrayList<>();
            for (byte[] item : witness.getStack()) {
                if (item != null) {
                    copiedStack.add(Arrays.copyOf(item, item.length));
                } else {
                    copiedStack.add(null);
                }
            }
            witnessCopy.setStack(copiedStack);
            copiedWitnesses.add(witnessCopy);
        }
        copy.setWitnesses(copiedWitnesses);

        return copy;
    }

    /**
     * 计算交易的总字节大小（包含所有数据：基础数据 + 见证数据）
     * 自动区分交易类型（普通交易/隔离见证交易）并返回对应总大小
     * @return 交易总字节大小（long类型）
     */
    public long calculateTotalSize() {
        if (isSegWit()) {
            // 隔离见证交易总大小 = 非见证数据大小 + 见证数据大小
            return calculateBaseSize() + calculateWitnessSize();
        } else {
            // 普通交易总大小 = 非见证数据大小（无见证数据）
            return calculateBaseSize();
        }
    }

    /**
     * 计算隔离见证交易的签名哈希
     * @param inputIndex 输入索引
     * @param amount 该输入对应的UTXO金额
     * @param sigHashType 签名哈希类型
     * @return 签名哈希值
     */
    public byte[] calculateWitnessSignatureHash(int inputIndex, long amount, SigHashType sigHashType) {
        // 1. 序列化交易的基本部分（不含见证数据）
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream dos = new java.io.DataOutputStream(baos);
        // 现实现（更安全）：使用复制的非见证交易实例
        //Transaction sigHashTx = copyWithoutWitness(this);

        try {
            // 1.1 写入版本号
            dos.writeInt(this.getVersion());

            // 1.2 写入锁定时间
            dos.writeLong(this.getLockTime());

            // 处理 ANYONECANPAY 标志
            boolean anyoneCanPay = (sigHashType.getValue() & 0x80) != 0;

            // 1.3 写入输入数量
            if (anyoneCanPay) {
                dos.writeLong(1); // 只包含当前输入
            } else {
                dos.writeLong(this.getInputs().size());
            }

            // 1.4 写入所有输入（scriptSig已被清空为scriptCode）
            for (int i = 0; i < this.getInputs().size(); i++) {
                TXInput input = this.getInputs().get(i);

                // 处理 ANYONECANPAY：只包含当前输入
                if (anyoneCanPay && i != inputIndex) {
                    continue;
                }

                dos.write(input.getTxId());
                dos.writeInt(input.getVout());

                // 对于当前输入，使用完整的 scriptCode
                if (i == inputIndex) {
                    byte[] scriptBytes = input.getScriptSig() != null ? input.getScriptSig().getScriptBytes() : new byte[0];
                    dos.writeLong(scriptBytes.length);
                    dos.write(scriptBytes);
                } else {
                    // 对于其他输入，使用空脚本
                    dos.writeLong(0);
                }

                // 对于当前输入，使用原始 sequence；对于其他输入，根据标志处理
                dos.writeLong(input.getSequence());
            }

            // 1.5 写入输出数量
            // 获取基本类型（移除 ANYONECANPAY 标志）
            byte baseType = (byte) (sigHashType.getValue() & 0x7F);

            if (baseType == SigHashType.NONE.getValue()) {
                // 不签名任何输出（可由矿工修改）
                dos.writeLong(0); // 输出数量为0

                // 对于 NONE 类型，所有输入的 sequence 设为 0
                if (!anyoneCanPay) {
                    for (int i = 0; i < this.getInputs().size(); i++) {
                        if (i != inputIndex) {
                            dos.writeLong(0); // sequence 为 0
                        }
                    }
                }
            } else if (baseType == SigHashType.SINGLE.getValue()) {
                // 仅签名指定索引的输出（需与输入索引匹配）
                if (inputIndex >= this.getOutputs().size()) {
                    // 无效索引，返回特殊哈希值
                    return new byte[32];
                }
                // 只保留指定索引的输出，其他输出被替换为"空白"
                dos.writeLong(inputIndex + 1);
                for (int i = 0; i < inputIndex; i++) {
                    dos.writeLong(-1); // 金额为-1
                    dos.writeLong(0); // 脚本长度为0
                }
                // 写入指定索引的输出
                TXOutput output = this.getOutputs().get(inputIndex);
                dos.writeLong(output.getValue());
                byte[] scriptBytes = output.getScriptPubKey().getScriptBytes();
                dos.writeLong(scriptBytes.length);
                dos.write(scriptBytes);

                // 对于 SINGLE 类型，除当前输入外的其他输入 sequence 设为 0
                if (!anyoneCanPay) {
                    for (int i = 0; i < this.getInputs().size(); i++) {
                        if (i != inputIndex) {
                            dos.writeLong(0); // sequence 为 0
                        }
                    }
                }
            } else {
                // ALL 类型：写入所有输出
                dos.writeLong(this.getOutputs().size());
                for (TXOutput output : this.getOutputs()) {
                    dos.writeLong(output.getValue());
                    byte[] scriptBytes = output.getScriptPubKey().getScriptBytes();
                    dos.writeLong(scriptBytes.length);
                    dos.write(scriptBytes);
                }
            }

            // 3. 写入当前输入的金额（隔离见证特有）
            dos.writeLong(amount);

            // 4. 写入scriptCode长度和scriptCode（当前输入的解锁脚本模板）
            TXInput currentInput = this.getInputs().get(inputIndex);
            byte[] scriptCode = currentInput.getScriptSig().getScriptBytes();
            dos.writeLong(scriptCode.length);
            dos.write(scriptCode);

            // 5. 写入sigHashType（作为4字节整数）
            dos.writeInt(sigHashType.getValue());

            // 6. 计算双SHA256哈希
            byte[] serializedTx = baos.toByteArray();
            byte[] hash1 = CryptoUtil.applySHA256(serializedTx);
            return CryptoUtil.applySHA256(hash1);

        } catch (IOException e) {
            throw new RuntimeException("计算见证签名哈希失败", e);
        }
    }


    /**
     * 交易数据更新时调用（如修改输入输出、添加见证数据），强制重新计算ID
     */
    public void updateData() {
        this.txId = calculateTxId(this);
        this.wtxId = calculateWtxId(this);
        this.setSize(); // 同步更新大小
        this.calculateWeight(); // 同步更新权重
    }

    // 修正ID获取与更新逻辑
    public byte[] getTxId() {
        if (txId == null) {
            txId = calculateTxId(this);
        }
        return txId;
    }

    public byte[] getWtxId() {
        if (wtxId == null) {
            wtxId = calculateWtxId(this);
        }
        return wtxId;
    }



}
