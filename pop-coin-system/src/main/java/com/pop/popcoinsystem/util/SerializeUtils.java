package com.pop.popcoinsystem.util;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.pool.KryoFactory;
import com.esotericsoftware.kryo.pool.KryoPool;
import com.pop.popcoinsystem.application.service.wallet.Wallet;
import com.pop.popcoinsystem.data.script.Script;
import com.pop.popcoinsystem.data.script.ScriptPubKey;
import com.pop.popcoinsystem.data.script.ScriptSig;
import com.pop.popcoinsystem.data.transaction.UTXO;
import com.pop.popcoinsystem.network.common.ExternalNodeInfo;
import com.pop.popcoinsystem.network.protocol.message.*;

import java.math.BigInteger;
import java.security.PrivateKey;
import java.security.PublicKey;

/**
 * 序列化工具类
 */
public class SerializeUtils {
    // 创建 Kryo 工厂
    private static final KryoFactory factory = () -> {
        Kryo kryo = new Kryo();
        // 注册常用类
        kryo.register(KademliaMessage.class);
        kryo.register(PingKademliaMessage.class);
        kryo.register(PongKademliaMessage.class);

        kryo.register(HandshakeRequestMessage.class);
        kryo.register(HandshakeResponseMessage.class);

        kryo.register(TransactionMessage.class);


        kryo.register(java.util.Date.class);
        kryo.register(java.util.UUID.class);

        kryo.register(PrivateKey.class);
        kryo.register(PublicKey.class);


        kryo.register(ExternalNodeInfo.class);


        kryo.register(Script.class);
        kryo.register(ScriptPubKey.class);
        kryo.register(ScriptSig.class);

        kryo.register(UTXO.class);

        kryo.register(BigInteger.class);  // 假设包含 BigInteger 等基础类型外的类
        kryo.register(byte[].class);  // 若有字节数组也需注册

        kryo.register(Wallet.class);

        // 配置Kryo（根据需要调整）
        kryo.setRegistrationRequired(false);
        kryo.setReferences(true);
        return kryo;
    };
    // 创建线程安全的 Kryo 池
    private static final KryoPool pool = new KryoPool.Builder(factory).build();

    /**
     * 反序列化
     *
     * @param bytes 对象对应的字节数组
     * @return
     */
    public static Object deSerialize(byte[] bytes) {
        Input input = new Input(bytes);
        Object obj = new Kryo().readClassAndObject(input);
        input.close();
        return obj;
    }

    /**
     * 序列化
     *
     * @param object 需要序列化的对象
     * @return
     */
    public static byte[] serialize(Object object) {
        Output output = new Output(4096, -1);
        new Kryo().writeClassAndObject(output, object);
        byte[] bytes = output.toBytes();
        output.close();
        return bytes;
    }
}
