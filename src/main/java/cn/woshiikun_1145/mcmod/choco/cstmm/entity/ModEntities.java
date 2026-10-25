package cn.woshiikun_1145.mcmod.choco.cstmm.entity;

import cn.woshiikun_1145.mcmod.choco.cstmm.Cstmm;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

/**
 * 【作用】模组自定义实体注册表（命名空间固定 cstmm，与物品 ID 约定一致）：
 *         cstmm:c4 = 已安放的 C4 炸弹实体（绿色长方体占位渲染，客户端 BombEntityRenderer）。
 * 【被谁使用】Cstmm.onInitialize（启动时注册，须在注册表冻结前）；BombInteractionManager
 *           （安放生成/拆除/爆炸清理）、BombRoundManager（回合重置清理）；
 *           客户端 CstmmClient 注册渲染器与交互按键（拆除目标检测）。仅服务端 spawn。
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

    /**
     * 【作用】实体注册入口（必须在注册表冻结前调用）：静态字段加载即完成注册。
     * 【被谁使用】Cstmm.onInitialize（ModGuardian 包装，异常时进入保护模式）。
     */
    public static void initialize() {
        Cstmm.LOGGER.info("[CSTMM - ModEntities] Entities registered: cstmm:c4");
    }
}
