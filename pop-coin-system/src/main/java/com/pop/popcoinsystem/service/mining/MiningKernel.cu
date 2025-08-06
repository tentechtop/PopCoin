#include <cuda.h>
#include <stdint.h>
#include <string.h>

// SHA-256d实现（双SHA-256，适配GPU）
namespace sha256 {
    // 常量定义（SHA-256标准）
    __device__ const uint32_t k[] = {
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
        0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
    };

    // 旋转右移
    __device__ uint32_t rotr(uint32_t x, int n) {
        return (x >> n) | (x << (32 - n));
    }

    // 加密过程
    __device__ void transform(uint32_t state[8], const uint8_t block[64]) {
        uint32_t w[64];
        uint32_t a, b, c, d, e, f, g, h, t1, t2;

        // 初始化消息调度数组
        for (int i = 0; i < 16; i++) {
            w[i] = (block[4*i] << 24) | (block[4*i+1] << 16) | (block[4*i+2] << 8) | block[4*i+3];
        }
        for (int i = 16; i < 64; i++) {
            uint32_t s0 = rotr(w[i-15], 7) ^ rotr(w[i-15], 18) ^ (w[i-15] >> 3);
            uint32_t s1 = rotr(w[i-2], 17) ^ rotr(w[i-2], 19) ^ (w[i-2] >> 10);
            w[i] = w[i-16] + s0 + w[i-7] + s1;
        }

        // 初始化工作变量
        a = state[0]; b = state[1]; c = state[2]; d = state[3];
        e = state[4]; f = state[5]; g = state[6]; h = state[7];

        // 主循环
        for (int i = 0; i < 64; i++) {
            uint32_t S1 = rotr(e, 14) ^ rotr(e, 18) ^ rotr(e, 41);
            uint32_t ch = (e & f) ^ (~e & g);
            t1 = h + S1 + ch + k[i] + w[i];
            uint32_t S0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22);
            uint32_t maj = (a & b) ^ (a & c) ^ (b & c);
            t2 = S0 + maj;

            h = g; g = f; f = e; e = d + t1;
            d = c; c = b; b = a; a = t1 + t2;
        }

        // 更新状态
        state[0] += a; state[1] += b; state[2] += c; state[3] += d;
        state[4] += e; state[5] += f; state[6] += g; state[7] += h;
    }

    // 计算单轮SHA-256
    __device__ void sha256(uint8_t *out, const uint8_t *in, size_t len) {
        uint32_t state[8] = {
            0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
            0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19
        };
        uint8_t block[64];
        size_t off = 0;

        // 处理完整的512位块
        while (off + 64 <= len) {
            memcpy(block, in + off, 64);
            transform(state, block);
            off += 64;
        }

        // 处理剩余数据
        memcpy(block, in + off, len - off);
        size_t rem = len - off;
        block[rem++] = 0x80;

        // 填充
        if (rem <= 56) {
            memset(block + rem, 0, 56 - rem);
        } else {
            memset(block + rem, 0, 64 - rem);
            transform(state, block);
            memset(block, 0, 56);
        }

        // 附加长度（bits）
        uint64_t bits = len * 8;
        block[56] = (bits >> 56) & 0xff;
        block[57] = (bits >> 48) & 0xff;
        block[58] = (bits >> 40) & 0xff;
        block[59] = (bits >> 32) & 0xff;
        block[60] = (bits >> 24) & 0xff;
        block[61] = (bits >> 16) & 0xff;
        block[62] = (bits >> 8) & 0xff;
        block[63] = bits & 0xff;
        transform(state, block);

        // 输出结果（大端转小端）
        for (int i = 0; i < 8; i++) {
            out[4*i] = (state[i] >> 24) & 0xff;
            out[4*i+1] = (state[i] >> 16) & 0xff;
            out[4*i+2] = (state[i] >> 8) & 0xff;
            out[4*i+3] = state[i] & 0xff;
        }
    }

    // 双SHA-256（比特币标准）
    __device__ void sha256d(uint8_t *out, const uint8_t *in, size_t len) {
        uint8_t buf[32];
        sha256(buf, in, len);  // 第一次哈希
        sha256(out, buf, 32);  // 第二次哈希
    }
}

// 区块头数据结构（与Java序列化结果严格匹配，80字节）
struct BlockHeader {
    uint32_t version;          // 4字节，小端（Java: Integer.reverseBytes()）
    uint8_t previousHash[32];  // 32字节，小端（Java: reverseBytes()处理后）
    uint8_t merkleRoot[32];    // 32字节，小端（Java: reverseBytes()处理后）
    uint32_t time;             // 4字节，小端（Java: 秒级时间戳，Integer.reverseBytes()）
    uint32_t difficultyTarget; // 4字节，小端（压缩难度，Java: reverseBytes()处理后）
    uint32_t nonce;            // 4字节，小端（Java: Integer.reverseBytes()）
};

// 全局变量：挖矿结果（GPU全局内存）
__device__ uint32_t foundNonce = 0;
__device__ int found = 0;  // 0=未找到，1=已找到
__device__ uint8_t foundHash[32];

// 辅助函数：将压缩难度（bits）转换为256位目标值（小端存储）
__device__ void bitsToTarget(uint32_t bits, uint8_t* target) {
    memset(target, 0, 32);
    uint8_t exponent = (bits >> 24) & 0xFF;  // 指数部分
    uint32_t mantissa = bits & 0x00FFFFFF;   // 尾数部分（大端）

    // 处理指数范围
    if (exponent < 3) {
        mantissa >>= 8 * (3 - exponent);
        exponent = 3;
    }
    if (exponent > 32) exponent = 32;

    // 转换为小端存储的256位目标值
    int shift = (exponent - 3) * 8;  // 位移量（字节）
    for (int i = 0; i < 3; i++) {
        int pos = shift / 8 + (2 - i);  // 小端存储位置
        if (pos < 32) {
            target[pos] = (mantissa >> (i * 8)) & 0xFF;
        }
    }
}

// 辅助函数：验证哈希是否满足难度目标（均为小端比较）
__device__ bool isValidHash(uint8_t* hash, uint32_t bits) {
    uint8_t target[32];
    bitsToTarget(bits, target);

    // 逐字节比较（小端存储，哈希 <= 目标即为有效）
    for (int i = 0; i < 32; i++) {
        if (hash[i] < target[i]) return true;
        if (hash[i] > target[i]) return false;
    }
    return true;  // 等于目标值时有效
}

// GPU挖矿内核（优化RTX 4060并行效率）
__global__ void mineKernel(BlockHeader header, uint32_t startNonce, uint32_t endNonce) {
    // 共享内存缓存（减少全局内存访问延迟）
    __shared__ uint8_t s_prevHash[32];
    __shared__ uint8_t s_merkleRoot[32];
    __shared__ uint32_t s_version;
    __shared__ uint32_t s_time;
    __shared__ uint32_t s_bits;

    // 线程0负责初始化共享内存
    if (threadIdx.x == 0) {
        memcpy(s_prevHash, header.previousHash, 32);
        memcpy(s_merkleRoot, header.merkleRoot, 32);
        s_version = header.version;
        s_time = header.time;
        s_bits = header.difficultyTarget;
    }
    __syncthreads();  // 等待共享内存初始化完成

    // 计算线程唯一ID和对应的nonce
    uint32_t globalThreadId = blockIdx.x * blockDim.x + threadIdx.x;
    uint32_t nonce = startNonce + globalThreadId;

    // 已找到结果或超出范围则退出
    if (found || nonce >= endNonce) return;

    // 构造本地区块头（使用共享内存数据）
    BlockHeader localHeader;
    localHeader.version = s_version;
    memcpy(localHeader.previousHash, s_prevHash, 32);
    memcpy(localHeader.merkleRoot, s_merkleRoot, 32);
    localHeader.time = s_time;
    localHeader.difficultyTarget = s_bits;
    localHeader.nonce = nonce;

    // 计算双SHA-256哈希
    uint8_t hash[32];
    sha256::sha256d(hash, (uint8_t*)&localHeader, sizeof(BlockHeader));

    // 验证哈希是否有效
    if (isValidHash(hash, s_bits)) {
        // 原子操作确保只有第一个找到的结果被记录
        if (atomicExch(&found, 1) == 0) {
            foundNonce = nonce;
            memcpy(foundHash, hash, 32);
        }
    }
}
