package com.pop.popcoinsystem.data.transaction;

import com.pop.popcoinsystem.data.block.Block;
import com.pop.popcoinsystem.data.enums.SigHashType;
import com.pop.popcoinsystem.data.script.Script;
import com.pop.popcoinsystem.data.script.ScriptPubKey;
import com.pop.popcoinsystem.data.script.ScriptSig;
import com.pop.popcoinsystem.util.CryptoUtil;
import com.pop.popcoinsystem.util.SerializeUtils;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.Serializable;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;


import static com.pop.popcoinsystem.constant.BlockChainConstants.*;

/**
 * 交易信息
 */
//隔离见证（SegWit） 通过将签名数据（见证数据）与交易的其他部分分离，解决了上述问题。交易被分为：
//
//非见证数据：包含版本号、输入输出结构、锁定时间等固定信息。
//见证数据：包含签名、公钥等证明交易合法性的信息。

/**
 * 交易 ID（txid）的计算机制
 * 核心逻辑：
 * txid 是对交易的 完整非见证数据（包括 nLockTime、输入输出、版本号等）进行 双重 SHA-256 哈希 的结果。
 * 计算公式：txid = SHA256(SHA256(非见证数据))。
 * 非见证数据范围：
 * 包括版本号、输入（不含见证数据）、输出、nLockTime 等，但不包含隔离见证的见证数据。
 * 时间的影响：
 * nLockTime 直接参与哈希计算，若交易的 nLockTime 被修改，即使其他字段不变，txid 也会完全不同。
 *
 *
 *核心逻辑：
 * wtxid 是对交易的 完整数据（包括非见证数据和见证数据）进行 双重 SHA-256 哈希 的结果。
 * 计算公式：wtxid = SHA256(SHA256(完整数据))。
 * 完整数据范围：
 * 包括版本号、输入（含见证数据）、输出、nLockTime、见证数据等所有字段。
 * 时间的影响：
 * nLockTime 和见证数据中的时间相关信息（如签名时间戳）均会影响 wtxid 的计算。
 *
 */


@Slf4j
@Data
@AllArgsConstructor
@NoArgsConstructor
public class Transaction implements Serializable {
    /**
     * 交易ID（txid）：基于非见证数据的双重SHA256哈希
     * 非见证数据范围：版本号 + 输入列表（不含见证数据） + 输出列表 + 锁定时间 + 交易创建时间
     */
    private byte[] txId;  //基于非见证数据计算（版本、输入输出、锁定时间等，不含见证数据）

    /**
     * 隔离见证ID（wtxid）：基于完整数据的双重SHA256哈希
     * 完整数据范围：非见证数据 + 见证数据（隔离见证交易）/ 与txid一致（普通交易）
     */
    private byte[] wtxId; //基于完整数据（含见证数据）计算，非隔离见证交易与txId一致

    /**
     * 交易版本
     */
    private int version;

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
    private long lockTime;


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









    // ------------------------------ 核心：ID计算逻辑重构 ------------------------------
    public byte[] calculateTxId() {
        // 1. 生成非见证数据快照（仅包含计算txid必需的字段）
        Transaction nonWitnessTx = createNonWitnessSnapshot();
        // 2. 序列化非见证数据（严格按规范字段顺序）
        byte[] nonWitnessBytes = nonWitnessTx.serializeNonWitnessData();
        // 3. 双重SHA256哈希
        return doubleHash256(nonWitnessBytes);
    }

    /**
     * 计算隔离见证ID（wtxid）：基于完整数据的双重SHA256哈希
     * 完整数据 = 非见证数据 + 见证数据（隔离见证交易）；普通交易与txid一致
     */
    public byte[] calculateWtxId() {
        if (!isSegWit()) {
            return calculateTxId(); // 普通交易wtxid与txid相同
        }
        // 1. 生成完整数据快照（包含所有字段，包括见证数据）
        Transaction fullTx = createFullSnapshot();
        // 2. 序列化完整数据（非见证数据 + 见证数据）
        byte[] fullBytes = fullTx.serializeFullData();
        // 3. 双重SHA256哈希
        return doubleHash256(fullBytes);
    }


    // ------------------------------ 快照创建：严格控制数据范围 ------------------------------
    /**
     * 创建非见证数据快照（仅用于txid计算）
     * 确保快照中不包含任何见证数据，且核心字段与原交易严格一致
     */
    private Transaction createNonWitnessSnapshot() {
        Transaction snapshot = new Transaction();
        // 复制基础字段（不可变）
        snapshot.version = this.version;
        snapshot.lockTime = this.lockTime;

        // 复制输入列表（清空scriptSig以外的无关数据，且不含见证数据）
        List<TXInput> inputSnapshots = new ArrayList<>();
        for (TXInput input : this.inputs) {
            TXInput inputSnapshot = new TXInput();
            inputSnapshot.setTxId(Arrays.copyOf(input.getTxId(), input.getTxId().length)); // 深拷贝前序交易ID
            inputSnapshot.setVout(input.getVout());
            inputSnapshot.setSequence(input.getSequence());
            inputSnapshot.setScriptSig(input.getScriptSig() != null ? input.getScriptSig().copy() : null); // 深拷贝脚本
            inputSnapshots.add(inputSnapshot);
        }
        snapshot.inputs = inputSnapshots;

        // 复制输出列表（完整保留，输出不含见证数据）
        List<TXOutput> outputSnapshots = new ArrayList<>();
        for (TXOutput output : this.outputs) {
            TXOutput outputSnapshot = new TXOutput();
            outputSnapshot.setValue(output.getValue());
            outputSnapshot.setScriptPubKey(output.getScriptPubKey() != null ? output.getScriptPubKey().copy() : null); // 深拷贝脚本
            outputSnapshots.add(outputSnapshot);
        }
        snapshot.outputs = outputSnapshots;

        // 强制清空见证数据（非见证快照核心特征）
        snapshot.witnesses = new ArrayList<>();
        return snapshot;
    }

    /**
     * 创建完整数据快照（仅用于wtxid计算）
     * 包含所有字段（非见证数据 + 见证数据），确保与原交易完全一致
     */
    private Transaction createFullSnapshot() {
        Transaction snapshot = this.copy(); // 利用深拷贝机制复制所有字段
        // 清除临时计算的ID，避免干扰序列化
        snapshot.txId = null;
        snapshot.wtxId = null;
        return snapshot;
    }


    // ------------------------------ 序列化：严格遵循规范格式 ------------------------------


    /**
     * 序列化非见证数据（用于txid计算）
     * 格式：版本号（4字节小端）→ 输入数量（VarInt）→ 输入列表 → 输出数量（VarInt）→ 输出列表 → 锁定时间（4字节小端）→ 交易时间（8字节小端）
     */
    private byte[] serializeNonWitnessData() {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {

            // 1. 版本号（32位整数，小端序）
            dos.writeInt(Integer.reverseBytes(version));

            // 2. 输入数量（VarInt编码）
            writeVarInt(dos, inputs.size());

            // 3. 输入列表
            for (TXInput input : inputs) {
                // 3.1 前序交易ID（32字节，小端序）
                byte[] txId = input.getTxId();
                if (txId != null) {
                    dos.write(Arrays.copyOf(txId, 32)); // 确保32字节长度
                } else {
                    dos.write(new byte[32]); // 空ID填0
                }

                // 3.2 前序输出索引（32位整数，小端序）
                dos.writeInt(Integer.reverseBytes(input.getVout()));

                // 3.3 解锁脚本（ScriptSig）：长度（VarInt）+ 脚本字节
                ScriptSig scriptSig = input.getScriptSig();
                byte[] scriptSigBytes = scriptSig != null ? scriptSig.getScriptBytes() : new byte[0];
                writeVarInt(dos, scriptSigBytes.length);
                dos.write(scriptSigBytes);

                // 3.4 序列号（32位整数，小端序）
                dos.writeInt(Integer.reverseBytes((int) input.getSequence()));
            }

            // 4. 输出数量（VarInt编码）
            writeVarInt(dos, outputs.size());

            // 5. 输出列表
            for (TXOutput output : outputs) {
                // 5.1 金额（64位整数，小端序）
                dos.writeLong(Long.reverseBytes(output.getValue()));

                // 5.2 锁定脚本（ScriptPubKey）：长度（VarInt）+ 脚本字节
                ScriptPubKey scriptPubKey = output.getScriptPubKey();
                byte[] scriptPubKeyBytes = scriptPubKey != null ? scriptPubKey.getScriptBytes() : new byte[0];
                writeVarInt(dos, scriptPubKeyBytes.length);
                dos.write(scriptPubKeyBytes);
            }

            // 6. 锁定时间（32位整数，小端序）
            dos.writeInt(Integer.reverseBytes((int) lockTime));



            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("非见证数据序列化失败", e);
        }
    }

    /**
     * 序列化完整数据（用于wtxid计算）
     * 格式：非见证数据 + 隔离见证标记（0x0001） + 见证数据列表
     */
    private byte[] serializeFullData() {
        try (java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
             java.io.DataOutputStream dos = new java.io.DataOutputStream(baos)) {

            // 1. 先写入非见证数据
            baos.write(serializeNonWitnessData());

            // 2. 隔离见证标记（0x0001，仅隔离见证交易需要）
            dos.writeShort(0x0001); // 固定标记，区分隔离见证交易

            // 3. 见证数据列表（每个输入对应一个见证）
            for (Witness witness : witnesses) {
                if (witness == null) {
                    writeVarInt(dos, 0); // 空见证
                    continue;
                }
                // 3.1 见证项数量（VarInt）
                writeVarInt(dos, witness.getStack().size());
                // 3.2 每个见证项：长度（VarInt）+ 数据
                for (byte[] item : witness.getStack()) {
                    if (item == null) {
                        writeVarInt(dos, 0);
                    } else {
                        writeVarInt(dos, item.length);
                        dos.write(item);
                    }
                }
            }

            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("完整数据序列化失败", e);
        }
    }










    /**
     * 写入VarInt编码（比特币变量长度整数编码）
     * 格式：<0xfd → 1字节；0xfd-0xffff → 0xfd + 2字节小端；0x10000-0xffffffff → 0xfe + 4字节小端；更大 → 0xff + 8字节小端
     */
    private void writeVarInt(DataOutputStream dos, int value) throws IOException {
        if (value < 0xfd) {
            dos.writeByte(value);
        } else if (value <= 0xffff) {
            dos.writeByte(0xfd);
            dos.writeShort(Short.reverseBytes((short) value));
        } else if (value <= 0xffffffffL) {
            dos.writeByte(0xfe);
            dos.writeInt(Integer.reverseBytes(value));
        } else {
            dos.writeByte(0xff);
            dos.writeLong(Long.reverseBytes(value));
        }
    }






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















    public static Transaction copyWithoutWitness(Transaction tx) {
        Transaction copy = new Transaction();
        copy.setVersion(tx.getVersion());
        copy.setLockTime(tx.getLockTime());
        // 复制输入（保留txId、vout、sequence，清空scriptSig）
        List<TXInput> inputs = new ArrayList<>();
        for (TXInput input : tx.getInputs()) {
            TXInput inputCopy = new TXInput();
            inputCopy.setTxId(input.getTxId() != null ? Arrays.copyOf(input.getTxId(), input.getTxId().length) : null);
            inputCopy.setVout(input.getVout());
            inputCopy.setSequence(input.getSequence());
            inputCopy.setScriptSig(input.getScriptSig());
            inputs.add(inputCopy);
        }
        copy.setInputs(inputs);
        // 复制输出（完整保留，输出不含见证数据）
        copy.setOutputs(new ArrayList<>(tx.getOutputs()));
        // 强制清空见证数据
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






    public Transaction copy() {
        Transaction copy = new Transaction();
        // 复制基本类型字段
        copy.setVersion(this.version);
        copy.setSize(this.size);
        copy.setWeight(this.weight);
        copy.setLockTime(this.lockTime);

        // 深拷贝txId和wtxId
        copy.txId = this.txId != null ? Arrays.copyOf(this.txId, this.txId.length) : null;
        copy.wtxId = this.wtxId != null ? Arrays.copyOf(this.wtxId, this.wtxId.length) : null;


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
     * 交易数据更新时强制刷新ID（如修改输入/输出/见证数据后调用）
     */
    public void refreshIds() {
        this.txId = calculateTxId();
        this.wtxId = calculateWtxId();
        this.setSize();
        this.calculateWeight();
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
        this.txId = calculateTxId();
        this.wtxId = calculateWtxId();
        this.setSize(); // 同步更新大小
        this.calculateWeight(); // 同步更新权重
    }

    // 修正ID获取与更新逻辑
    public byte[] getTxId() {
        if (txId == null) {
            txId = calculateTxId();
        }
        return txId;
    }

    public byte[] getWtxId() {
        if (wtxId == null) {
            wtxId = calculateWtxId();
        }
        return wtxId;
    }


    // 新增普通交易的哈希计算实现
    public byte[] calculateLegacySignatureHash(int inputIndex, SigHashType sigHashType) {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream dos = new java.io.DataOutputStream(baos);
        try {
            // 1. 写入版本号
            dos.writeInt(this.getVersion());

            // 2. 处理输入（根据ANYONECANPAY标志）
            boolean anyoneCanPay = (sigHashType.getValue() & 0x80) != 0;

            // 2.1 写入输入数量
            if (anyoneCanPay) {
                dos.writeLong(1); // 仅包含当前输入
            } else {
                dos.writeLong(this.getInputs().size());
            }

            // 2.2 写入输入内容
            for (int i = 0; i < this.getInputs().size(); i++) {
                TXInput input = this.getInputs().get(i);

                // ANYONECANPAY标志：跳过非当前输入
                if (anyoneCanPay && i != inputIndex) {
                    continue;
                }

                // 写入前序交易ID和输出索引
                dos.write(input.getTxId());
                dos.writeInt(input.getVout());

                // 写入scriptSig（仅当前输入保留临时脚本，其他输入为空）
                if (i == inputIndex) {
                    byte[] scriptBytes = input.getScriptSig() != null ? input.getScriptSig().getScriptBytes() : new byte[0];
                    dos.writeLong(scriptBytes.length);
                    dos.write(scriptBytes);
                } else {
                    dos.writeLong(0); // 非当前输入的scriptSig为空
                }

                // 写入sequence（根据哈希类型处理）
                dos.writeLong(input.getSequence());
            }

            // 3. 处理输出（根据哈希类型：ALL/NONE/SINGLE）
            byte baseType = (byte) (sigHashType.getValue() & 0x7F);

            if (baseType == SigHashType.NONE.getValue()) {
                // NONE：不包含任何输出
                dos.writeLong(0);

                // 非当前输入的sequence设为0
                if (!anyoneCanPay) {
                    for (int i = 0; i < this.getInputs().size(); i++) {
                        if (i != inputIndex) {
                            dos.writeLong(0);
                        }
                    }
                }
            } else if (baseType == SigHashType.SINGLE.getValue()) {
                // SINGLE：仅包含与输入索引相同的输出
                if (inputIndex >= this.getOutputs().size()) {
                    return new byte[32]; // 索引无效，返回全0哈希
                }
                dos.writeLong(inputIndex + 1); // 输出数量为输入索引+1
                for (int i = 0; i < inputIndex; i++) {
                    dos.writeLong(-1); // 前序输出金额设为-1
                    dos.writeLong(0);  // 前序输出脚本长度为0
                }
                // 写入当前索引的输出
                TXOutput output = this.getOutputs().get(inputIndex);
                dos.writeLong(output.getValue());
                byte[] scriptBytes = output.getScriptPubKey().getScriptBytes();
                dos.writeLong(scriptBytes.length);
                dos.write(scriptBytes);

                // 非当前输入的sequence设为0
                if (!anyoneCanPay) {
                    for (int i = 0; i < this.getInputs().size(); i++) {
                        if (i != inputIndex) {
                            dos.writeLong(0);
                        }
                    }
                }
            } else {
                // ALL：包含所有输出
                dos.writeLong(this.getOutputs().size());
                for (TXOutput output : this.getOutputs()) {
                    dos.writeLong(output.getValue());
                    byte[] scriptBytes = output.getScriptPubKey().getScriptBytes();
                    dos.writeLong(scriptBytes.length);
                    dos.write(scriptBytes);
                }
            }

            // 4. 写入锁定时间
            dos.writeLong(this.getLockTime());

            // 5. 写入哈希类型（4字节）
            dos.writeInt(sigHashType.getValue());

            // 6. 双重SHA256哈希（比特币标准）
            byte[] serialized = baos.toByteArray();
            byte[] hash1 = CryptoUtil.applySHA256(serialized);
            return CryptoUtil.applySHA256(hash1);

        } catch (IOException e) {
            throw new RuntimeException("计算普通交易签名哈希失败", e);
        }
    }


    public long getWitnessSize() {
        // 直接调用已实现的见证数据大小计算方法
        return calculateWitnessSize();
    }


    public static byte[] createWitnessSignatureHash(Transaction tx, int inputIndex, UTXO utxo, SigHashType sigHashType) {

        // 1. 复制交易对象，避免修改原交易
        Transaction txCopy = Transaction.copyWithoutWitness(tx);
        for (TXInput input : txCopy.getInputs()) {
            input.setScriptSig(null);
        }
        txCopy.setVersion(0);

        // 获取当前处理的输入
        TXInput currentInput = txCopy.getInputs().get(inputIndex);
        if (currentInput == null){
            log.warn("输入索引超出范围");
            throw new RuntimeException("输入索引超出范围");
        }
        ScriptPubKey originalScriptPubKey  = utxo.getScriptPubKey();
        ScriptSig tempByScriptPubKey = ScriptSig.createTempByScriptPubKey(originalScriptPubKey);
        String scripString = tempByScriptPubKey.toScripString();

        //在区块链交易签名流程中，代码里的临时脚本（tempByScriptPubKey）不需要执行完整验证，
        // 其核心作用是作为签名哈希（Signature Hash）计算的 “特征上下文”，确保签名能正确关联到被花费的 UTXO 的锁定规则。
        currentInput.setScriptSig(tempByScriptPubKey); // 临时设置，仅用于签名
        // 4. 创建签名哈希
        log.info("植入特征{}", scripString);
        return txCopy.calculateWitnessSignatureHash(inputIndex, utxo.getValue(), sigHashType);
    }





    public static byte[] createLegacySignatureHash(Transaction tx, int inputIndex, UTXO utxo, SigHashType sigHashType) {
        //打印所有的参数
        // 1. 复制交易对象，避免修改原交易
        Transaction txCopy  = new Transaction();
        // 1. 仅复制计算签名哈希必需的核心字段，去除无关数据
        // 保留核心字段：版本、输入列表、输出列表、锁定时间（这些是签名哈希计算的必要项）
        txCopy.setInputs(copyEssentialInputs(tx.getInputs())); // 仅复制输入的必要信息
        txCopy.setOutputs(new ArrayList<>(tx.getOutputs())); // 复制输出列表（浅拷贝足够，无需修改）
        txCopy.setLockTime(tx.getLockTime());
        // 3. 获取当前输入并验证
        TXInput currentInput = txCopy.getInputs().get(inputIndex);
        if (currentInput == null) {
            log.warn("输入索引超出范围");
            throw new RuntimeException("输入索引超出范围");
        }
        // 4. 将当前输入对应的UTXO的scriptPubKey作为临时scriptSig（核心步骤，必须执行）
        ScriptPubKey originalScriptPubKey = utxo.getScriptPubKey();
        if (originalScriptPubKey == null) {
            log.warn("UTXO的scriptPubKey为空");
            throw new IllegalArgumentException("UTXO的scriptPubKey不能为空");
        }
        ScriptSig tempScriptSig = new ScriptSig(originalScriptPubKey.getScriptBytes());
        currentInput.setScriptSig(tempScriptSig);

        // 5. 计算签名哈希
        return txCopy.calculateLegacySignatureHash(inputIndex, sigHashType);
    }
    private static List<TXInput> copyEssentialInputs(List<TXInput> inputs) {
        List<TXInput> copiedInputs = new ArrayList<>();
        for (TXInput input : inputs) {
            TXInput inputCopy = new TXInput();
            // 仅复制输入的必要信息
            inputCopy.setTxId(Arrays.copyOf(input.getTxId(), input.getTxId().length));
            inputCopy.setVout(input.getVout());
            inputCopy.setSequence(input.getSequence());
            copiedInputs.add(inputCopy);
        }
        return copiedInputs;
    }


    /**
     * 创建创世区块的CoinBase交易
     * CoinBase交易特殊性：
     * 1. 没有输入（或输入为特殊值）
     * 2. 输出为初始挖矿奖励
     */
    public static Transaction createGenesisCoinbaseTransaction() {
        Transaction coinbaseTx = new Transaction();
        // 创建特殊输入（引用自身）
        TXInput input = new TXInput();
        input.setTxId(new byte[32]); // 全零交易ID
        input.setVout(0); // 特殊值表示CoinBase交易
        byte[] bytes = "The Times 03/Jan/2009 Chancellor on brink of second bailout for banks".getBytes();
        ScriptSig scriptSig = new ScriptSig(bytes);
        input.setScriptSig(scriptSig);
        List<TXInput> inputs = new ArrayList<>();
        inputs.add(input);
        coinbaseTx.setInputs(inputs);
        // 创建输出（初始奖励50 BTC = 50*1e8聪）
        TXOutput output = new TXOutput();
        output.setValue(50L * 100000000); // 50 BTC in satoshi
        // 创世区块奖励地址（可以替换为你的项目地址）
        String address = "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa";
        //获取地址公钥哈希
        byte[] addressHash = CryptoUtil.ECDSASigner.getAddressHash(address);
        ScriptPubKey pubKey = new ScriptPubKey(addressHash);
        output.setScriptPubKey(pubKey);
        List<TXOutput> outputs = new ArrayList<>();
        outputs.add(output);
        coinbaseTx.setOutputs(outputs);
        // 计算交易ID
        byte[] txId = coinbaseTx.calculateTxId();
        coinbaseTx.setTxId(txId);
        coinbaseTx.calculateWeight();
        return coinbaseTx;
    }

    /**
     * 判断是否为CoinBase交易（区块中第一笔交易，输入无有效UTXO）
     */
    public static boolean isCoinBaseTransaction(Transaction transaction) {
        List<TXInput> inputs = transaction.getInputs();
        if (inputs == null || inputs.size() != 1) {
            return false;
        }
        TXInput input = inputs.getFirst();
        // CoinBase交易的输入txId为全零，且vout为特殊值（如-1或0，根据协议定义）
        return input.getTxId() != null
                && Arrays.equals(input.getTxId(), new byte[32])  // txId为全零
                && (input.getVout() == -1 || input.getVout() == 0);  // 匹配协议定义的特殊值
    }

}
