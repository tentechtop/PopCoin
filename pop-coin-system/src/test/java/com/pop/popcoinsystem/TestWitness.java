package com.pop.popcoinsystem;

import com.pop.popcoinsystem.data.enums.SigHashType;
import com.pop.popcoinsystem.util.CryptoUtil;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Arrays;

import static com.pop.popcoinsystem.util.SegWitUtils.createSigHashType;

public class TestWitness {
    public static void main(String[] args) {
        KeyPair keyPair = CryptoUtil.ECDSASigner.generateKeyPair();
        PrivateKey privateKey = keyPair.getPrivate();
        PublicKey publicKey = keyPair.getPublic();

        byte[] txToSign = new byte[32];
        Arrays.fill(txToSign, (byte)0x01);



        // 伪代码示例：签名生成时附加 sigHashType
        byte[] signature = CryptoUtil.ECDSASigner.applySignature(privateKey, txToSign);
        byte[] signatureWithHashType = createSigHashType(signature, SigHashType.NONE);


        SigHashType sigHashType = extractSigHashType(signatureWithHashType);
        System.out.println("SigHashType: " + sigHashType);

        byte[] originalSignature = extractOriginalSignature(signatureWithHashType);


        boolean b = CryptoUtil.ECDSASigner.verifySignature(publicKey, txToSign, originalSignature);
        System.out.println("signature: " + b);


    }



    public static SigHashType extractSigHashType(byte[] signature) {
        if (signature == null || signature.length == 0) {
            throw new IllegalArgumentException("无效的签名数据");
        }
        byte sigHashByte = signature[signature.length - 1];
        // 根据字节值映射到对应的 SigHashType 枚举
        for (SigHashType type : SigHashType.values()) {
            if (type.getValue() == sigHashByte) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知的 SigHashType: 0x" + String.format("%02X", sigHashByte));
    }


    /**
     * 从包含 SigHashType 的签名数据中提取原始签名部分
     * @param signatureWithHashType 包含 SigHashType 的完整签名数据
     * @return 原始签名数据（不包含最后的 SigHashType 字节）
     */
    public static byte[] extractOriginalSignature(byte[] signatureWithHashType) {
        if (signatureWithHashType == null || signatureWithHashType.length < 1) {
            throw new IllegalArgumentException("无效的签名数据：长度不足");
        }
        // 提取原始签名部分（排除最后一个字节的 SigHashType）
        return Arrays.copyOf(signatureWithHashType, signatureWithHashType.length - 1);
    }


    /*    public Result<String> startMiningTest() throws Exception {
        if (isMining) {
            return Result.error("ERROR: The node is already mining ! ");
        }
        //获取矿工信息
        miner = POPStorage.getInstance().getMiner();
        if (miner == null) {
            //挖矿前请设置本节点的矿工信息
            return Result.error("ERROR: Please set the miner information first ! ");
        }
        isMining = true;
        log.info("开始挖矿:Starting mining...");
        // 初始化线程池
        executor = Executors.newFixedThreadPool(threadCount);
        new Thread(() -> {
            Thread.currentThread().setPriority(Thread.NORM_PRIORITY);//NORM_PRIORITY  MIN_PRIORITY
            while (isMining) {
                monitorCpuLoad();
                List<Transaction> transactions = getTransactionsByPriority();
                if (transactions.isEmpty()) {
                    log.info("No transactions available for mining.");
                }
                //获取主链最新的区块hash 和 区块高度
                byte[] latestBlockHash = blockChainService.getMainLatestBlockHash();
                log.info("最新区块Hash:"+CryptoUtil.bytesToHex(latestBlockHash));
                long blockHeight = blockChainService.getMainLatestHeight();
                log.info("最新区块Hash: {}", blockHeight);
                Block latestBlock = blockChainService.getMainBlockByHeight(blockHeight);


                Block newBlock = new Block();
                newBlock.setPreviousHash(latestBlockHash);
                newBlock.setHeight(blockHeight+1);
                newBlock.setTime(System.currentTimeMillis());
                ArrayList<Transaction> blockTransactions = new ArrayList<>();
                //创建CoinBase交易 放在第一位
                Transaction coinBaseTransaction = Transaction.createCoinBaseTransaction(miner.getAddress(),blockHeight+1);
                blockTransactions.add(coinBaseTransaction);
                blockTransactions.addAll(transactions);
                newBlock.setTransactions(blockTransactions);
                newBlock.calculateAndSetMerkleRoot();
                newBlock.setTime(System.currentTimeMillis() /1000 );
                newBlock.setDifficulty(currentDifficulty);
                newBlock.setDifficultyTarget(DifficultyUtils.difficultyToCompact(currentDifficulty));

                //  //表示该区块之前的区块链总工作量，以十六进制表示。它反映了整个区块链的挖矿工作量。
                //    private byte[] chainWork;
                //计算工作总量
                byte[] chainWork = latestBlock.getChainWork();
                byte[] add = DifficultyUtils.add(chainWork, currentDifficulty);
                newBlock.setChainWork(add);

                //挖矿奖励：通过 coinbase 交易嵌入区块体
                //每个区块的第一笔交易是coinbase 交易（特殊交易，无输入），其输出部分直接包含矿工的挖矿奖励。例如：
                //比特币区块的 coinbase 交易输出会包含 “基础奖励 + 区块内所有交易的手续费总和”，这笔输出会被记录在区块体的交易列表中。
                //区块只需存储这笔交易，就能通过交易验证逻辑自动计算出矿工获得的总奖励（无需额外字段）。
                //手续费：隐含在普通交易的 “输入 - 输出差额” 中
                //普通交易中，输入金额总和 - 输出金额总和 = 手续费，这部分差额由打包该交易的矿工获得。
                //例如：用户发起一笔交易，输入 10 个代币，输出 9.9 个代币，差额 0.1 个代币即为手续费。这部分无需单独记录，通过遍历区块内所有交易的输入输出即可计算。
                System.out.println("\n开始挖矿新区块 #" + newBlock.getHeight() +
                        " (难度: " + newBlock.getDifficulty() + ", 交易数: " + transactions.size());
                MiningResult result = mineBlock(newBlock);
                MiningResult result2 = mineBlock(newBlock);
                MiningResult result3 = mineBlock(newBlock);

                if (result != null && result.found) {
                    newBlock.setNonce(result.nonce);
                    newBlock.setHash(result.hash);

                    Block block = BeanCopyUtils.copyObject(newBlock, Block.class);
                    block.setNonce(result2.nonce);
                    block.setHash(result2.hash);


                    byte[] mainBlockHashByHeight = blockChainService.getMainBlockHashByHeight(newBlock.getHeight() - 10);
                    if (mainBlockHashByHeight != null){
                        //制造非延续冲突
                        log.info("非延续冲突:{}",CryptoUtil.bytesToHex(mainBlockHashByHeight));
                        Block blockByHash = blockChainService.getBlockByHash(mainBlockHashByHeight);
                        byte[] chainWork1 = blockByHash.getChainWork();
                        byte[] add1 = DifficultyUtils.add(chainWork1, currentDifficulty);
                        Block block3 = BeanCopyUtils.copyObject(newBlock, Block.class);
                        block3.setHeight(newBlock.getHeight()-9);
                        block3.setPreviousHash(mainBlockHashByHeight);
                        block3.setNonce(result3.nonce);
                        block3.setHash(result3.hash);
                        block3.setChainWork(add1);
                        blockChainService.verifyBlock(block3);
                    }

                    adjustDifficulty();
                    // 挖矿成功：移除已打包的交易
                    for (Transaction tx : transactions) {
                        removeTransaction(CryptoUtil.bytesToHex(tx.getTxId()));
                    }
                    //将区块提交到区块链
                    blockChainService.verifyBlock(newBlock);

                    blockChainService.verifyBlock(block);
                } else {
                    // 未找到有效哈希，将交易放回交易池（避免丢失）
                    log.info("区块 #" + newBlock.getHeight() + " 挖矿失败，重新尝试...");
                    // 挖矿失败：延迟重试（例如3秒），减少CPU占用
                    try {
                        Thread.sleep(1000); // 1秒后重试
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break; // 中断时退出循环
                    }
                }
            }
        }).start();
        return Result.ok();
    }*/



    //           pipeline.addLast(new LengthFieldBasedFrameDecoder(
    //                                    10 * 1024 * 1024,  // 最大帧长度
    //                                    4,                 // 长度字段偏移量（跳过类型字段）
    //                                    4,                 // 长度字段长度（总长度字段）
    //                                    -8,                // 长度调整值 = 内容长度 - 总长度 = -8
    //                                    0                 // 跳过前12字节（类型+总长度+内容长度）  目前不跳过
    //                            ));





    //logging:
    //  level:
    //    root: ERROR  # 全局日志级别设为ERROR
    //  file:
    //    name: ../logs/popcoin.log  # 主日志文件路径
    //  logback:
    //    rollingpolicy:
    //      max-file-size: 10MB  # 单个日志文件的最大容量
    //      max-history: 30  # 保留历史日志的天数
    //  pattern:
    //    console: "%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %highlight(%-5level) %cyan(%logger{50}:%L) - %msg%n"  # 控制台输出格式
    //    file: "%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{50}:%L - %msg%n"  # 文件输出格式
    //  # 自定义appender配置
    //  config: classpath:logback-spring.xml  # 引用自定义的logback配置文件


    //          Bucket bucket = routingTable.findBucket(id);
    //            // 为每个节点创建超时任务：若超时未收到Pong，则标记为不活跃 清楚掉节点
    //            ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    //            for (BigInteger nodeId : bucket.getNodeIds()) {
    //                ExternalNodeInfo oldNode = bucket.getNode(nodeId);
    //                PingKademliaMessage pingKademliaMessage = new PingKademliaMessage();
    //                pingKademliaMessage.setSender(kademliaNodeServer.getNodeInfo());
    //                pingKademliaMessage.setReceiver(message.getSender());
    //                kademliaNodeServer.getTcpClient().sendMessage(pingKademliaMessage);
    //                // 超时任务：5秒未收到Pong，则认为不活跃，从桶中移除
    //                scheduler.schedule(() -> {
    //                    Date now = new Date();
    //                    // 计算时间差（毫秒）
    //                    long timeDiff = now.getTime() - oldNode.getLastSeen().getTime();
    //                    // 若最后活跃时间超过阈值（如5秒），则判定为不活跃
    //                    if (timeDiff > 5000) {
    //                        log.info("节点 {} 不活跃，从路由表移除", oldNode.getId());
    //                        bucket.remove(oldNode.getId()); // 从桶中移除
    //                    }
    //                }, 5, TimeUnit.SECONDS); // 超时时间设为3秒（可调整）
    //            }

}
