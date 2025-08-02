package com.pop.popcoinsystem.util;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.pop.popcoinsystem.application.service.wallet.Wallet;
import com.pop.popcoinsystem.data.block.Block;
import com.pop.popcoinsystem.data.block.BlockBody;
import com.pop.popcoinsystem.data.block.BlockHeader;
import com.pop.popcoinsystem.data.script.Script;
import com.pop.popcoinsystem.data.script.ScriptPubKey;
import com.pop.popcoinsystem.data.script.ScriptSig;
import com.pop.popcoinsystem.data.transaction.*;
import com.pop.popcoinsystem.data.transaction.dto.WitnessDTO;
import com.pop.popcoinsystem.network.common.ExternalNodeInfo;
import com.pop.popcoinsystem.network.protocol.message.*;
import com.pop.popcoinsystem.network.protocol.messageData.Handshake;
import com.pop.popcoinsystem.network.protocol.messageData.HeadersRequestParam;
import org.objenesis.ObjenesisStd;
import org.objenesis.strategy.InstantiatorStrategy;
import com.esotericsoftware.kryo.util.DefaultInstantiatorStrategy;

import java.math.BigInteger;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.objenesis.strategy.StdInstantiatorStrategy; // 关键：引入Objenesis的策略

/**
 * 序列化工具类（Kryo 5.x 适配，无KryoPool版本）
 */
public class SerializeUtils {



    // 用ThreadLocal存储Kryo实例（每个线程一个独立实例，解决线程安全问题）
    private static final ThreadLocal<Kryo> kryoThreadLocal = ThreadLocal.withInitial(() -> {
        Kryo kryo = new Kryo();

        // 实例化策略（Kryo 5.x 必须这样配置）
        InstantiatorStrategy strategy = new DefaultInstantiatorStrategy();
        kryo.setInstantiatorStrategy(strategy);

        // 基础配置
        kryo.setRegistrationRequired(false); // 允许未注册类
        kryo.setReferences(true); // 支持循环引用

        // 注册基础类型
        kryo.register(Date.class);
        kryo.register(BigInteger.class);
        kryo.register(byte[].class);
        kryo.register(List.class);
        kryo.register(ArrayList.class);
        kryo.register(UUID.class, new UUIDSerializer()); // 自定义UUID序列化器

        // 注册加密相关类
        kryo.register(PrivateKey.class);
        kryo.register(PublicKey.class);

        // 注册网络协议相关类
        kryo.register(KademliaMessage.class);
        kryo.register(PingKademliaMessage.class);
        kryo.register(PongKademliaMessage.class);
        kryo.register(HandshakeRequestMessage.class);
        kryo.register(HandshakeResponseMessage.class);
        kryo.register(TransactionMessage.class);
        kryo.register(RpcRequestMessage.class);
        kryo.register(RpcResponseMessage.class);
        kryo.register(HeadersRequestParam.class);
        kryo.register(Handshake.class);
        kryo.register(ExternalNodeInfo.class);

        // 注册区块和交易相关类
        kryo.register(Block.class);
        kryo.register(BlockBody.class);
        kryo.register(BlockHeader.class);
        kryo.register(Transaction.class);
        kryo.register(TXInput.class);
        kryo.register(TXOutput.class);
        kryo.register(UTXO.class);
        kryo.register(Witness.class);
        kryo.register(WitnessDTO.class);
        kryo.register(Wallet.class);

        // 注册脚本相关类
        kryo.register(Script.class);
        kryo.register(Script.ScriptElement.class);
        kryo.register(ScriptPubKey.class);
        kryo.register(ScriptSig.class);

        return kryo;
    });

    /**
     * 反序列化（从字节数组恢复对象）
     */
    public static Object deSerialize(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }

        // 从当前线程获取Kryo实例
        Kryo kryo = kryoThreadLocal.get();
        Input input = null;
        try {
            input = new Input(bytes);
            return kryo.readClassAndObject(input);
        } catch (Exception e) {
            throw new RuntimeException("反序列化失败: " + e.getMessage(), e);
        } finally {
            if (input != null) {
                input.close();
            }
            // 无需回收，ThreadLocal会为线程缓存实例
        }
    }

    /**
     * 序列化（将对象转为字节数组）
     */
    public static byte[] serialize(Object object) {
        if (object == null) {
            return new byte[0];
        }

        // 从当前线程获取Kryo实例
        Kryo kryo = kryoThreadLocal.get();
        Output output = null;
        try {
            output = new Output(4096, -1); // 初始容量4096，自动扩容
            kryo.writeClassAndObject(output, object);
            return output.toBytes();
        } catch (Exception e) {
            throw new RuntimeException("序列化失败: " + e.getMessage(), e);
        } finally {
            if (output != null) {
                output.close();
            }
        }
    }

    /**
     * 自定义UUID序列化器（避免反射私有字段）
     */
    public static class UUIDSerializer extends com.esotericsoftware.kryo.Serializer<UUID> {
        @Override
        public void write(Kryo kryo, Output output, UUID uuid) {
            output.writeString(uuid.toString());
        }

        @Override
        public UUID read(Kryo kryo, Input input, Class<? extends UUID> type) {
            return UUID.fromString(input.readString());
        }
    }
}
