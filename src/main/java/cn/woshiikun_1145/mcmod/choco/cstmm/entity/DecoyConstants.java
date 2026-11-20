package cn.woshiikun_1145.mcmod.choco.cstmm.entity;

/**
 * 【作用】诱饵弹相关常量（移植自 CounterStrikeGrenades 的 DecoyConstants，数值逐值一致）。
 * 【被谁使用】DecoyGrenadeEntity（生命周期与爆炸时机）、DecoyFirePatternGenerator（射击时间戳生成）。
 */
public final class DecoyConstants {

    /** 诱饵弹总持续时间（15 秒 = 300 tick） */
    public static final int TOTAL_DURATION_TICKS = 15 * 20;

    /** 最小爆发间延迟（25 tick ≈ 1.25 秒） */
    public static final int MIN_BURST_DELAY_TICKS = 25;

    /** 最大爆发间延迟（60 tick ≈ 3 秒） */
    public static final int MAX_BURST_DELAY_TICKS = 60;

    /** 单次爆发中最小射击次数 */
    public static final int MIN_SHOTS_IN_BURST = 2;

    /** 单次爆发中最大射击次数 */
    public static final int MAX_SHOTS_IN_BURST = 5;

    private DecoyConstants() {
    }
}
