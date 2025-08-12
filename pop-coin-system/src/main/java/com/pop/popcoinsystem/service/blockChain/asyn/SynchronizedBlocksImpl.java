package com.pop.popcoinsystem.service.blockChain.asyn;

import com.pop.popcoinsystem.data.block.Block;
import com.pop.popcoinsystem.data.block.BlockHeader;
import com.pop.popcoinsystem.data.vo.result.Result;
import com.pop.popcoinsystem.network.common.ExternalNodeInfo;
import com.pop.popcoinsystem.network.common.NodeInfo;
import com.pop.popcoinsystem.network.enums.NodeType;
import com.pop.popcoinsystem.network.protocol.message.HandshakeRequestMessage;
import com.pop.popcoinsystem.network.protocol.message.KademliaMessage;
import com.pop.popcoinsystem.network.protocol.message.PingKademliaMessage;
import com.pop.popcoinsystem.network.protocol.message.PongKademliaMessage;
import com.pop.popcoinsystem.network.protocol.messageData.Handshake;
import com.pop.popcoinsystem.network.rpc.RpcProxyFactory;
import com.pop.popcoinsystem.network.service.KademliaNodeServer;
import com.pop.popcoinsystem.service.blockChain.BlockChainService;
import com.pop.popcoinsystem.service.blockChain.BlockChainServiceImpl;
import com.pop.popcoinsystem.service.mining.MiningServiceImpl;
import com.pop.popcoinsystem.util.BeanCopyUtils;
import com.pop.popcoinsystem.util.CryptoUtil;
import com.pop.popcoinsystem.util.DifficultyUtils;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.net.ConnectException;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static com.pop.popcoinsystem.constant.BlockChainConstants.GENESIS_PREV_BLOCK_HASH;
import static com.pop.popcoinsystem.constant.BlockChainConstants.RPC_TIMEOUT;


@Data
@Slf4j
@Component
public class SynchronizedBlocksImpl implements ApplicationRunner {
    @Autowired
    private KademliaNodeServer kademliaNodeServer;
    @Lazy
    @Autowired
    private BlockChainService localBlockChainService;
    @Lazy
    @Autowired
    private MiningServiceImpl miningService;

    private ScheduledExecutorService scheduler;

    private volatile boolean isSyncing = false;

    private  ThreadPoolExecutor processExecutor = new ThreadPoolExecutor(
            1,  // 核心线程数=1（保证顺序）
            1,  // 最大线程数=1（禁止并行）
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(100),  // 队列缓冲待处理任务
            r -> new Thread(r, "block-sync-single-thread"),
            new ThreadPoolExecutor.CallerRunsPolicy()  // 队列满时阻塞提交者，避免乱序
    );

    private static final int BLOCK_HEADER_BATCH = 500; // 区块头批次大小
    private static final int BLOCK_BODY_BATCH = 50;    // 区块体批次大小（避免单次请求过大）
    private static final int MAX_RETRY = 3;            // 单批次最大重试次数


    @Override
    public void run(ApplicationArguments args) throws Exception {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sync-scheduler");
            t.setDaemon(true);
            return t;
        });
        //延迟60秒执行 之后每30分钟执行一次
        scheduler.scheduleAtFixedRate(this::findTheHighest, 30, 60, TimeUnit.SECONDS);
    }

    public void compareAndSync(NodeInfo remoteNode,
                               long localHeight, byte[] localHash, byte[] localTotalWork,
                               long remoteHeight, byte[] remoteHash, byte[] remoteTotalWork) throws ConnectException {
        // 情况0：节点正在同步中，拒绝新请求
        if (isSyncing) {
            log.info("节点正在同步中...，不处理新的同步请求");
            return;
        }
        // 情况1：本地节点未初始化（无区块数据）仅仅一个创世区块
        if (localHeight == -1) {
            log.info("本地节点未初始化（高度: {}），请求远程完整链（远程最新高度: {}，远程总工作量: {}）",
                    localHeight, remoteHeight, remoteTotalWork);
            startSyncFromRemote(remoteNode, 0);  // 从初始状态同步
            return;
        }
        // 情况2：远程节点未初始化（无区块数据）
        if (remoteHeight == -1) {
            log.info("远程节点未初始化（高度: {}），等待远程节点拉取本地链（本地高度: {}，本地总工作量: {}）",
                    remoteHeight, localHeight, localTotalWork);
            return;
        }
        // 预检查：获取本地与远程的共同分叉点（即使高度不同也可能存在历史分叉）
        long forkHeight = findForkHeightWithBatchQuery(remoteNode, Math.min(localHeight, remoteHeight));
        log.info("获取本地与远程的分叉点（本地高度: {}，远程高度: {}，分叉点: {}）",
                localHeight, remoteHeight, forkHeight);
        // 情况3：本地链高度低于远程节点
        if (localHeight < remoteHeight) {
            // 远程高度高，且工作量更大（需要同步）
            if (DifficultyUtils.compare(remoteTotalWork,localTotalWork)==1) {
                // 存在历史分叉（共同高度后出现分歧）
                if (forkHeight < localHeight) {
                    log.info("本地链短、工作量小且存在历史分叉（本地高度:{}，远程高度:{}，分叉点:{}，本地总工作量:{}，远程总工作量:{}），从分叉点开始同步远程链",
                            localHeight, remoteHeight, forkHeight, localTotalWork, remoteTotalWork);
                    startSyncFromRemote(remoteNode, forkHeight);  // 从分叉点同步，覆盖本地分歧链
                } else {
                    // 无历史分叉，直接从本地当前高度同步
                    log.info("本地链落后且远程工作量更大（本地高度:{}，远程高度:{}，无历史分叉，本地总工作量:{}，远程总工作量:{}），开始同步远程链",
                            localHeight, remoteHeight, localTotalWork, remoteTotalWork);
                    startSyncFromRemote(remoteNode, localHeight);
                }
            } else {
                // 远程高度高但工作量更小（无效链），等待远程同步本地
                log.info("本地链高度低但工作量更大（本地高度:{}，远程高度:{}，本地总工作量:{}，远程总工作量:{}），等待远程节点同步本地链",
                        localHeight, remoteHeight, localTotalWork, remoteTotalWork);
            }
            return;
        }

        // 情况4：本地链高度高于远程节点
        if (localHeight > remoteHeight) {
            // 本地高度高，需判断工作量
            if (DifficultyUtils.compare(localTotalWork, remoteTotalWork) ==1) {
                // 本地高度高且工作量更大（有效链），无需同步
                log.info("本地链领先且工作量更大（本地高度:{}，远程高度:{}，本地总工作量:{}，远程总工作量:{}），状态正常",
                        localHeight, remoteHeight, localTotalWork, remoteTotalWork);
            } else {
                // 本地高度高但工作量更小（无效链），需同步远程
                // 检查是否存在历史分叉
                if (forkHeight < remoteHeight) {
                    log.info("本地链高但工作量小且存在历史分叉（本地高度:{}，远程高度:{}，分叉点:{}，本地总工作量:{}，远程总工作量:{}），从分叉点开始同步远程链",
                            localHeight, remoteHeight, forkHeight, localTotalWork, remoteTotalWork);
                    startSyncFromRemote(remoteNode, forkHeight);
                } else {
                    log.info("本地链高度高但工作量更小（本地高度:{}，远程高度:{}，无历史分叉，本地总工作量:{}，远程总工作量:{}），开始同步远程链",
                            localHeight, remoteHeight, localTotalWork, remoteTotalWork);
                    startSyncFromRemote(remoteNode, remoteHeight);
                }
            }
            return;
        }

        // 情况5：高度相同但哈希不同（存在分叉）
        if (!Arrays.equals(localHash, remoteHash)) {
            // 分叉时以工作量大的链为准
            if (DifficultyUtils.compare(localTotalWork, remoteTotalWork) == 1) {
                log.info("链分叉（高度:{}），本地工作量更大（本地:{}，远程:{}），等待远程同步本地链",
                        localHeight, localTotalWork, remoteTotalWork);
            } else if (DifficultyUtils.compare(remoteTotalWork,localTotalWork)==1) {
                log.info("链分叉（高度:{}），远程工作量更大（本地:{}，远程:{}），开始同步远程链",
                        localHeight, localTotalWork, remoteTotalWork);
                startSyncFromRemote(remoteNode, localHeight);
            } else {
                // 工作量相同的特殊情况（极罕见），可根据策略保留本地链
                log.info("链分叉（高度:{}），双方工作量相同（{}），保留本地链",
                        localHeight, localTotalWork);
            }
            return;
        }

        // 情况6：高度相同且哈希一致（状态正常）
        log.info("本地链与远程链状态一致（高度: {}，哈希: {}，总工作量: {}）",
                localHeight, CryptoUtil.bytesToHex(localHash), localTotalWork);
    }

    // 优化分叉点探测：增大步长+缓存哈希
    private long findForkHeightWithBatchQuery(NodeInfo remoteNode, long maxCommonHeight) {
        if (maxCommonHeight < 0) return -1;
        RpcProxyFactory proxyFactory = new RpcProxyFactory(kademliaNodeServer, remoteNode);
        BlockChainService remoteService = proxyFactory.createProxy(BlockChainService.class);

        // 缓存已查询的哈希（避免重复请求）
        Map<Long, byte[]> localHashCache = new HashMap<>();
        Map<Long, byte[]> remoteHashCache = new HashMap<>();

        long low = 0;
        long high = maxCommonHeight;
        long forkHeight = -1;
        long step = Math.max(maxCommonHeight / 10, 100); // 初始步长为总高度的1/10（至少100）

        while (low <= high) {
            // 批量查询当前区间内的关键高度（步长采样）
            List<Long> heightsToCheck = new ArrayList<>();
            for (long h = low; h <= high; h += step) {
                heightsToCheck.add(h);
            }
            heightsToCheck.add(high); // 确保覆盖终点

            // 优先从缓存获取，未命中则查询
            List<Long> localMissHeights = heightsToCheck.stream()
                    .filter(h -> !localHashCache.containsKey(h))
                    .collect(Collectors.toList());
            if (!localMissHeights.isEmpty()) {
                localHashCache.putAll(localBlockChainService.getBlockHashes(localMissHeights));
            }

            List<Long> remoteMissHeights = heightsToCheck.stream()
                    .filter(h -> !remoteHashCache.containsKey(h))
                    .collect(Collectors.toList());
            if (!remoteMissHeights.isEmpty()) {
                remoteHashCache.putAll(remoteService.getBlockHashes(remoteMissHeights));
            }

            // 查找最大匹配高度
            long currentMaxMatch = -1;
            for (long h : heightsToCheck) {
                byte[] localHash = localHashCache.get(h);
                byte[] remoteHash = remoteHashCache.get(h);
                if (localHash != null && remoteHash != null && Arrays.equals(localHash, remoteHash)) {
                    currentMaxMatch = h;
                }
            }

            if (currentMaxMatch != -1) {
                forkHeight = currentMaxMatch;
                low = currentMaxMatch + 1; // 向更高高度探测
            } else {
                high = high - step; // 向更低高度探测
            }
            // 缩小步长（二分法思想）
            step = Math.max(step / 2, 1);
        }
        return forkHeight == -1 && maxCommonHeight >= 0 ? 0 : forkHeight;
    }

    private void startSyncFromRemote(NodeInfo remoteNode, long startHeight) {
        if (remoteNode == null) {
            log.error("远程节点信息为空，无法启动同步");
            return;
        }
        // 标记同步开始
        if (isSyncing) {
            log.warn("已有同步任务在执行中，当前任务将排队等待");
            return;
        }
        startSync();
        log.info("开始从远程节点[{}]同步区块，起始高度: {}", remoteNode.getId().toString().substring(0, 8), startHeight);

        //开始同步 异步单线程每次500个区块头 验证完就下载50个区块体 并合并到主链
        processExecutor.submit(() -> {
            try {
                // 1. 初始化远程服务代理
                RpcProxyFactory rpcProxyFactory = new RpcProxyFactory(kademliaNodeServer, remoteNode);
                rpcProxyFactory.setTimeout(5000);
                BlockChainService remoteService = rpcProxyFactory.createProxy(BlockChainService.class);

                // 2. 获取远程最新高度，确定同步终点
                long remoteLatestHeight = remoteService.getMainLatestHeight();
                if (remoteLatestHeight <= startHeight) {
                    log.info("无需同步：远程最新高度[{}] <= 起始高度[{}]", remoteLatestHeight, startHeight);
                    return;
                }
                log.info("同步范围：[{} - {}]，共{}个区块",
                        startHeight, remoteLatestHeight, remoteLatestHeight - startHeight + 1);

                // 3. 按批次同步区块头+区块体
                long currentHeight = startHeight;
                while (currentHeight <= remoteLatestHeight && isSyncing) {
                    // 计算当前批次的结束高度（不超过远程最新高度）
                    long batchEnd = Math.min(currentHeight + BLOCK_HEADER_BATCH - 1, remoteLatestHeight);
                    log.info("处理批次：[{} - {}]", currentHeight, batchEnd);

                    // 4. 拉取并验证区块头（带重试）
                    Map<Long, BlockHeader> validHeaders = fetchAndValidateHeaders(remoteNode, currentHeight, batchEnd);
                    if (validHeaders == null || validHeaders.isEmpty()) {
                        log.error("批次[{} - {}]区块头验证失败，换节点重试", currentHeight, batchEnd);
                        break; // 换节点逻辑由外层调用处理
                    }

                    // 5. 拉取并验证区块体（按小批次处理，避免内存占用过大）
                    boolean batchSuccess = syncBlockBodies(remoteNode,validHeaders, currentHeight, batchEnd);
                    if (!batchSuccess) {
                        log.error("批次[{} - {}]区块体同步失败，终止同步", currentHeight, batchEnd);
                        return;
                    }

                    // 6. 批次成功，推进当前高度
                    currentHeight = batchEnd + 1;
                    // 计算总区块数（避免分母为0）
                    long totalBlocks = remoteLatestHeight - startHeight + 1;
                    double progress = totalBlocks <= 0 ? 100.0 :
                            (double) (currentHeight - startHeight) / totalBlocks * 100;

                    log.info("累计进度：{}%", progress);

                }
            } catch (Exception e) {
                log.error("同步过程异常", e);
            } finally {
                // 无论成功失败，标记同步结束
                finishSync();
                log.info("从节点[{}]的同步流程结束", remoteNode.getId().toString().substring(0, 8));
            }
        });
    }
    /**
     * 拉取并验证区块头，返回高度→区块头的映射（已验证通过）
     */
    /**
     * 拉取并验证区块头，返回高度→区块头的映射（已验证通过）
     * @param remoteNode 远程节点信息
     * @param start 起始高度
     * @param end 结束高度
     * @return 验证通过的高度→区块头映射（null表示验证失败）
     */
    private Map<Long, BlockHeader> fetchAndValidateHeaders(NodeInfo remoteNode,
                                                           long start,
                                                           long end) {
        int totalBlocks = (int) (end - start + 1); // 本批次总区块数
        int retryCount = 0;

        while (retryCount < MAX_RETRY) {
            try {
                // 1. 批量拉取区块头并建立高度映射（通过起始高度+索引推算高度）
                Map<Long, BlockHeader> heightToHeader = fetchBlockHeadersWithHeightMap(remoteNode, start, totalBlocks);

                // 2. 验证映射的高度范围是否完整（必须覆盖[start, end]）
                Long minHeight = heightToHeader.keySet().stream().min(Long::compare).orElse(-1L);
                Long maxHeight = heightToHeader.keySet().stream().max(Long::compare).orElse(-1L);
                if (minHeight != start || maxHeight != end) {
                    log.warn("区块头高度范围不完整（预期[{} - {}]，实际[{} - {}]）",
                            start, end, minHeight, maxHeight);
                    retryCount++;
                    continue;
                }

                // 3. 按高度升序遍历验证（确保链条连续性）
                List<Long> sortedHeights = new ArrayList<>(heightToHeader.keySet());
                sortedHeights.sort(Long::compare); // 强制按高度递增验证

                BlockHeader prevHeader = null;
                for (long height : sortedHeights) {
                    BlockHeader currentHeader = heightToHeader.get(height);
                    if (currentHeader == null) {
                        log.error("高度[{}]的区块头为空，验证失败", height);
                        throw new IllegalArgumentException("区块头为空");
                    }

                    // 3.1 验证PoW合法性（区块头必须满足工作量证明）
                    if (!Block.validateBlockHeaderPoW(currentHeader)) {
                        log.error("高度[{}]的区块头PoW验证失败", height);
                        throw new IllegalArgumentException("PoW验证失败");
                    }
                    // 3.2 验证prevHash连续性（与前序区块关联）
                    // 修改原prevHeader == null的判断逻辑
                    if (prevHeader == null) {
                        // 首个区块：区分起始高度为0（创世区块）和起始高度>0（普通区块）的情况
                        if (start == 0) {
                            // 情况1：从0开始同步（创世区块），验证prevHash是否符合创世区块预期（通常为全0）
                            log.info("验证创世区块（高度[{}]）的prevHash", height);
                            if (!Arrays.equals(currentHeader.getPreviousHash(), GENESIS_PREV_BLOCK_HASH)) {
                                log.error("创世区块（高度[{}]）的prevHash不符合预期，预期:{}，实际:{}",
                                        height,
                                        CryptoUtil.bytesToHex(GENESIS_PREV_BLOCK_HASH),
                                        CryptoUtil.bytesToHex(currentHeader.getPreviousHash()));
                                throw new IllegalArgumentException("创世区块prevHash验证失败");
                            }
                        } else {
                            // 情况2：从非0高度开始同步，验证前序区块哈希
                            byte[] localPrevHash = localBlockChainService.getBlockHash(start - 1);
                            if (localPrevHash == null) {
                                log.error("本地链中不存在高度[{}]的区块，无法验证前序哈希", start - 1);
                                throw new IllegalArgumentException("本地前序区块不存在");
                            }
                            log.info("验证起始区块前序哈希（本地高度:{}，哈希:{}）",
                                    start - 1, CryptoUtil.bytesToHex(localPrevHash));
                            if (!Arrays.equals(currentHeader.getPreviousHash(), localPrevHash)) {
                                log.error("高度[{}]的prevHash与本地链前序区块不匹配，预期:{}，实际:{}",
                                        height,
                                        CryptoUtil.bytesToHex(localPrevHash),
                                        CryptoUtil.bytesToHex(currentHeader.getPreviousHash()));
                                throw new IllegalArgumentException("初始区块prevHash不匹配");
                            }
                        }
                    }

                    prevHeader = currentHeader; // 推进前序区块头引用
                }

                log.info("区块头验证通过，高度范围[{} - {}]，共{}个区块",
                        start, end, totalBlocks);
                return heightToHeader;

            } catch (Exception e) {
                log.warn("区块头拉取/验证失败（第{}次重试）", retryCount + 1, e);
                retryCount++;
                // 指数退避重试（1s, 2s, 4s）
                try {
                    Thread.sleep(1000L * (1 << retryCount));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null; // 中断时直接返回失败
                }
            }
        }

        log.error("达到最大重试次数（{}次），区块头验证失败", MAX_RETRY);
        return null;
    }

    /**
     * 基于高度→区块头映射同步区块体，验证通过后合并到主链
     * @param validHeaders 已验证的高度→区块头映射
     * @param currentHeight 本批次起始高度
     * @param batchEnd 本批次结束高度
     * @return 同步成功返回true，否则返回false
     */
    private boolean syncBlockBodies(NodeInfo remoteNode,Map<Long, BlockHeader> validHeaders, long currentHeight, long batchEnd) {
        // 1. 提取有序高度列表（确保按高度升序处理）
        List<Long> sortedHeights = new ArrayList<>(validHeaders.keySet());
        sortedHeights.sort(Long::compare);
        if (sortedHeights.isEmpty()) {
            log.error("无有效区块头映射，无法同步区块体");
            return false;
        }

        // 2. 按小批次拉取区块体（避免单次请求过大）
        for (int i = 0; i < sortedHeights.size(); i += BLOCK_BODY_BATCH) {
            int batchEndIdx = Math.min(i + BLOCK_BODY_BATCH, sortedHeights.size());
            List<Long> batchHeights = sortedHeights.subList(i, batchEndIdx);
            long subStart = batchHeights.getFirst();
            long subEnd = batchHeights.getLast();
            log.info("拉取区块体子批次：[{} - {}]（共{}个区块）", subStart, subEnd, batchHeights.size());

            // 3. 从远程节点拉取当前子批次的区块体
            List<Block> blocks;
            try {
                RpcProxyFactory rpcProxyFactory = new RpcProxyFactory(kademliaNodeServer, remoteNode);
                rpcProxyFactory.setTimeout(5000);
                BlockChainService remoteService = rpcProxyFactory.createProxy(BlockChainService.class);
                blocks =  remoteService.getBlockListByRange(subStart, subEnd);
            } catch (Exception e) {
                log.error("拉取区块体子批次[{} - {}]失败", subStart, subEnd, e);
                return false;
            }

            // 4. 验证区块体数量是否匹配请求
            if (blocks == null || blocks.size() != batchHeights.size()) {
                log.error("区块体数量不匹配（请求{}个，实际{}个）",
                        batchHeights.size(), blocks == null ? 0 : blocks.size());
                return false;
            }
            //按照高度排序
            blocks.sort(Comparator.comparingLong(Block::getHeight));

            // 5. 逐个验证区块体并合并到主链
            for (Block block : blocks) {
                long blockHeight = block.getHeight(); // 区块体自带高度（基准值）

                // 5.1 验证区块高度是否在当前映射中
                if (!validHeaders.containsKey(blockHeight)) {
                    log.error("区块[{}]不在已验证的高度列表中，丢弃", blockHeight);
                    return false;
                }
                // 5.2 验证区块体头与区块头哈希一致性
                BlockHeader expectedHeader = validHeaders.get(blockHeight);
                byte[] blockHeaderHash = block.getHeader().computeHash();
                byte[] expectedHash = expectedHeader.computeHash();
                if (!Arrays.equals(blockHeaderHash, expectedHash)) {
                    log.error("区块[{}]体与头哈希不匹配（体哈希:{}，头哈希:{}）",
                            blockHeight,
                            CryptoUtil.bytesToHex(blockHeaderHash),
                            CryptoUtil.bytesToHex(expectedHash));
                    return false;
                }
                boolean verifyBlock = localBlockChainService.verifyBlock(block, false);
                // 5.3 验证区块完整性（交易合法性、签名等）
                if (!verifyBlock) {
                    log.error("区块[{}]完整性验证失败", blockHeight);
                    return false;
                }
                log.info("区块[{}]成功合并到主链", blockHeight);
            }

            log.info("区块体子批次[{} - {}]同步完成", subStart, subEnd);
        }

        log.info("批次[{} - {}]区块体同步完成", currentHeight, batchEnd);
        return true;
    }

    // 批量获取区块头并建立高度映射
    private Map<Long, BlockHeader> fetchBlockHeadersWithHeightMap(NodeInfo remoteNode, long startHeight, int count) {
        RpcProxyFactory proxyFactory = new RpcProxyFactory(kademliaNodeServer, remoteNode);
        proxyFactory.setTimeout(5000);
        BlockChainService remoteService = proxyFactory.createProxy(BlockChainService.class);
        // 假设远程返回的区块头按高度升序排列（从startHeight开始）
        List<BlockHeader> headers = remoteService.getBlockHeaders(startHeight, count);
        if (headers == null || headers.size() != count) {
            throw new RuntimeException("区块头数量不匹配: 请求" + count + "个，实际" + (headers == null ? 0 : headers.size()));
        }
        // 建立高度→区块头的映射（关键：通过请求参数推算高度）
        Map<Long, BlockHeader> heightToHeaderMap = new TreeMap<>(); // TreeMap保证按高度升序
        for (int i = 0; i < headers.size(); i++) {
            long height = startHeight + i; // 第i个区块头对应高度=起始高度+i
            heightToHeaderMap.put(height, headers.get(i));
        }
        return heightToHeaderMap;
    }

    @PreDestroy
    public void destroy() {
        scheduler.shutdown();
        processExecutor.shutdown();
    }

    // 同步服务中开始同步的方法
    public void startSync() {
        if (!isSyncing) {
            isSyncing = true;
            // 通知挖矿服务停止挖矿，并记录原状态
            miningService.pauseMiningForSync();
        }
    }

    // 同步服务中结束同步的方法
    public void finishSync() {
        isSyncing = false;
        // 同步完成后，若同步前在挖矿，则恢复挖矿
        log.info("同步结束 恢复挖矿");
        miningService.resumeMiningAfterSync();
    }


    /**
     * 终止同步任务
     */
    public void stopTask(String taskId) {
        finishSync();
    }

    private void findTheHighest() {
        getNetworkMaxHeight();
    }
    private void getNetworkMaxHeight() {
        try {
            NodeInfo local = kademliaNodeServer.getNodeInfo();
            // 默认使用本地高度作为基准
            long maxHeight = localBlockChainService.getMainLatestHeight();
            //已知在线全部全节点
            List<ExternalNodeInfo> allNodes = kademliaNodeServer.getRoutingTable().findNodesByType(NodeType.FULL);
            log.info("开始获取网络最大高度{}", allNodes);
            // 遍历健康节点获取最高高度
            for (ExternalNodeInfo nodeInfo : allNodes) {
                NodeInfo node = nodeInfo.extractNodeInfo();
                try {
                    RpcProxyFactory proxyFactory = new RpcProxyFactory(kademliaNodeServer, node);
                    BlockChainService remoteService = proxyFactory.createProxy(BlockChainService.class);
                    long remoteHeight = remoteService.getMainLatestHeight();
                    log.info("获取节点{}高度{}", node.getId(), remoteHeight);
                    if (remoteHeight > maxHeight) {
                        maxHeight = remoteHeight;
                        log.info("更新网络最高高度为: {} (来自节点 {})", maxHeight, node.getId());
                        //发送最高高度广播给临近节点帮助同步 TODO
                        Block mainLatestBlock =localBlockChainService.getMainLatestBlock();
                        SyncRequest syncRequest = new SyncRequest();
                        byte[] localLatestChainWork = mainLatestBlock.getChainWork();
                        byte[] localLatestHash = mainLatestBlock.getHash();
                        long localLatestHeight = mainLatestBlock.getHeight();
                        syncRequest.setChainWork(localLatestChainWork);
                        syncRequest.setLatestBlockHash(localLatestHash);
                        syncRequest.setLatestBlockHeight(localLatestHeight);
                        syncRequest.setGenesisBlockHash(localBlockChainService.GENESIS_BLOCK_HASH());
                        SyncResponse syncResponse = remoteService.RequestSynchronization(syncRequest);

                        //拿到响应后对比
                        if (!syncResponse.isAllowSync()) {
                            log.error("同步请求被拒绝：{}", syncResponse.getRejectReason());
                        }else {
                            //拿到响应数据提交差异
                            long remoteLatestBlockHeight = syncResponse.getLatestBlockHeight();
                            byte[] remoteLatestBlockHash = syncResponse.getLatestBlockHash();
                            byte[] remoteLatestChainWork = syncResponse.getChainWork();
                            compareAndSync(
                                    node,
                                    localLatestHeight,
                                    localLatestHash,
                                    localLatestChainWork,
                                    remoteLatestBlockHeight,
                                    remoteLatestBlockHash,
                                    remoteLatestChainWork
                            );
                        }
                    }
                } catch (Exception e) {
                    log.warn("获取节点{}的高度失败: {}", node.getId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("获取网络最高高度失败", e);
            localBlockChainService.getMainLatestHeight();
        }
    }
}
