package cn.woshiikun_1145.mcmod.choco.cstmm.util;

/**
 * 【作用】手雷系统全局参数（移植自 CounterStrikeGrenades 的 ForgeConfigSpec 默认值，
 *         1:1 保留原 mod 的数值口径）。当前为静态常量形态；后续如需按地图/全局配置调整，
 *         再迁入 GlobalConfig（注意保留默认值口径）。
 * 【被谁使用】全部手雷实体与物品（弹跳/爆炸/烟雾/闪光计算）、客户端 ThrowActionHandler
 *           （投掷速度与冷却）、FlashBangExplodedPayload（致盲时长计算）。
 */
public final class GrenadeSettings {

    private GrenadeSettings() {
    }

    // ===== 通用投掷参数 =====

    /** 手雷实体碰撞盒边长（0.25 方块） */
    public static final float GRENADE_ENTITY_SIZE = 0.25f;

    /** 是否忽略屏障方块碰撞（false = 屏障照常弹开；包装成方法避免编译期常量内联产生 Dead code 警告） */
    private static final boolean IGNORE_BARRIER_BLOCK = false;

    /** 【作用】屏障方块碰撞是否忽略（对应原 mod 同名配置项）。【被谁使用】CounterStrikeGrenadeEntity.onBlockHit。 */
    public static boolean ignoreBarrierBlock() {
        return IGNORE_BARRIER_BLOCK;
    }

    /** 投掷冷却（毫秒），同时作为物品冷却条长度 */
    public static final int GRENADE_THROW_COOLDOWN_MS = 1000;

    /** 左键（强投）初速度 */
    public static final double THROW_SPEED_STRONG = 1.3;

    /** 右键（弱投）初速度 */
    public static final double THROW_SPEED_WEAK = 0.4;

    /** 左右键同按（中投）初速度 */
    public static final double THROW_SPEED_MODERATE = 1.0;

    /** 强投时玩家自身速度加成比例 */
    public static final double PLAYER_SPEED_FACTOR_STRONG = 1.3;

    /** 弱投时玩家自身速度加成比例 */
    public static final double PLAYER_SPEED_FACTOR_WEAK = 0.5;

    /** 投掷类型切换的平滑过渡时长（毫秒） */
    public static final int THROW_TYPE_TRANSIENT_TIME_MS = 1000;

    /** 投掷蓄力 FOV 效果强度（阶段 1 暂未接入，保留原 mod 默认值） */
    public static final double FOV_EFFECT_AMOUNT = 0.12;

    /** 爆炸类手雷是否伤害非玩家生物 */
    public static final boolean DAMAGE_NON_PLAYER_ENTITY = true;

    // ===== 烟雾弹 =====

    /** 烟雾半径（方块） */
    public static final int SMOKE_RADIUS = 6;

    /** 烟雾弹落地后到开始发烟的引信时间（毫秒） */
    public static final int SMOKE_FUSE_TIME_AFTER_LANDING_MS = 500;

    /** 烟雾持续时间（毫秒），到期后实体消失 */
    public static final int SMOKE_LIFETIME_MS = 20000;

    /** 烟雾被打散后开始恢复前的等待时间（毫秒） */
    public static final int SMOKE_TIME_BEFORE_REGENERATE_MS = 1000;

    /** 烟雾被打散后的恢复时长（毫秒，透明度渐回） */
    public static final int SMOKE_REGENERATION_TIME_MS = 3000;

    /** 烟雾向下蔓延的最大高度（方块） */
    public static final int SMOKE_MAX_FALLING_HEIGHT = 8;

    /** 箭矢穿过烟雾时的打散半径（方块） */
    public static final double SMOKE_ARROW_CLEAR_RANGE = 1.2;

    /** 子弹（TACZ）穿过烟雾时的打散半径（方块） */
    public static final double SMOKE_BULLET_CLEAR_RANGE = 1.0;

    // ===== 高爆手雷（HE） =====

    /** HE 基础伤害（贴身正面无遮挡） */
    public static final double HE_BASE_DAMAGE = 30.0;

    /** HE 伤害半径（方块，线性衰减到 0） */
    public static final double HE_DAMAGE_RANGE = 5.0;

    /** 头部暴露时伤害加成倍率 */
    public static final double HE_HEAD_DAMAGE_BOOST = 1.5;

    /** HE 引信时间（毫秒，投掷后到爆炸） */
    public static final int HE_FUSE_TIME_MS = 2000;

    /** 自伤策略（原 mod 默认 ALWAYS：自己也被炸，走自伤伤害类型） */
    public static final SelfDamageSetting HE_CAUSE_DAMAGE_TO_OWNER = SelfDamageSetting.ALWAYS;

    // ===== 闪光弹 =====

    /** 闪光有效范围（方块，超出后距离衰减因子为 0） */
    public static final double FLASH_EFFECTIVE_RANGE = 64.0;

    /** 闪光引信时间（毫秒，投掷后到爆闪） */
    public static final int FLASH_FUSE_TIME_MS = 1600;

    /** 最大致盲总时长（秒，贴身直视） */
    public static final double FLASH_MAX_DURATION_S = 5.0;

    /** 最小致盲总时长（秒，完全背对） */
    public static final double FLASH_MIN_DURATION_S = 0.25;

    /** 距离衰减曲线指数（1=线性，越大则近处越强远处衰减越快） */
    public static final double FLASH_DISTANCE_DECAY_EXPONENT = 2.0;

    /** 完全致盲时长占总致盲时长的比例 */
    public static final double FLASH_FULL_BLIND_RATIO = 0.5;

    // ===== 燃烧类手雷（燃烧弹/莫洛托夫共用，阶段 2） =====

    /** 火焰蔓延范围（方块，随机游走步数基准） */
    public static final int FIRE_RANGE = 6;

    /** 火焰持续时间（毫秒），到期后实体消失 */
    public static final int FIRE_LIFETIME_MS = 7000;

    /** 燃烧类引信时间（毫秒，未落地空中自爆） */
    public static final int FIRE_FUSE_TIME_MS = 2000;

    /** 烟雾熄灭火焰的判定距离（方块） */
    public static final int FIRE_EXTINGUISH_RANGE = 6;

    /** 火焰向下蔓延的最大深度（方块） */
    public static final int FIRE_MAX_SPREAD_DOWNWARD = 10;

    /** 火焰每跳伤害（满额） */
    public static final double FIRE_DAMAGE = 3.0;

    /** 火焰伤害从最低到满额的爬升时间（毫秒） */
    public static final int FIRE_DAMAGE_INCREASE_TIME_MS = 2000;

    /** 火焰最低伤害比例（满额的 25%，首次接触时施加） */
    public static final double FIRE_MIN_DAMAGE_RATIO = 0.25;

    /** 火焰对投掷者本人的伤害策略（原 mod 默认 ALWAYS） */
    public static final SelfDamageSetting FIRE_CAUSE_DAMAGE_TO_OWNER = SelfDamageSetting.ALWAYS;

    /** 火焰伤害每次结算后的无敌帧（tick，原 mod 确认值 10） */
    public static final int FIRE_INVULNERABLE_TICKS = 10;

    /** 火焰粒子密度（每方块范围系数，原 mod 常量） */
    public static final int INCENDIARY_PARTICLE_DENSITY = 1;

    /** 火焰粒子基础寿命（毫秒，原 mod 常量） */
    public static final int INCENDIARY_PARTICLE_LIFETIME = 1200;

    // ===== 通用策略 =====

    /**
     * 【作用】手雷对投掷者本人的伤害策略枚举（移植自原 mod SelfDamageSetting）。
     * 【被谁使用】HEGrenadeEntity（爆炸自伤判定）；燃烧弹阶段 2 接入。
     */
    public enum SelfDamageSetting {
        /** 从不伤害自己 */
        NEVER,
        /** 仅在"不与自己对战"场景伤害自己（本服语义：无队伍概念，与 ALWAYS 等效但走对敌伤害类型） */
        NOT_IN_TEAM,
        /** 总是伤害自己（走自伤伤害类型） */
        ALWAYS,
    }
}
