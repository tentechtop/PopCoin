package com.pop.popcoinsystem;

import com.pop.popcoinsystem.application.service.TemplateStorage;
import com.pop.popcoinsystem.application.service.Wallet;
import com.pop.popcoinsystem.data.storage.POPStorage;
import com.pop.popcoinsystem.data.transaction.UTXO;
import com.pop.popcoinsystem.util.CryptoUtil;

import java.math.BigDecimal;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * UTXO测试数据生成工具
 */
public class UTXOTestGenerator {
    private static final Logger log = Logger.getLogger(UTXOTestGenerator.class.getName());
    private static final int BATCH_SIZE = 1000; // 每批生成的UTXO数量
    private final POPStorage storage;
    private final Random random = new Random();

    public UTXOTestGenerator(POPStorage storage) {
        this.storage = storage;
    }

    /**
     * 生成指定数量的UTXO测试数据
     * @param count 需要生成的UTXO数量
     */
    public void generateUTXOs(int count) {
        log.info("开始生成 " + count + " 个UTXO测试数据...");
        long startTime = System.currentTimeMillis();

        int batches = count / BATCH_SIZE;
        AtomicInteger progress = new AtomicInteger(0);

        // 使用多线程加速生成过程
        ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

        for (int i = 0; i <= batches; i++) {
            executor.submit(() -> {
                List<UTXO> batch = new ArrayList<>(BATCH_SIZE);
                for (int j = 0; j < BATCH_SIZE; j++) {
                    batch.add(createRandomUTXO());
                }
                // 批量添加到存储
                storage.addUtxos(batch);
                int completed = progress.addAndGet(BATCH_SIZE);
                if (completed % (BATCH_SIZE * 100) == 0) {
                    log.info("已生成: " + completed + "/" + count);
                }
            });
        }

        // 处理剩余的UTXO
        int remaining = count % BATCH_SIZE;
        if (remaining > 0) {
            executor.submit(() -> {
                List<UTXO> batch = new ArrayList<>(remaining);
                for (int j = 0; j < remaining; j++) {
                    batch.add(createRandomUTXO());
                }
                storage.addUtxos(batch);
                log.info("已生成: " + count + "/" + count);
            });
        }

        executor.shutdown();
        while (!executor.isTerminated()) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                log.log(Level.WARNING, "生成过程被中断", e);
                Thread.currentThread().interrupt();
                break;
            }
        }

        long endTime = System.currentTimeMillis();
        log.info("UTXO测试数据生成完成，耗时: " + (endTime - startTime) + "ms");

    }

    /**
     * 创建一个随机的UTXO
     */
    private UTXO createRandomUTXO() {
        try {
            // 生成随机密钥对
            TemplateStorage instance = TemplateStorage.getInstance();
            Wallet  walleta = instance.getWallet("wallet-b");
            String publicKeyHex = walleta.getPublicKeyHex();


            // 生成随机交易哈希
            byte[] txToSign = new byte[32];
            random.nextBytes(txToSign);

            // 生成随机地址 (P2PKH或P2WPKH)
            String address  = CryptoUtil.ECDSASigner.createP2PKHAddressByPK(CryptoUtil.hexToBytes(publicKeyHex));

            // 创建UTXO
            UTXO utxo = new UTXO();
            utxo.setAddress(address);
            // 生成随机金额 (0.00000001 - 10 BTC)
            long satoshiValue = (long) ((random.nextDouble() * 10 + 0.00000001) * 100000000);
            utxo.setValue(satoshiValue);
            utxo.setTxId(txToSign);
            utxo.setVout(random.nextInt(10)); // 随机vout索引

            return utxo;
        } catch (Exception e) {
            log.log(Level.SEVERE, "生成UTXO失败", e);
            // 返回一个空的UTXO作为后备
            return new UTXO();
        }
    }

    // 示例用法
    public static void main(String[] args) {
        POPStorage storage = POPStorage.getInstance();
        UTXOTestGenerator generator = new UTXOTestGenerator(storage);

        // 生成100万个UTXO
/*        generator.generateUTXOs(1_000_000);*/

        TemplateStorage instance = TemplateStorage.getInstance();
        Wallet  walleta = instance.getWallet("wallet-b");
        String publicKeyHex = walleta.getPublicKeyHex();
        String address  = CryptoUtil.ECDSASigner.createP2PKHAddressByPK(CryptoUtil.hexToBytes(publicKeyHex));

/*        List<UTXO> utxosByAddress = storage.getUtxosByAddress(address);
        log.info("地址: " + address + " 的UTXO数量: " + utxosByAddress.size());*/

    }
}