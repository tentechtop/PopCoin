// 引入OpenCL标准库
#include <clc/clc.h>
#include <string.h>

// SHA-256常量定义（简化版，仅用于演示）
#define SHA256_BLOCK_SIZE 64
#define SHA256_DIGEST_SIZE 32

// 简化的SHA-256哈希计算（实际项目建议使用完整实现）
void sha256(const uchar *data, size_t len, uchar *hash) {
    // 此处为简化实现，实际需替换为标准SHA-256算法
    memset(hash, 0, SHA256_DIGEST_SIZE);
    for (size_t i = 0; i < len && i < SHA256_DIGEST_SIZE; i++) {
        hash[i] = data[i] ^ 0x55; // 模拟哈希计算
    }
}

// 内核函数：挖矿主逻辑
__kernel void mineBlock(
    __global const uchar *blockData,    // 区块数据（不含nonce）
    __global const uchar *target,      // 难度目标（前导零模板）
    __global int *resultNonce,         // 输出：找到的有效nonce
    __global uchar *resultHash,        // 输出：有效哈希值
    int blockSize,                     // 区块数据大小
    int maxNonce                       // 最大nonce范围
) {
    // 获取全局线程ID（每个线程处理一个nonce）
    int globalId = get_global_id(0);
    if (globalId >= maxNonce) return;

    // 1. 准备区块数据+nonce的临时缓冲区
    uchar buffer[blockSize + 4]; // 额外4字节存储nonce
    memcpy(buffer, blockData, blockSize);

    // 2. 写入当前线程的nonce（小端存储）
    buffer[blockSize] = (uchar)(globalId & 0xFF);
    buffer[blockSize + 1] = (uchar)((globalId >> 8) & 0xFF);
    buffer[blockSize + 2] = (uchar)((globalId >> 16) & 0xFF);
    buffer[blockSize + 3] = (uchar)((globalId >> 24) & 0xFF);

    // 3. 计算区块哈希
    uchar hash[SHA256_DIGEST_SIZE];
    sha256(buffer, blockSize + 4, hash);

    // 4. 验证哈希是否满足难度目标（前导零数量匹配）
    bool isValid = true;
    for (int i = 0; i < SHA256_DIGEST_SIZE; i++) {
        if (hash[i] != target[i]) {
            isValid = false;
            break;
        }
    }

    // 5. 找到有效哈希时写入结果（原子操作确保线程安全）
    if (isValid) {
        atomic_xchg(resultNonce, globalId);
        memcpy(resultHash, hash, SHA256_DIGEST_SIZE);
    }
}