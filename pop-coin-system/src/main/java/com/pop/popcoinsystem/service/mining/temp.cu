#include <cuda_runtime.h>
#include <stdint.h>
#include <string.h>


namespace sha256 {
    // ... (保持原有SHA256实现不变)
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


    __device__ uint32_t rotr(uint32_t x, int n) {
        return (x >> n) | (x << (32 - n));
    }


    __device__ void transform(uint32_t state[8], const uint8_t block[64]) {
        uint32_t w[64];
        uint32_t a, b, c, d, e, f, g, h, t1, t2;


        for (int i = 0; i < 16; i++) {
            w[i] = (block[4*i] << 24) | (block[4*i+1] << 16) | (block[4*i+2] << 8) | block[4*i+3];
        }
        for (int i = 16; i < 64; i++) {
            uint32_t s0 = rotr(w[i-15], 7) ^ rotr(w[i-15], 18) ^ (w[i-15] >> 3);
            uint32_t s1 = rotr(w[i-2], 17) ^ rotr(w[i-2], 19) ^ (w[i-2] >> 10);
            w[i] = w[i-16] + s0 + w[i-7] + s1;
        }

        a = state[0]; b = state[1]; c = state[2]; d = state[3];
        e = state[4]; f = state[5]; g = state[6]; h = state[7];

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


        state[0] += a; state[1] += b; state[2] += c; state[3] += d;
        state[4] += e; state[5] += f; state[6] += g; state[7] += h;
    }


    __device__ void sha256(uint8_t *out, const uint8_t *in, size_t len) {
        uint32_t state[8] = {
            0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
            0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19
        };
        uint8_t block[64];
        size_t off = 0;


        while (off + 64 <= len) {
            memcpy(block, in + off, 64);
            transform(state, block);
            off += 64;
        }


        memcpy(block, in + off, len - off);
        size_t rem = len - off;
        block[rem++] = 0x80;


        if (rem <= 56) {
            memset(block + rem, 0, 56 - rem);
        } else {
            memset(block + rem, 0, 64 - rem);
            transform(state, block);
            memset(block, 0, 56);
        }


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


        for (int i = 0; i < 8; i++) {
            out[4*i] = (state[i] >> 24) & 0xff;
            out[4*i+1] = (state[i] >> 16) & 0xff;
            out[4*i+2] = (state[i] >> 8) & 0xff;
            out[4*i+3] = state[i] & 0xff;
        }
    }


    __device__ void sha256d(uint8_t *out, const uint8_t *in, size_t len) {
        uint8_t buf[32];
        sha256(buf, in, len);
        sha256(out, buf, 32);
    }
}

#pragma pack(push, 1)
struct BlockHeader {
    uint32_t version;
    uint8_t previousHash[32];
    uint8_t merkleRoot[32];
    uint32_t time;
    uint32_t difficultyTarget;
    uint32_t nonce;
};
#pragma pack(pop)


__device__ void bitsToTarget(uint32_t bits, uint8_t* target) {
    memset(target, 0, 32);
    uint8_t exponent = (bits >> 24) & 0xFF;
    uint32_t mantissa = bits & 0x00FFFFFF;

    if (exponent < 3) {
        mantissa >>= 8 * (3 - exponent);
        exponent = 3;
    }
    if (exponent > 32) exponent = 32;

    int shift = (exponent - 3) * 8;
    for (int i = 0; i < 3; i++) {
        int pos = shift / 8 + (2 - i);
        if (pos < 32) {
            target[pos] = (mantissa >> (i * 8)) & 0xFF;
        }
    }
}


__device__ bool isValidHash(uint8_t* hash, uint32_t bits) {
    uint8_t target[32];
    bitsToTarget(bits, target);

    for (int i = 0; i < 32; i++) {
        if (hash[i] < target[i]) return true;
        if (hash[i] > target[i]) return false;
    }
    return true;
}

// 调试辅助函数：将字节数组转换为十六进制字符串（设备端）
__device__ void bytesToHex(const uint8_t* bytes, size_t len, char* hex) {
    const char* hexChars = "0123456789abcdef";
    for (size_t i = 0; i < len; i++) {
        hex[i*2] = hexChars[(bytes[i] >> 4) & 0x0F];
        hex[i*2+1] = hexChars[bytes[i] & 0x0F];
    }
    hex[len*2] = '\0';
}

// 序列化BlockHeader到字节数组（关键修正：哈希字节序处理）
__device__ void serializeHeader(BlockHeader* header, uint8_t* buffer) {
    // 1. 版本号（4字节，小端）
    buffer[0] = (header->version) & 0xFF;
    buffer[1] = (header->version >> 8) & 0xFF;
    buffer[2] = (header->version >> 16) & 0xFF;
    buffer[3] = (header->version >> 24) & 0xFF;

    // 2. 前区块哈希（32字节，关键修正：不反转哈希字节序）
    // 原因：Java端接收的已经是反转后的哈希，直接复制即可
    memcpy(buffer + 4, header->previousHash, 32);

    // 3. 默克尔根（32字节，关键修正：不反转哈希字节序）
    memcpy(buffer + 36, header->merkleRoot, 32);

    // 4. 时间戳（4字节，小端）
    buffer[68] = (header->time) & 0xFF;
    buffer[69] = (header->time >> 8) & 0xFF;
    buffer[70] = (header->time >> 16) & 0xFF;
    buffer[71] = (header->time >> 24) & 0xFF;

    // 5. 难度目标（4字节，小端）
    buffer[72] = (header->difficultyTarget) & 0xFF;
    buffer[73] = (header->difficultyTarget >> 8) & 0xFF;
    buffer[74] = (header->difficultyTarget >> 16) & 0xFF;
    buffer[75] = (header->difficultyTarget >> 24) & 0xFF;

    // 6. Nonce（4字节，小端）
    buffer[76] = (header->nonce) & 0xFF;
    buffer[77] = (header->nonce >> 8) & 0xFF;
    buffer[78] = (header->nonce >> 16) & 0xFF;
    buffer[79] = (header->nonce >> 24) & 0xFF;
}


__device__ uint8_t debugHeader[80];
__device__ char debugHeaderHex[161];  // 80字节 * 2 + 1
__device__ uint32_t foundNonce = 0;
__device__ int found = 0;
__device__ uint8_t foundHash[32];
__device__ char foundHashHex[65];     // 32字节 * 2 + 1

extern "C" __global__ void miningKernel(
    BlockHeader* header,
    uint32_t startNonce,
    uint32_t endNonce
) {
    uint32_t threadId = blockIdx.x * blockDim.x + threadIdx.x;
    uint32_t totalThreads = gridDim.x * blockDim.x;
    uint32_t currentNonce = startNonce + threadId;

    BlockHeader localHeader = *header;

    // 线程0负责生成调试信息（用于对比Java序列化结果）
    if (threadId == 0) {
        serializeHeader(&localHeader, debugHeader);
    }

    uint8_t headerBuffer[80];
    uint8_t hash[32];

    while (currentNonce < endNonce) {
        if (found) return;

        localHeader.nonce = currentNonce;
        serializeHeader(&localHeader, headerBuffer);
        sha256::sha256d(hash, headerBuffer, 80);

        if (isValidHash(hash, localHeader.difficultyTarget)) {
            int expected = 0;
            if (atomicCAS(&found, expected, 1) == expected) {
                foundNonce = currentNonce;
                memcpy(foundHash, hash, 32);
                return;
            }
        }

        currentNonce += totalThreads;
    }
}
