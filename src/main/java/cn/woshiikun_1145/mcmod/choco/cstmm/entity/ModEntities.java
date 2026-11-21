package cn.woshiikun_1145.mcmod.choco.cstmm.entity;

import cn.woshiikun_1145.mcmod.choco.cstmm.Cstmm;
import cn.woshiikun_1145.mcmod.choco.cstmm.util.GrenadeSettings;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

/**
 * 【作用】模组自定义实体注册表（命名空间固定 cstmm，与物品 ID 约定一致）：
 *         cstmm:c4 = 已安放的 C4 炸弹实体（绿色长方体占位渲染，客户端 BombEntityRenderer）；
 *         cstmm:hegrenade / cstmm:flashbang / cstmm:smokegrenade = CS 手雷投掷物实体
 *         （移植自 CounterStrikeGrenades，阶段 1，尺寸 0.25 方块，客户端手雷渲染器渲染）。
 * 【被谁使用】Cstmm.onInitialize（启动时注册，须在注册表冻结前）；BombInteractionManager
 *           （安放生成/拆除/爆炸清理）、BombRoundManager（回合重置清理）、
 *           GrenadeThrownPayload（服务端按类型生成手雷实体）；
 *           客户端 CstmmClient 注册渲染器与交互按键（拆除目标检测）。
 */
public class ModEntities {

    /** 已安放的 C4 炸弹：碰撞盒 0.875×0.375×0.625（与 c4.json 模型 14×6×10 units 精确对齐）、防火防爆（爆炸伤害由 C4DamageCalculator 阶段④结算） */
    public static final EntityType<BombEntity> C4 = Registry.register(
            Registries.ENTITY_TYPE,
            Identifier.of("cstmm", "c4"),
            EntityType.Builder.<BombEntity>create(BombEntity::new, SpawnGroup.MISC)
                    .dimensions(0.875F, 0.375F)
                    .makeFireImmune()
                    .build()
    );

    /** 高爆手雷投掷物：弹跳物理 + 引信 2 秒爆炸（伤害随距离线性衰减） */
    public static final EntityType<HEGrenadeEntity> HEGRENADE = registerGrenade(
            "hegrenade", HEGrenadeEntity::new);

    /** 闪光弹投掷物：引信 1.6 秒后爆闪（按角度/距离/遮挡对全体玩家计算致盲） */
    public static final EntityType<FlashBangEntity> FLASHBANG = registerGrenade(
            "flashbang", FlashBangEntity::new);

    /** 烟雾弹投掷物：落地停稳后发烟（烟雾可被箭矢/子弹穿透打散） */
    public static final EntityType<SmokeGrenadeEntity> SMOKEGRENADE = registerGrenade(
            "smokegrenade", SmokeGrenadeEntity::new);

    /** 燃烧弹投掷物：落地/空中自爆生成魂火区域（蓝白粒子，伤害爬升） */
    public static final EntityType<IncendiaryEntity> INCENDIARY = registerGrenade(
            "incendiary", IncendiaryEntity::new);

    /** 莫洛托夫投掷物：落地/空中自爆生成火焰区域（橙色粒子，伤害爬升） */
    public static final EntityType<MolotovEntity> MOLOTOV = registerGrenade(
            "molotov", MolotovEntity::new);

    /** 诱饵弹投掷物：落地后 15 秒内按持枪者射速模式模拟枪声（TACZ 时用真实枪械数据），结束小爆炸 */
    public static final EntityType<DecoyGrenadeEntity> DECOY = registerGrenade(
            "decoy", DecoyGrenadeEntity::new);

    /**
     * 【作用】按手雷统一尺寸（0.25 方块）注册一个手雷投掷物实体类型。
     * 【被谁使用】本类静态字段初始化（阶段 2 的燃烧弹/莫洛托夫/诱饵弹复用）。
     */
    private static <T extends CounterStrikeGrenadeEntity> EntityType<T> registerGrenade(
            String name, EntityType.EntityFactory<T> factory) {
        return Registry.register(
                Registries.ENTITY_TYPE,
                Identifier.of("cstmm", name),
                EntityType.Builder.<T>create(factory, SpawnGroup.MISC)
                        .dimensions(GrenadeSettings.GRENADE_ENTITY_SIZE, GrenadeSettings.GRENADE_ENTITY_SIZE)
                        .build()
        );
    }

    /**
     * 【作用】实体注册入口（必须在注册表冻结前调用）：静态字段加载即完成注册。
     * 【被谁使用】Cstmm.onInitialize（ModGuardian 包装，异常时进入保护模式）。
     */
    public static void initialize() {
        // 【作用】注册 BlockPos 列表实体同步序列化器（燃烧类手雷 spreadBlocks 同步用，
        //         移植自 CSG 的 BlockPosListEntityDataSerializer；两侧顺序一致）
        BlockPosListDataHandler.register();
        Cstmm.LOGGER.info("[CSTMM - ModEntities] Entities registered: cstmm:c4, cstmm:hegrenade, cstmm:flashbang, cstmm:smokegrenade, cstmm:incendiary, cstmm:molotov, cstmm:decoy");
    }
}
