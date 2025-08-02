package com.pop.popcoinsystem.util;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.pool.KryoFactory;
import com.esotericsoftware.kryo.pool.KryoPool;
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
import java.math.BigInteger;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import org.objenesis.strategy.StdInstantiatorStrategy;

/**
 * 序列化工具类
 */
public class SerializeUtils {
    // 创建 Kryo 工厂
    private static final KryoFactory factory = () -> {
        Kryo kryo = new Kryo();
        // 关键配置：允许循环引用（解决复杂对象引用问题）
        // 非必须注册所有类（但建议显式注册以提高性能）
        kryo.setRegistrationRequired(false);

        // 关键配置：使用Objenesis解决无参构造函数问题
        kryo.setInstantiatorStrategy(new StdInstantiatorStrategy());
        kryo.setReferences(true);

        // 注册基础类型和常用类
        kryo.register(Date.class);
        kryo.register(UUID.class);
        kryo.register(BigInteger.class);
        kryo.register(byte[].class);
        kryo.register(List.class);
        kryo.register(ArrayList.class); // 具体集合类型需要注册
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


        // 注册脚本相关类（关键：补充内部类注册）
        kryo.register(Script.class);
        kryo.register(Script.ScriptElement.class);
        kryo.register(ScriptPubKey.class);
        kryo.register(ScriptSig.class);

        return kryo;
    };
    // 创建线程安全的 Kryo 池
    // 创建线程安全的Kryo池（多线程环境必须使用池化）
    private static final KryoPool pool = new KryoPool.Builder(factory)
            .softReferences() // 允许GC回收闲置实例，避免内存泄漏
            .build();


    /**
     * 反序列化（从字节数组恢复对象）
     */
    public static Object deSerialize(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        // 从池获取Kryo实例（关键：复用配置好的实例）
        Kryo kryo = pool.borrow();
        Input input = null;
        try {
            input = new Input(bytes);
            return kryo.readClassAndObject(input);
        } finally {
            if (input != null) {
                input.close();
            }
            // 归还实例到池
            pool.release(kryo);
        }
    }

    /**
     * 序列化（将对象转为字节数组）
     */
    public static byte[] serialize(Object object) {
        if (object == null) {
            return new byte[0];
        }
        // 从池获取Kryo实例
        Kryo kryo = pool.borrow();
        Output output = null;
        try {
            output = new Output(4096, -1); // 初始容量4096，可自动扩容
            kryo.writeClassAndObject(output, object);
            return output.toBytes();
        } finally {
            if (output != null) {
                output.close();
            }
            // 归还实例到池
            pool.release(kryo);
        }
    }
}
