package com.pop.popcoinsystem.util;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.pool.KryoFactory;
import com.esotericsoftware.kryo.pool.KryoPool;
import com.pop.popcoinsystem.network.protocol.message.*;

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

        kryo.register(java.util.Date.class);
        kryo.register(java.util.UUID.class);

        kryo.register(PrivateKey.class);
        kryo.register(PublicKey.class);


        // 配置Kryo（根据需要调整）
        kryo.setRegistrationRequired(false);
        kryo.setReferences(true);
        return kryo;
    };
    // 创建线程安全的 Kryo 池
    private static final KryoPool pool = new KryoPool.Builder(factory).build();



    /**
     * 泛型反序列化方法
     * @param bytes 对象对应的字节数组
     * @param <T> 目标类型
     * @return 反序列化后的对象
     */
    @SuppressWarnings("unchecked")
    public static <T> T deSerializeT(byte[] bytes) {
        return pool.run(kryo -> {
            // 使用 try-with-resources 确保 Input 流被关闭
            try (Input input = new Input(bytes)) {
                return (T) kryo.readClassAndObject(input);
            }
        });
    }

    /**
     * 序列化方法
     * @param object 需要序列化的对象
     * @return 序列化后的字节数组
     */
    public static byte[] serializeT(Object object) {
        return pool.run(kryo -> {
            // 使用 try-with-resources 确保 Output 流被关闭
            try (Output output = new Output(4096, -1)) {
                kryo.writeClassAndObject(output, object);
                return output.toBytes();
            }
        });
    }

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
