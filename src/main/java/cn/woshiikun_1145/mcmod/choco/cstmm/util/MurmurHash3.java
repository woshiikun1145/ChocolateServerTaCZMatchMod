package cn.woshiikun_1145.mcmod.choco.cstmm.util;

/**
 * 【作用】MurmurHash3 x86_32 纯 Java 实现（标准算法，seed 默认 0）：
 *         用于徽标内容寻址 id（32 位 → 8 字符小写 hex），替代 SHA-256 前 8 字节——
 *         缓存键只需一致性与低碰撞，不需要密码学强度，MurmurHash3 更快且 id 更短。
 * 【被谁使用】Clan#getBadgeId（徽标内容寻址 id 生成）。仅服务端。
 */
public final class MurmurHash3 {

    private MurmurHash3() {}

    /**
     * 【作用】计算 data 的 MurmurHash3 x86_32 哈希值。
     * @param data 输入字节（任意长度，null 按空数组处理）
     * @param seed 哈希种子（固定传 0 保证两端一致）
     * @return 32 位哈希
     */
    public static int hash32(byte[] data, int seed) {
        if (data == null) data = new byte[0];
        int h1 = seed;
        final int c1 = 0xcc9e2d51;
        final int c2 = 0x1b873593;
        int roundedEnd = data.length & ~0x3;

        // 主体：每 4 字节一个小端 32 位块
        for (int i = 0; i < roundedEnd; i += 4) {
            int k1 = (data[i] & 0xff)
                    | ((data[i + 1] & 0xff) << 8)
                    | ((data[i + 2] & 0xff) << 16)
                    | (data[i + 3] << 24);
            k1 *= c1;
            k1 = Integer.rotateLeft(k1, 15);
            k1 *= c2;
            h1 ^= k1;
            h1 = Integer.rotateLeft(h1, 13);
            h1 = h1 * 5 + 0xe6546b64;
        }

        // 尾部：剩余 1~3 字节（注意 switch 贯穿为标准算法的一部分）
        int k1 = 0;
        switch (data.length & 0x3) {
            case 3:
                k1 = (data[roundedEnd + 2] & 0xff) << 16;
                // fall through
            case 2:
                k1 |= (data[roundedEnd + 1] & 0xff) << 8;
                // fall through
            case 1:
                k1 |= (data[roundedEnd] & 0xff);
                k1 *= c1;
                k1 = Integer.rotateLeft(k1, 15);
                k1 *= c2;
                h1 ^= k1;
                // fall through（default 无操作）
            default:
        }

        // 终态混淆（fmix32）
        h1 ^= data.length;
        h1 ^= h1 >>> 16;
        h1 *= 0x85ebca6b;
        h1 ^= h1 >>> 13;
        h1 *= 0xc2b2ae35;
        h1 ^= h1 >>> 16;
        return h1;
    }
}
