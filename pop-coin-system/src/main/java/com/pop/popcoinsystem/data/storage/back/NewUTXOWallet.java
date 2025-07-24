package com.pop.popcoinsystem.data.storage.back;

import java.util.*;
import java.util.stream.Collectors;

public class NewUTXOWallet {
    private static final long[] BUCKET_UPPER_LIMITS = {10L, 100L, 1000L, 10000L, 100000L, 1000000L,10000000L, Long.MAX_VALUE};
    private static final int MAX_UTXOS_PER_LAYER = 5000;

    private final Map<String, BaseUTXO> baseUTXOs;
    private final List<AmountBucket> buckets;

    // 新增：UTXO状态枚举
    public enum UTXOStatus {
        UNSPENT,       // 未花费（可被选中）
        PENDING_SPEND,  // 待确认花费（不可被选中）
        SELECTED       // 临时选中（用于多轮查找）
    }

    public NewUTXOWallet() {
        baseUTXOs = new HashMap<>();
        buckets = new ArrayList<>();
        for (int i = 0; i < BUCKET_UPPER_LIMITS.length; i++) {
            buckets.add(new AmountBucket(i, BUCKET_UPPER_LIMITS[i]));
        }
    }

    public void addUTXO(String txId, int vout, long value, String script) {
        String utxoKey = txId + "-" + vout;
        if (baseUTXOs.containsKey(utxoKey)) {
            throw new IllegalArgumentException("UTXO already exists: " + utxoKey);
        }

        BaseUTXO baseUTXO = new BaseUTXO(txId, vout, value, script);
        baseUTXOs.put(utxoKey, baseUTXO);

        int bucketIndex = findBucketIndex(value);
        buckets.get(bucketIndex).addUTXO(txId, vout, value);
    }

    public void removeUTXO(String txId, int vout) {
        String utxoKey = txId + "-" + vout;
        BaseUTXO utxo = baseUTXOs.remove(utxoKey);
        if (utxo == null) {
            return;
        }

        int bucketIndex = findBucketIndex(utxo.value);
        buckets.get(bucketIndex).removeUTXO(txId, vout, utxo.value);
    }

    public BaseUTXO getUTXO(String txId, int vout) {
        return baseUTXOs.get(txId + "-" + vout);
    }

    public long getTotalBalance() {
        return buckets.stream().mapToLong(AmountBucket::getTotalValue).sum();
    }

    private int findBucketIndex(long value) {
        for (int i = 0; i < BUCKET_UPPER_LIMITS.length; i++) {
            if (value <= BUCKET_UPPER_LIMITS[i]) {
                return i;
            }
        }
        return BUCKET_UPPER_LIMITS.length - 1;
    }

    // 新增：打印钱包内部结构的方法
    public void printWalletStructure() {
        System.out.println("Wallet Structure:");
        for (int i = 0; i < buckets.size(); i++) {
            AmountBucket bucket = buckets.get(i);
            long lowerLimit = i == 0 ? 0 : BUCKET_UPPER_LIMITS[i-1] + 1;
            long upperLimit = BUCKET_UPPER_LIMITS[i];
            String limitStr = upperLimit == Long.MAX_VALUE ?
                    lowerLimit + "+" :
                    lowerLimit + " - " + upperLimit;

            System.out.printf("  Bucket %d [%s]: %d UTXOs, Total Value = %d%n",
                    i, limitStr, bucket.getTotalUTXOs(), bucket.getTotalValue());

            List<UTXOLayer> layers = bucket.getLayers();
            for (int j = 0; j < layers.size(); j++) {
                UTXOLayer layer = layers.get(j);
                System.out.printf("    Layer %d: %d UTXOs, Total Value = %d%n",
                        j, layer.getUTXOCount(), layer.getTotalValue());

                List<ManagedUTXO> utxos = layer.getUTXOs();
                for (ManagedUTXO utxo : utxos) {
                    System.out.printf("      UTXO: %s-%d, Value = %d%n",
                            utxo.getTxId(), utxo.getVout(), utxo.getValue());
                }
            }
        }
    }
    public List<BaseUTXO> selectOptimalUTXOs(long targetAmount) {
        // 1. 检查总余额（直接用桶的总和，避免遍历所有UTXO）
        long availableBalance = getTotalBalance(); // 复用桶的totalValue总和，O(1)
        if (availableBalance < targetAmount) {
            System.out.println("余额不足，目标：" + targetAmount + "，可用：" + availableBalance);
            return Collections.emptyList();
        }

        // 2. 策略1：在目标金额所在桶中查找"恰好匹配"的UTXO
        int targetBucketIndex = findBucketIndex(targetAmount);
        BaseUTXO exactMatch = findExactMatchInBucket(targetAmount, targetBucketIndex);
        if (exactMatch != null) {
            exactMatch.setStatus(UTXOStatus.PENDING_SPEND);
            updateManagedUTXOStatus(exactMatch, UTXOStatus.PENDING_SPEND);
            return Collections.singletonList(exactMatch);
        }

        // 3. 策略2：基于桶结构组合UTXO（仅处理相关桶，避免全量遍历）
        List<BaseUTXO> candidateUTXOs = collectRelevantUTXOs(targetAmount, targetBucketIndex);
        if (!candidateUTXOs.isEmpty()) {
            CombinationResult optimalCombination = findOptimalCombination(candidateUTXOs, targetAmount);
            if (optimalCombination != null && optimalCombination.total >= targetAmount) {
                for (BaseUTXO utxo : optimalCombination.utxos) {
                    utxo.setStatus(UTXOStatus.PENDING_SPEND);
                    updateManagedUTXOStatus(utxo, UTXOStatus.PENDING_SPEND);
                }
                return optimalCombination.utxos;
            }
        }

        // 4. 策略3：在大额桶中找最小的超额UTXO（避免全量找最大UTXO）
        BaseUTXO smallestLargerUTXO = findSmallestLargerUTXO(targetAmount, targetBucketIndex);
        if (smallestLargerUTXO != null) {
            smallestLargerUTXO.setStatus(UTXOStatus.PENDING_SPEND);
            updateManagedUTXOStatus(smallestLargerUTXO, UTXOStatus.PENDING_SPEND);
            return Collections.singletonList(smallestLargerUTXO);
        }

        return Collections.emptyList();
    }

    // 仅在目标桶中查找金额恰好等于target的UTXO（避免全量遍历）
    private BaseUTXO findExactMatchInBucket(long target, int bucketIndex) {
        if (bucketIndex < 0 || bucketIndex >= buckets.size()) return null;
        AmountBucket bucket = buckets.get(bucketIndex);
        for (UTXOLayer layer : bucket.getLayers()) {
            for (ManagedUTXO mUtxo : layer.getUTXOs()) {
                if (mUtxo.getStatus() == UTXOStatus.UNSPENT && mUtxo.getValue() == target) {
                    return getUTXO(mUtxo.getTxId(), mUtxo.getVout());
                }
            }
        }
        return null;
    }

    // 仅收集与目标金额相关的UTXO（限制桶范围，避免全量遍历）
    private List<BaseUTXO> collectRelevantUTXOs(long target, int targetBucketIndex) {
        List<BaseUTXO> candidates = new ArrayList<>();
        // 1. 包含目标桶及以下的小额桶（最多往前查2个桶，避免范围过大）
        int startBucket = Math.max(0, targetBucketIndex - 2);
        // 2. 包含目标桶及以上的大额桶（最多往后查2个桶）
        int endBucket = Math.min(buckets.size() - 1, targetBucketIndex + 2);

        for (int i = startBucket; i <= endBucket; i++) {
            AmountBucket bucket = buckets.get(i);
            for (UTXOLayer layer : bucket.getLayers()) {
                for (ManagedUTXO mUtxo : layer.getUTXOs()) {
                    if (mUtxo.getStatus() == UTXOStatus.UNSPENT) {
                        BaseUTXO baseUTXO = getUTXO(mUtxo.getTxId(), mUtxo.getVout());
                        if (baseUTXO != null) {
                            candidates.add(baseUTXO);
                        }
                    }
                }
            }
        }
        // 按金额升序排序，优先用小额UTXO
        candidates.sort(Comparator.comparingLong(BaseUTXO::getValue));
        return candidates;
    }

    // 在大额桶中找最小的超额UTXO（仅遍历目标桶以上的桶）
    private BaseUTXO findSmallestLargerUTXO(long target, int targetBucketIndex) {
        BaseUTXO result = null;
        // 从目标桶的下一个桶开始查找（大额桶）
        for (int i = targetBucketIndex + 1; i < buckets.size(); i++) {
            AmountBucket bucket = buckets.get(i);
            for (UTXOLayer layer : bucket.getLayers()) {
                for (ManagedUTXO mUtxo : layer.getUTXOs()) {
                    if (mUtxo.getStatus() == UTXOStatus.UNSPENT && mUtxo.getValue() >= target) {
                        BaseUTXO baseUTXO = getUTXO(mUtxo.getTxId(), mUtxo.getVout());
                        if (baseUTXO != null && (result == null || mUtxo.getValue() < result.getValue())) {
                            result = baseUTXO;
                        }
                    }
                }
            }
            // 找到第一个满足条件的桶后，若已找到结果，可提前退出（桶是按金额递增的）
            if (result != null) break;
        }
        return result;
    }





    // 用于保存UTXO组合结果的类
    private static class CombinationResult {
        List<BaseUTXO> utxos;
        long total;
        int count;

        public CombinationResult(List<BaseUTXO> utxos, long total) {
            this.utxos = utxos;
            this.total = total;
            this.count = utxos.size();
        }
    }

    // 使用回溯法找到最优UTXO组合
    private CombinationResult findOptimalCombination(List<BaseUTXO> candidates, long target) {
        // 按金额升序排序，优先使用小额UTXO
        candidates.sort(Comparator.comparingLong(BaseUTXO::getValue));

        List<BaseUTXO> currentCombination = new ArrayList<>();
        CombinationResult[] bestResult = new CombinationResult[1];

        backtrack(candidates, target, 0, currentCombination, 0, bestResult);

        return bestResult[0];
    }

    // 回溯法辅助函数
    private void backtrack(List<BaseUTXO> candidates, long target, int start,
                           List<BaseUTXO> current, long currentSum,
                           CombinationResult[] bestResult) {
        // 如果当前总和超过目标，剪枝
        if (currentSum > target * 1.5) { // 设置上限为目标的1.5倍，避免过多零钱
            return;
        }

        // 如果当前组合是更优解，更新最优结果
        if (currentSum >= target) {
            CombinationResult currentResult = new CombinationResult(new ArrayList<>(current), currentSum);
            if (bestResult[0] == null) {
                bestResult[0] = currentResult;
            } else {
                // 比较当前结果与最优结果
                if (currentResult.total < bestResult[0].total ||
                        (currentResult.total == bestResult[0].total && currentResult.count < bestResult[0].count)) {
                    bestResult[0] = currentResult;
                }
            }
            return;
        }

        // 继续尝试添加UTXO
        for (int i = start; i < candidates.size(); i++) {
            BaseUTXO utxo = candidates.get(i);
            current.add(utxo);
            backtrack(candidates, target, i + 1, current, currentSum + utxo.getValue(), bestResult);
            current.remove(current.size() - 1);
        }
    }

    /**
     * 同步更新 BaseUTXO 和对应的 ManagedUTXO 的状态
     */
    private void updateManagedUTXOStatus(BaseUTXO baseUTXO, UTXOStatus status) {
        // 更新 BaseUTXO 状态（已在调用处更新，此处可省略）
        // baseUTXO.setStatus(status);

        // 查找对应的 ManagedUTXO 并更新状态
        int bucketIndex = findBucketIndex(baseUTXO.getValue());
        AmountBucket bucket = buckets.get(bucketIndex);

        for (UTXOLayer layer : bucket.getLayers()) {
            for (ManagedUTXO mUtxo : layer.getUTXOs()) {
                if (mUtxo.getTxId().equals(baseUTXO.getTxId()) &&
                        mUtxo.getVout() == baseUTXO.getVout()) {
                    mUtxo.setStatus(status);
                    break;
                }
            }
        }
    }

    // 贪心近似算法：优先选择大额UTXO，同时控制零钱
    private List<BaseUTXO> greedyApproach(List<BaseUTXO> candidates, long target) {
        List<BaseUTXO> selected = new ArrayList<>();
        long sum = 0;

        // 尝试从大到小选择UTXO
        for (BaseUTXO utxo : candidates) {
            if (sum + utxo.getValue() <= target * 1.5) { // 限制总和不超过目标的150%
                selected.add(utxo);
                sum += utxo.getValue();
                if (sum >= target) break;
            }
        }

        // 如果无法满足目标，再添加小额UTXO
        if (sum < target) {
            for (BaseUTXO utxo : candidates) {
                if (!selected.contains(utxo)) {
                    selected.add(utxo);
                    sum += utxo.getValue();
                    if (sum >= target) break;
                }
            }
        }

        return selected;
    }












    /**
     * 将目标金额拆解为子目标（按金额桶区间拆解，从小额到大额）
     * 示例：64 → [4, 60]（4对应0-10桶，60对应10-100桶）
     */
    private List<Long> splitTarget(long targetAmount) {
        // 检查是否存在恰好等于或大于目标金额的UTXO
        for (BaseUTXO utxo : baseUTXOs.values()) {
            if (utxo.getStatus() == UTXOStatus.UNSPENT && utxo.getValue() >= targetAmount) {
                return Collections.singletonList(targetAmount); // 直接用一个UTXO覆盖
            }
        }

        // 原有拆解逻辑（但避免产生过小的子目标）
        List<Long> subTargets = new ArrayList<>();
        long remaining = targetAmount;

        for (int i = 0; i < BUCKET_UPPER_LIMITS.length; i++) {
            if (remaining <= 0) break;

            long bucketMax = BUCKET_UPPER_LIMITS[i];
            long bucketMin = (i == 0) ? 0 : BUCKET_UPPER_LIMITS[i-1] + 1;

            // 避免产生过小的子目标（例如 < 余额的10%）
            long minSubTarget = Math.max(bucketMin, targetAmount / 10);
            long subTarget = Math.min(remaining, bucketMax);

            if (subTarget >= minSubTarget) {
                subTargets.add(subTarget);
                remaining -= subTarget;
            }
        }

        if (remaining > 0) {
            subTargets.add(remaining);
        }

        Collections.reverse(subTargets);
        return subTargets;
    }

    /**
     * 在指定桶及以上查找满足子目标的UTXO（贪心策略：优先选小额UTXO，避免浪费大额）
     * @param subTarget 子目标金额
     * @param startBucketIndex 起始桶索引
     * @return 满足子目标的UTXO列表
     */
    private List<BaseUTXO> findUTXOsInBucket(long subTarget, int startBucketIndex) {
        List<BaseUTXO> result = new ArrayList<>();
        long sum = 0;

        // 从指定桶开始，向上遍历（大额桶）
        for (int i = startBucketIndex; i < buckets.size(); i++) {
            AmountBucket bucket = buckets.get(i);
            // 桶内UTXO按金额升序排序（优先用小额，避免浪费）
            List<ManagedUTXO> bucketUTXOs = new ArrayList<>(bucket.getLayers().stream()
                    .flatMap(layer -> layer.getUTXOs().stream())
                    .filter(utxo -> utxo.getStatus() == UTXOStatus.UNSPENT)
                    .sorted(Comparator.comparingLong(ManagedUTXO::getValue))
                    .collect(Collectors.toList()));

            // 累加桶内UTXO，直到满足子目标
            for (ManagedUTXO mUtxo : bucketUTXOs) {
                BaseUTXO baseUTXO = getUTXO(mUtxo.getTxId(), mUtxo.getVout());
                if (baseUTXO == null) continue;

                // 标记UTXO为已选中状态
                baseUTXO.setStatus(UTXOStatus.SELECTED);
                mUtxo.setStatus(UTXOStatus.SELECTED);

                result.add(baseUTXO);
                sum += baseUTXO.getValue();
                if (sum >= subTarget) {
                    return result; // 满足子目标，返回
                }
            }
        }
        return result; // 未满足子目标，返回已找到的
    }








    // 辅助方法：计算 UTXO 列表的总金额
    private long sumValues(List<BaseUTXO> utxos) {
        return utxos.stream().mapToLong(BaseUTXO::getValue).sum();
    }

    /**
     * 计算当前可用余额（仅统计未花费的UTXO）
     */
    public long calculateAvailableBalance() {
        return baseUTXOs.values().stream()
                .filter(utxo -> utxo.getStatus() == UTXOStatus.UNSPENT)
                .mapToLong(BaseUTXO::getValue)
                .sum();
    }


    /**
     * 标记选中的UTXO为“待确认花费”
     * @param selectedUTXOs 选中的UTXO列表
     */
    public void markAsPendingSpend(List<BaseUTXO> selectedUTXOs) {
        for (BaseUTXO utxo : selectedUTXOs) {
            String utxoKey = utxo.getTxId() + "-" + utxo.getVout();
            // 更新BaseUTXO状态
            utxo.setStatus(UTXOStatus.PENDING_SPEND);
            // 同步更新AmountBucket中对应的ManagedUTXO状态
            int bucketIndex = findBucketIndex(utxo.getValue());
            AmountBucket bucket = buckets.get(bucketIndex);
            for (UTXOLayer layer : bucket.layers) {
                for (ManagedUTXO mUtxo : layer.utxos) {
                    if (mUtxo.getTxId().equals(utxo.getTxId()) && mUtxo.getVout() == utxo.getVout()) {
                        mUtxo.setStatus(UTXOStatus.PENDING_SPEND);
                        break;
                    }
                }
            }
        }
    }

    /**
     * 确认花费，移除待确认的UTXO
     * @param pendingUTXOs 待确认的UTXO列表
     */
    public void confirmSpend(List<BaseUTXO> pendingUTXOs) {
        for (BaseUTXO utxo : pendingUTXOs) {
            removeUTXO(utxo.getTxId(), utxo.getVout());  // 调用原有remove方法从钱包中移除
        }
    }

    /**
     * 取消花费，将待确认的UTXO恢复为“未花费”
     * @param pendingUTXOs 待确认的UTXO列表
     */
    public void cancelPendingSpend(List<BaseUTXO> pendingUTXOs) {
        for (BaseUTXO utxo : pendingUTXOs) {
            String utxoKey = utxo.getTxId() + "-" + utxo.getVout();
            // 恢复BaseUTXO状态
            utxo.setStatus(UTXOStatus.UNSPENT);
            // 同步恢复ManagedUTXO状态
            int bucketIndex = findBucketIndex(utxo.getValue());
            AmountBucket bucket = buckets.get(bucketIndex);
            for (UTXOLayer layer : bucket.layers) {
                for (ManagedUTXO mUtxo : layer.utxos) {
                    if (mUtxo.getTxId().equals(utxo.getTxId()) && mUtxo.getVout() == utxo.getVout()) {
                        mUtxo.setStatus(UTXOStatus.UNSPENT);
                        break;
                    }
                }
            }
        }
    }




    public static class BaseUTXO {
        private final String txId;
        private final int vout;
        private final long value;
        private final String script;
        private UTXOStatus status;  // 新增状态

        public BaseUTXO(String txId, int vout, long value, String script) {
            this.txId = txId;
            this.vout = vout;
            this.value = value;
            this.script = script;
            this.status = UTXOStatus.UNSPENT;  // 初始为未花费

        }
        // 新增：状态相关getter和setter
        public UTXOStatus getStatus() { return status; }
        public void setStatus(UTXOStatus status) { this.status = status; }
        public String getTxId() {
            return txId;
        }

        public int getVout() {
            return vout;
        }

        public long getValue() {
            return value;
        }

        public String getScript() {
            return script;
        }
    }

    public static class ManagedUTXO {
        private final String txId;
        private final int vout;
        private final long value;
        private UTXOStatus status;  // 新增状态

        public ManagedUTXO(String txId, int vout, long value) {
            this.txId = txId;
            this.vout = vout;
            this.value = value;
            this.status = UTXOStatus.UNSPENT;  // 初始为未花费
        }

        // 新增：状态相关getter和setter
        public UTXOStatus getStatus() { return status; }
        public void setStatus(UTXOStatus status) { this.status = status; }
        public String getTxId() {
            return txId;
        }

        public int getVout() {
            return vout;
        }

        public long getValue() {
            return value;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ManagedUTXO that = (ManagedUTXO) o;
            return vout == that.vout && value == that.value && txId.equals(that.txId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(txId, vout, value);
        }
    }

    private static class AmountBucket {
        private final int index;
        private final long upperLimit;
        private long totalValue;
        private int totalUTXOs;
        private final List<UTXOLayer> layers;
        private final Map<String, UTXOLayer> utxoLocationMap;

        public AmountBucket(int index, long upperLimit) {
            this.index = index;
            this.upperLimit = upperLimit;
            this.totalValue = 0;
            this.totalUTXOs = 0;
            this.layers = new ArrayList<>();
            this.utxoLocationMap = new HashMap<>();
            layers.add(new UTXOLayer());
        }

        public void addUTXO(String txId, int vout, long value) {
            String utxoKey = txId + "-" + vout;
            if (utxoLocationMap.containsKey(utxoKey)) {
                throw new IllegalArgumentException("UTXO already exists in bucket: " + utxoKey);
            }

            UTXOLayer targetLayer = findSuitableLayer();
            targetLayer.addUTXO(txId, vout, value);
            utxoLocationMap.put(utxoKey, targetLayer);

            totalValue += value;
            totalUTXOs++;
        }

        public void removeUTXO(String txId, int vout, long value) {
            String utxoKey = txId + "-" + vout;
            UTXOLayer layer = utxoLocationMap.remove(utxoKey);
            if (layer == null) {
                return;
            }

            layer.removeUTXO(txId, vout, value);
            totalValue -= value;
            totalUTXOs--;

            // 清理空层，但保留至少一层
            if (layer.isEmpty() && layers.size() > 1) {
                layers.remove(layer);
            }
        }

        public long getTotalValue() {
            return totalValue;
        }

        public int getTotalUTXOs() {
            return totalUTXOs;
        }

        // 新增：获取层列表的方法
        public List<UTXOLayer> getLayers() {
            return layers;
        }

        private UTXOLayer findSuitableLayer() {
            UTXOLayer lastLayer = layers.get(layers.size() - 1);
            if (lastLayer.getUTXOCount() < MAX_UTXOS_PER_LAYER) {
                return lastLayer;
            }

            UTXOLayer newLayer = new UTXOLayer();
            layers.add(newLayer);
            return newLayer;
        }
    }

    private static class UTXOLayer {
        private final List<ManagedUTXO> utxos;
        private long totalValue;

        public UTXOLayer() {
            this.utxos = new ArrayList<>(MAX_UTXOS_PER_LAYER);
            this.totalValue = 0;
        }

        public void addUTXO(String txId, int vout, long value) {
            utxos.add(new ManagedUTXO(txId, vout, value));
            totalValue += value;
        }

        public void removeUTXO(String txId, int vout, long value) {
            utxos.removeIf(utxo -> utxo.getTxId().equals(txId) && utxo.getVout() == vout);
            totalValue -= value;
        }

        public int getUTXOCount() {
            return utxos.size();
        }

        public long getTotalValue() {
            return totalValue;
        }

        public boolean isEmpty() {
            return utxos.isEmpty();
        }

        // 新增：获取UTXO列表的方法
        public List<ManagedUTXO> getUTXOs() {
            return utxos;
        }
    }

    public static void main(String[] args) {
        NewUTXOWallet wallet = new NewUTXOWallet();
        // 添加测试UTXO（覆盖不同金额区间）
        wallet.addUTXO("tx1", 3, 1L, "script");
        wallet.addUTXO("tx1", 0, 5L, "script");
        wallet.addUTXO("tx1", 1, 9L, "script");
        wallet.addUTXO("tx2", 0, 16L, "script");
        wallet.addUTXO("tx3", 0, 50L, "script");
        wallet.addUTXO("tx4", 1, 100L, "script");
        wallet.addUTXO("tx4", 0, 400L, "script");
        wallet.addUTXO("tx5", 0, 500L, "script");
        wallet.addUTXO("tx6", 0, 5000L, "script");

        System.out.println("初始总余额：" + wallet.getTotalBalance());
        System.out.println("可用余额（未花费）：" + wallet.calculateAvailableBalance());

        // 再次打印钱包结构
        wallet.printWalletStructure();

        // 测试1：选择560的最优组合
        long target = 68L;
        List<BaseUTXO> selected = wallet.selectOptimalUTXOs(target);
        System.out.println("\n选中的UTXO（目标：" + target + "）：");
        selected.forEach(utxo -> System.out.println(
                utxo.getTxId() + "-" + utxo.getVout() + "，金额：" + utxo.getValue()
        ));
        // 输出：500（tx5-0） + 50（tx3-0） + 16（tx2-0）= 566 ≥ 560

        // 测试2：标记为待确认
        wallet.markAsPendingSpend(selected);
        System.out.println("\n标记待确认后，可用余额：" + wallet.calculateAvailableBalance());  // 5980 - 566 = 5414

        // 测试3：确认花费（移除UTXO）
        wallet.confirmSpend(selected);
        System.out.println("\n确认花费后，总余额：" + wallet.getTotalBalance());  // 5980 - 566 = 5414
        System.out.println("确认花费后，可用余额：" + wallet.calculateAvailableBalance());  // 5414（剩余UTXO均为未花费）
        // 再次打印钱包结构
        wallet.printWalletStructure();




        long target2 = 90L;
        List<BaseUTXO> selected2 = wallet.selectOptimalUTXOs(target2);
        System.out.println("\n选中的UTXO（目标：" + target2 + "）：");
        selected2.forEach(utxo -> System.out.println(
                utxo.getTxId() + "-" + utxo.getVout() + "，金额：" + utxo.getValue()
        ));
        // 输出：500（tx5-0） + 50（tx3-0） + 16（tx2-0）= 566 ≥ 560

        // 测试2：标记为待确认
        wallet.markAsPendingSpend(selected2);
        System.out.println("\n标记待确认后，可用余额：" + wallet.calculateAvailableBalance());  // 5980 - 566 = 5414

        // 测试3：确认花费（移除UTXO）
        wallet.confirmSpend(selected2);
        System.out.println("\n确认花费后，总余额：" + wallet.getTotalBalance());  // 5980 - 566 = 5414
        System.out.println("确认花费后，可用余额：" + wallet.calculateAvailableBalance());  // 5414（剩余UTXO均为未花费）
        // 再次打印钱包结构
        wallet.printWalletStructure();






       /*
        UTXOWallet wallet = new UTXOWallet();
        wallet.addUTXO("tx1", 3, 16L, "script1");
        wallet.addUTXO("tx1", 2, 9L, "script1");
        wallet.addUTXO("tx1", 0, 5L, "script1");
        wallet.addUTXO("tx2", 1, 50L, "script2");
        wallet.addUTXO("tx3", 1, 400L, "script3");
        wallet.addUTXO("tx3", 2, 500L, "script3");
        wallet.addUTXO("tx4", 3, 5000L, "script4");
        wallet.addUTXO("tx5", 4, 50000L, "script5");
        wallet.addUTXO("tx6", 5, 500000L, "script6");
        wallet.addUTXO("tx7", 6, 5000000L, "script7");

        System.out.println("Total Balance: " + wallet.getTotalBalance());
        System.out.println("UTXO tx3-1: " + wallet.getUTXO("tx3", 1).getValue());
        // 打印钱包结构
        wallet.printWalletStructure();

        wallet.removeUTXO("tx4", 3);
        System.out.println("\nTotal Balance after removal: " + wallet.getTotalBalance());
        // 再次打印钱包结构
        wallet.printWalletStructure();
        */
    }


}