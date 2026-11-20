package cn.woshiikun_1145.mcmod.choco.cstmm.entity;

import net.minecraft.util.math.random.Random;

import java.util.ArrayList;
import java.util.List;

/**
 * 【作用】诱饵弹射击模式生成器（移植自 CounterStrikeGrenades 的 DecoyFirePatternGenerator）：
 *         按枪械射击模式生成"何时该放一枪"的时间戳列表（tick）：
 *         AUTO → 爆发式连发（段内按射击间隔连响 2-5 发），其余（SEMI 等）→ 单点节奏；
 *         无有效枪械数据时退回半自动节奏（fallback）。
 * 【被谁使用】DecoyGrenadeEntity.activate（落地激活时生成时间戳）。
 */
public final class DecoyFirePatternGenerator {

    private DecoyFirePatternGenerator() {
    }

    /**
     * 【作用】生成射击时间戳列表：按枪械模式分派（AUTO→连发，其余→半自动；无效数据→fallback 半自动）。
     * 【被谁使用】DecoyGrenadeEntity.activate。
     */
    public static List<Integer> generateFireTimestamps(DecoyGrenadeEntity entity) {
        DecoyGunData gunData = entity.getGunData();
        if (gunData == null || !gunData.isValid()) {
            return generateSemiTimestamps(entity.getWorld().getRandom());
        }
        if ("AUTO".equals(gunData.fireMode())) {
            return generateAutoTimestamps(gunData, entity.getWorld().getRandom());
        }
        return generateSemiTimestamps(entity.getWorld().getRandom());
    }

    // 【作用】连发模式时间戳：首段延迟减半（增加真实性），每段随机 2-5 发
    //         （段内按 shootIntervalMs/50 = tick 间隔），段间延迟 25-60 tick，持续生成直至总时长上限
    private static List<Integer> generateAutoTimestamps(DecoyGunData gunData, Random random) {
        List<Integer> timestamps = new ArrayList<>();
        int currentTime = 0;
        double shootIntervalTicks = Math.max(1.0, gunData.shootIntervalMs() / 50.0);

        currentTime += getRandomBurstDelay(random, true);

        while (currentTime < DecoyConstants.TOTAL_DURATION_TICKS) {
            // 【作用】随机生成爆发中的射击次数（kotlin nextInt(min, max+1) 复刻为闭区间取值）
            int shotsInBurst = random.nextInt(
                    DecoyConstants.MAX_SHOTS_IN_BURST - DecoyConstants.MIN_SHOTS_IN_BURST + 1)
                    + DecoyConstants.MIN_SHOTS_IN_BURST;
            for (int i = 0; i < shotsInBurst; i++) {
                int shotTime = currentTime + (int) (i * shootIntervalTicks);
                if (shotTime < DecoyConstants.TOTAL_DURATION_TICKS) {
                    timestamps.add(shotTime);
                } else {
                    break;
                }
            }
            // 【作用】更新当前时间到最后一次射击，再添加爆发间延迟（原版顺序）
            currentTime = timestamps.isEmpty() ? currentTime : timestamps.get(timestamps.size() - 1);
            currentTime += getRandomBurstDelay(random, false);
        }
        return timestamps;
    }

    // 【作用】半自动模式时间戳：首击延迟减半，之后每次随机 25-60 tick 一响，直至总时长上限
    private static List<Integer> generateSemiTimestamps(Random random) {
        List<Integer> timestamps = new ArrayList<>();
        int currentTime = 0;
        currentTime += getRandomBurstDelay(random, true);
        timestamps.add(currentTime);
        while (currentTime < DecoyConstants.TOTAL_DURATION_TICKS) {
            currentTime += getRandomBurstDelay(random, false);
            if (currentTime < DecoyConstants.TOTAL_DURATION_TICKS) {
                timestamps.add(currentTime);
            }
        }
        return timestamps;
    }

    // 【作用】随机爆发间延迟：首段减半（原版逻辑）；区间 [min, max] 闭区间取值（与 kotlin 语义一致）
    private static int getRandomBurstDelay(Random random, boolean isFirstBurst) {
        int minDelay = isFirstBurst ? DecoyConstants.MIN_BURST_DELAY_TICKS / 2 : DecoyConstants.MIN_BURST_DELAY_TICKS;
        int maxDelay = isFirstBurst ? DecoyConstants.MAX_BURST_DELAY_TICKS / 2 : DecoyConstants.MAX_BURST_DELAY_TICKS;
        return random.nextInt(maxDelay - minDelay + 1) + minDelay;
    }
}
