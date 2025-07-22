package com.pop.popcoinsystem.data.storage.back;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * 优化后的 UTXO 管理系统 - 增强版分层桶结构实现
 */
public class OptimizedUTXOMangementSystem {
    // 地址余额映射 - O(1) 查询地址总余额
    private final Map<String, BigDecimal> addressBalances = new ConcurrentHashMap<>();

    // 地址到桶集合的映射
    private final Map<String, List<UTXOBucket>> addressBuckets = new ConcurrentHashMap<>();

    // 地址级别的锁，支持更细粒度的并发控制
    private final Map<String, ReentrantLock> addressLocks = new ConcurrentHashMap<>();

    // 桶的金额区间定义（指数级递增）
    private static final List<BigDecimal> BUCKET_THRESHOLDS = Arrays.asList(
            new BigDecimal("0.01"),
            new BigDecimal("0.1"),
            new BigDecimal("1"),
            new BigDecimal("10"),
            new BigDecimal("100"),
            new BigDecimal("1000"),
            new BigDecimal("10000")
    );

    // UTXO 类 - 表示未花费交易输出
    static class UTXO {
        private final String utxoId;        // UTXO 唯一标识
        private final BigDecimal amount;    // UTXO 金额
        private volatile boolean isSpent;   // 是否已花费（volatile 保证可见性）
        private final long timestamp;       // 创建时间戳，用于冷数据识别

        public UTXO(String utxoId, BigDecimal amount) {
            this.utxoId = utxoId;
            this.amount = amount;
            this.isSpent = false;
            this.timestamp = System.currentTimeMillis();
        }

        // Getters and setters
        public String getUtxoId() { return utxoId; }
        public BigDecimal getAmount() { return amount; }
        public boolean isSpent() { return isSpent; }
        public void markAsSpent() { this.isSpent = true; }
        public long getTimestamp() { return timestamp; }

        @Override
        public String toString() {
            return "UTXO{id=" + utxoId + ", amount=" + amount + ", spent=" + isSpent + "}";
        }
    }

    // 桶类 - 管理特定金额区间的 UTXO
    static class UTXOBucket {
        private final BigDecimal minAmount;  // 桶的最小金额（包含）
        private final BigDecimal maxAmount;  // 桶的最大金额（不包含）
        private volatile BigDecimal totalAmount;      // 桶内所有 UTXO 的总金额
        private final List<UTXO> utxos;      // 桶内的 UTXO 列表
        private final ReentrantLock bucketLock = new ReentrantLock();  // 桶级锁

        public UTXOBucket(BigDecimal minAmount, BigDecimal maxAmount) {
            this.minAmount = minAmount;
            this.maxAmount = maxAmount;
            this.totalAmount = BigDecimal.ZERO;
            this.utxos = new ArrayList<>();
        }

        // 添加 UTXO 到桶中
        public void addUTXO(UTXO utxo) {
            bucketLock.lock();
            try {
                if (utxo.getAmount().compareTo(minAmount) < 0 ||
                        utxo.getAmount().compareTo(maxAmount) >= 0) {
                    throw new IllegalArgumentException("UTXO 金额超出桶的范围");
                }
                utxos.add(utxo);
                totalAmount = totalAmount.add(utxo.getAmount());
            } finally {
                bucketLock.unlock();
            }
        }

        // 从桶中移除 UTXO
        public void removeUTXO(UTXO utxo) {
            bucketLock.lock();
            try {
                if (utxos.remove(utxo)) {
                    totalAmount = totalAmount.subtract(utxo.getAmount());
                }
            } finally {
                bucketLock.unlock();
            }
        }

        // 获取桶内所有可用的 UTXO
        public List<UTXO> getAvailableUTXOs() {
            bucketLock.lock();
            try {
                return utxos.stream()
                        .filter(utxo -> !utxo.isSpent())
                        .collect(Collectors.toList());
            } finally {
                bucketLock.unlock();
            }
        }

        // 检查桶内可用 UTXO 是否足够支付指定金额
        public boolean hasEnoughAmount(BigDecimal amount) {
            return getAvailableTotalAmount().compareTo(amount) >= 0;
        }

        // 获取桶内可用 UTXO 的总金额
        public BigDecimal getAvailableTotalAmount() {
            bucketLock.lock();
            try {
                return utxos.stream()
                        .filter(utxo -> !utxo.isSpent())
                        .map(UTXO::getAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
            } finally {
                bucketLock.unlock();
            }
        }

        // 从桶中筛选足够支付的 UTXO 组合
        public List<UTXO> selectUTXOsForPayment(BigDecimal targetAmount, boolean preferLarger) {
            bucketLock.lock();
            try {
                List<UTXO> available = utxos.stream()
                        .filter(utxo -> !utxo.isSpent())
                        .collect(Collectors.toList());

                // 根据策略排序
                if (preferLarger) {
                    available.sort(Comparator.comparing(UTXO::getAmount).reversed());
                } else {
                    available.sort(Comparator.comparing(UTXO::getAmount));
                }

                List<UTXO> selected = new ArrayList<>();
                BigDecimal accumulated = BigDecimal.ZERO;

                for (UTXO utxo : available) {
                    selected.add(utxo);
                    accumulated = accumulated.add(utxo.getAmount());

                    if (accumulated.compareTo(targetAmount) >= 0) {
                        break;
                    }
                }

                // 如果未达到目标金额，返回空列表
                return accumulated.compareTo(targetAmount) >= 0 ? selected : Collections.emptyList();
            } finally {
                bucketLock.unlock();
            }
        }

        @Override
        public String toString() {
            return "Bucket[" + minAmount + ", " + maxAmount + "), total=" + totalAmount +
                    ", size=" + utxos.size();
        }
    }

    // 初始化地址的桶集合
    private void initBucketsForAddress(String address) {
        if (!addressBuckets.containsKey(address)) {
            List<UTXOBucket> buckets = new ArrayList<>();
            BigDecimal prevThreshold = BigDecimal.ZERO;

            for (BigDecimal threshold : BUCKET_THRESHOLDS) {
                buckets.add(new UTXOBucket(prevThreshold, threshold));
                prevThreshold = threshold;
            }
            // 添加最后一个无限大的桶
            //buckets.add(new UTXOBucket(prevThreshold, new BigDecimal(Long.MAX_VALUE)));

            // 替换原代码中的最大桶初始化
            buckets.add(new UTXOBucket(prevThreshold, new BigDecimal("1000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"))); // 自定义超大值
            // 或用 null 表示无上限（需同时修改桶的判断逻辑）
            addressBuckets.put(address, buckets);
        }

        // 初始化地址锁
        addressLocks.putIfAbsent(address, new ReentrantLock());
    }

    // 添加 UTXO 到系统
    public void addUTXO(String address, String utxoId, BigDecimal amount) {
        // 验证金额有效性
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("UTXO 金额必须大于零");
        }
        // 初始化地址的桶集合（如果不存在）
        initBucketsForAddress(address);
        // 获取地址锁
        ReentrantLock lock = addressLocks.get(address);
        lock.lock();
        try {
            // 查找对应的桶
            UTXOBucket bucket = findBucketForAmount(address, amount);
            if (bucket == null) {
                throw new IllegalStateException("无法找到合适的桶 for amount: " + amount);
            }
            // 创建并添加 UTXO
            UTXO utxo = new UTXO(utxoId, amount);
            bucket.addUTXO(utxo);
            // 更新地址余额
            addressBalances.put(address,
                    addressBalances.getOrDefault(address, BigDecimal.ZERO).add(amount));
        } finally {
            lock.unlock();
        }
    }

    // 查找金额对应的桶
    private UTXOBucket findBucketForAmount(String address, BigDecimal amount) {
        List<UTXOBucket> buckets = addressBuckets.get(address);
        for (UTXOBucket bucket : buckets) {
            if (amount.compareTo(bucket.minAmount) >= 0 &&
                    amount.compareTo(bucket.maxAmount) < 0) {
                return bucket;
            }
        }
        return null;
    }

    // 获取地址余额
    public BigDecimal getBalance(String address) {
        return addressBalances.getOrDefault(address, BigDecimal.ZERO);
    }

    // 尝试支付指定金额，返回使用的 UTXO 列表（如果支付成功）
    public List<UTXO> attemptPayment(String address, BigDecimal amount, boolean preferLarger) {
        // 验证金额有效性
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("支付金额必须大于零");
        }

        // 检查地址是否存在
        if (!addressBuckets.containsKey(address)) {
            return Collections.emptyList();
        }

        // 获取地址锁
        ReentrantLock lock = addressLocks.get(address);
        lock.lock();
        try {
            // 检查余额是否足够
            BigDecimal balance = getBalance(address);
            if (balance.compareTo(amount) < 0) {
                return Collections.emptyList(); // 余额不足
            }

            List<UTXOBucket> buckets = addressBuckets.get(address);
            List<UTXO> selectedUTXOs = new ArrayList<>();
            BigDecimal remainingAmount = amount;

            // 确定遍历顺序
            int startIndex = preferLarger ? buckets.size() - 1 : 0;
            int endIndex = preferLarger ? -1 : buckets.size();
            int step = preferLarger ? -1 : 1;

            // 遍历桶集合
            for (int i = startIndex;
                 (preferLarger ? i > endIndex : i < endIndex) &&
                         remainingAmount.compareTo(BigDecimal.ZERO) > 0;
                 i += step) {

                UTXOBucket bucket = buckets.get(i);

                // 如果桶内可用金额足够支付剩余金额
                if (bucket.hasEnoughAmount(remainingAmount)) {
                    // 只有当目标金额大于当前桶的最小值时才使用该桶
                    if (remainingAmount.compareTo(bucket.minAmount) >= 0) {
                        List<UTXO> utxos = bucket.selectUTXOsForPayment(remainingAmount, preferLarger);
                        if (!utxos.isEmpty()) {
                            selectedUTXOs.addAll(utxos);

                            // 计算已选择金额
                            BigDecimal selectedAmount = utxos.stream()
                                    .map(UTXO::getAmount)
                                    .reduce(BigDecimal.ZERO, BigDecimal::add);

                            remainingAmount = remainingAmount.subtract(selectedAmount);

                            if (remainingAmount.compareTo(BigDecimal.ZERO) <= 0) {
                                break;
                            }
                        }
                    }
                } else {
                    // 尝试使用桶内所有可用UTXO
                    List<UTXO> availableUTXOs = bucket.getAvailableUTXOs();
                    if (!availableUTXOs.isEmpty()) {
                        selectedUTXOs.addAll(availableUTXOs);

                        // 计算已选择金额
                        BigDecimal selectedAmount = availableUTXOs.stream()
                                .map(UTXO::getAmount)
                                .reduce(BigDecimal.ZERO, BigDecimal::add);

                        remainingAmount = remainingAmount.subtract(selectedAmount);
                    }
                }
            }

            // 如果成功凑够金额，标记 UTXO 为已花费
            if (remainingAmount.compareTo(BigDecimal.ZERO) <= 0) {
                for (UTXO utxo : selectedUTXOs) {
                    utxo.markAsSpent();
                }

                // 计算实际花费金额（可能大于请求金额）
                BigDecimal actualSpent = amount.add(remainingAmount.negate());

                // 更新地址余额
                addressBalances.put(address, balance.subtract(actualSpent));
                return selectedUTXOs;
            }

            return Collections.emptyList(); // 无法凑够金额
        } finally {
            lock.unlock();
        }
    }

    // 尝试支付指定金额，返回使用的 UTXO 列表（如果支付成功）
    public List<UTXO> attemptPayment1(String address, BigDecimal amount, boolean preferLarger) {
        // 验证金额有效性
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("支付金额必须大于零");
        }

        // 检查地址是否存在
        if (!addressBuckets.containsKey(address)) {
            return Collections.emptyList();
        }

        // 获取地址锁
        ReentrantLock lock = addressLocks.get(address);
        lock.lock();
        try {
            // 检查余额是否足够
            BigDecimal balance = getBalance(address);
            if (balance.compareTo(amount) < 0) {
                return Collections.emptyList(); // 余额不足
            }

            List<UTXOBucket> buckets = addressBuckets.get(address);
            List<UTXO> selectedUTXOs = new ArrayList<>();
            BigDecimal remainingAmount = amount;

            // 确定遍历顺序
            int startIndex = preferLarger ? buckets.size() - 1 : 0;
            int endIndex = preferLarger ? -1 : buckets.size();
            int step = preferLarger ? -1 : 1;

            // 遍历桶集合
            for (int i = startIndex;
                 (preferLarger ? i > endIndex : i < endIndex) &&
                         remainingAmount.compareTo(BigDecimal.ZERO) > 0;
                 i += step) {

                UTXOBucket bucket = buckets.get(i);

                // 如果桶内可用金额足够支付剩余金额
                if (bucket.hasEnoughAmount(remainingAmount)) {
                    List<UTXO> utxos = bucket.selectUTXOsForPayment(remainingAmount, preferLarger);
                    if (!utxos.isEmpty()) {
                        selectedUTXOs.addAll(utxos);

                        // 计算已选择金额
                        BigDecimal selectedAmount = utxos.stream()
                                .map(UTXO::getAmount)
                                .reduce(BigDecimal.ZERO, BigDecimal::add);

                        remainingAmount = remainingAmount.subtract(selectedAmount);

                        if (remainingAmount.compareTo(BigDecimal.ZERO) <= 0) {
                            break;
                        }
                    }
                } else {
                    // 尝试使用桶内所有可用UTXO
                    List<UTXO> availableUTXOs = bucket.getAvailableUTXOs();
                    if (!availableUTXOs.isEmpty()) {
                        selectedUTXOs.addAll(availableUTXOs);

                        // 计算已选择金额
                        BigDecimal selectedAmount = availableUTXOs.stream()
                                .map(UTXO::getAmount)
                                .reduce(BigDecimal.ZERO, BigDecimal::add);

                        remainingAmount = remainingAmount.subtract(selectedAmount);
                    }
                }
            }

            // 如果成功凑够金额，标记 UTXO 为已花费
            if (remainingAmount.compareTo(BigDecimal.ZERO) <= 0) {
                for (UTXO utxo : selectedUTXOs) {
                    utxo.markAsSpent();
                }

                // 计算实际花费金额（可能大于请求金额）
                BigDecimal actualSpent = amount.add(remainingAmount.negate());

                // 更新地址余额
                addressBalances.put(address, balance.subtract(actualSpent));
                return selectedUTXOs;
            }

            return Collections.emptyList(); // 无法凑够金额
        } finally {
            lock.unlock();
        }
    }

    // 重载方法，默认优先使用大额UTXO
    public List<UTXO> attemptPayment(String address, BigDecimal amount) {
        return attemptPayment(address, amount, true);
    }


    public List<UTXO> attemptPaymentMini(String address, BigDecimal amount) {
        // 验证金额有效性
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("支付金额必须大于零");
        }

        // 检查地址是否存在
        if (!addressBuckets.containsKey(address)) {
            return Collections.emptyList();
        }

        // 获取地址锁
        ReentrantLock lock = addressLocks.get(address);
        lock.lock();
        try {
            // 检查余额是否足够
            BigDecimal balance = getBalance(address);
            if (balance.compareTo(amount) < 0) {
                return Collections.emptyList(); // 余额不足
            }

            // 策略1：优先使用小额UTXO（清理碎片），但可能产生更多找零
            List<UTXO> smallFirstResult = collectUTXOs(address, amount, false);
            BigDecimal smallFirstChange = calculateChange(smallFirstResult, amount);

            // 策略2：优先使用大额UTXO（减少找零），但可能保留碎片
            List<UTXO> largeFirstResult = collectUTXOs(address, amount, true);
            BigDecimal largeFirstChange = calculateChange(largeFirstResult, amount);

            // 选择最优策略：找零更小且UTXO数量更少（手续费更低）
            List<UTXO> optimalResult;
            if (smallFirstChange.compareTo(largeFirstChange) < 0 ||
                    (smallFirstChange.compareTo(largeFirstChange) == 0 &&
                            smallFirstResult.size() < largeFirstResult.size())) {
                optimalResult = smallFirstResult;
            } else {
                optimalResult = largeFirstResult;
            }

            // 如果找到合适的UTXO组合，标记为已花费并更新余额
            if (!optimalResult.isEmpty()) {
                for (UTXO utxo : optimalResult) {
                    utxo.markAsSpent();
                    //不标记直接删除
                    addressBuckets.get(address).remove(utxo);
                }

                // 计算实际花费金额和找零
                BigDecimal actualSpent = optimalResult.stream()
                        .map(UTXO::getAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal change = actualSpent.subtract(amount);

                // 更新地址余额
                addressBalances.put(address, balance.subtract(actualSpent));

                // 如果有找零，创建新UTXO（保留在最小的合适桶中）
                if (change.compareTo(BigDecimal.ZERO) > 0) {
                    addUTXO(address, UUID.randomUUID().toString(), change);
                }

                return optimalResult;
            }

            return Collections.emptyList(); // 无法凑够金额
        } finally {
            lock.unlock();
        }
    }





    // 计算找零金额
    private BigDecimal calculateChange(List<UTXO> utxos, BigDecimal targetAmount) {
        if (utxos.isEmpty()) return BigDecimal.ZERO;
        BigDecimal total = utxos.stream()
                .map(UTXO::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return total.subtract(targetAmount);
    }
    // 收集UTXO的核心逻辑（支持大小额优先策略）
    private List<UTXO> collectUTXOs(String address, BigDecimal amount, boolean preferLarger) {
        List<UTXOBucket> buckets = addressBuckets.get(address);
        List<UTXO> selectedUTXOs = new ArrayList<>();
        BigDecimal remainingAmount = amount;

        // 确定遍历顺序：大额优先或小额优先
        int startIndex = preferLarger ? buckets.size() - 1 : 0;
        int endIndex = preferLarger ? -1 : buckets.size();
        int step = preferLarger ? -1 : 1;

        // 遍历桶集合
        for (int i = startIndex;
             (preferLarger ? i > endIndex : i < endIndex) &&
                     remainingAmount.compareTo(BigDecimal.ZERO) > 0;
             i += step) {

            UTXOBucket bucket = buckets.get(i);

            // 如果桶内可用金额足够支付剩余金额
            if (bucket.hasEnoughAmount(remainingAmount)) {
                List<UTXO> utxos = bucket.selectUTXOsForPayment(remainingAmount, preferLarger);
                if (!utxos.isEmpty()) {
                    selectedUTXOs.addAll(utxos);
                    remainingAmount = remainingAmount.subtract(
                            utxos.stream().map(UTXO::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
                }
            } else {
                // 桶内金额不足，使用所有可用UTXO
                List<UTXO> availableUTXOs = bucket.getAvailableUTXOs();
                if (!availableUTXOs.isEmpty()) {
                    selectedUTXOs.addAll(availableUTXOs);
                    remainingAmount = remainingAmount.subtract(
                            availableUTXOs.stream().map(UTXO::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
                }
            }
        }

        return remainingAmount.compareTo(BigDecimal.ZERO) <= 0 ? selectedUTXOs : Collections.emptyList();
    }





    // 获取地址的所有 UTXO 统计信息
    public Map<String, Integer> getUTXOsDistribution(String address) {
        Map<String, Integer> distribution = new LinkedHashMap<>();
        List<UTXOBucket> buckets = addressBuckets.getOrDefault(address, Collections.emptyList());

        for (UTXOBucket bucket : buckets) {
            String bucketName = bucket.minAmount + "~" + bucket.maxAmount;
            int count = (int) bucket.getAvailableUTXOs().stream()
                    .filter(utxo -> !utxo.isSpent())
                    .count();
            distribution.put(bucketName, count);
        }
        return distribution;
    }

    // 回收长期未使用的UTXO（冷数据归档准备）
    public List<UTXO> reclaimOldUTXOs(String address, long olderThanTimestamp) {
        if (!addressBuckets.containsKey(address)) {
            return Collections.emptyList();
        }

        ReentrantLock lock = addressLocks.get(address);
        lock.lock();
        try {
            List<UTXO> oldUTXOs = new ArrayList<>();
            List<UTXOBucket> buckets = addressBuckets.get(address);

            for (UTXOBucket bucket : buckets) {
                List<UTXO> toRemove = bucket.getAvailableUTXOs().stream()
                        .filter(utxo -> utxo.getTimestamp() < olderThanTimestamp)
                        .collect(Collectors.toList());

                for (UTXO utxo : toRemove) {
                    bucket.removeUTXO(utxo);
                }

                oldUTXOs.addAll(toRemove);
            }

            // 更新地址余额
            if (!oldUTXOs.isEmpty()) {
                BigDecimal reclaimedAmount = oldUTXOs.stream()
                        .map(UTXO::getAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

                addressBalances.put(address,
                        addressBalances.getOrDefault(address, BigDecimal.ZERO).subtract(reclaimedAmount));
            }

            return oldUTXOs;
        } finally {
            lock.unlock();
        }
    }



    // 示例使用
    public static void main(String[] args) {
        OptimizedUTXOMangementSystem system = new OptimizedUTXOMangementSystem();
        String address = "user123";

        // 添加一些 UTXO
        system.addUTXO(address, "utxo0", new BigDecimal("0.005"));
        system.addUTXO(address, "utxo1", new BigDecimal("0.005"));
        system.addUTXO(address, "utxo2", new BigDecimal("0.01"));
        system.addUTXO(address, "utxo3", new BigDecimal("0.5"));
        system.addUTXO(address, "utxo4", new BigDecimal("10"));
        system.addUTXO(address, "utxo5", new BigDecimal("10"));
        system.addUTXO(address, "utxo6", new BigDecimal("10"));
        system.addUTXO(address, "utxo7", new BigDecimal("500"));

        // 打印余额
        System.out.println("地址余额: " + system.getBalance(address));

        // 尝试支付
        //从这个地址找出 UTXO
        List<UTXO> result = system.attemptPaymentMini(address, new BigDecimal("20.815"));
        if (!result.isEmpty()) {
            System.out.println("支付成功! 使用的 UTXO:");
            for (UTXO utxo : result) {
                System.out.println("- " + utxo);
            }
            //找零给自己

            System.out.println("剩余余额: " + system.getBalance(address));
        } else {
            System.out.println("支付失败: 余额不足或无法凑够金额");
        }

        // 打印 UTXO 分布
        System.out.println("\nUTXO 分布:");
        system.getUTXOsDistribution(address).forEach((bucket, count) -> {
            System.out.println(bucket + ": " + count + " 个");
        });

        // 尝试回收30天前的UTXO（这里使用当前时间+1秒，所以不会回收任何UTXO）
        long thirtyDaysAgo = System.currentTimeMillis() + 1000;
        List<UTXO> reclaimed = system.reclaimOldUTXOs(address, thirtyDaysAgo);
        System.out.println("\n回收的UTXO数量: " + reclaimed.size());
        //遍历回收
        for (UTXO utxo : reclaimed) {
            System.out.println("- " + utxo);
        }
        //回收余额
        System.out.println("回收余额: " + system.getBalance(address));
    }
}