package cn.woshiikun_1145.mcmod.choco.cstmm.entity;

import cn.woshiikun_1145.mcmod.choco.cstmm.item.ModItems;
import cn.woshiikun_1145.mcmod.choco.cstmm.util.GrenadeDamageTypes;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.damage.DamageType;
import net.minecraft.entity.projectile.thrown.ThrownItemEntity;
import net.minecraft.item.Item;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;

/**
 * 【作用】莫洛托夫投掷物实体（移植自 CounterStrikeGrenades 的 MolotovEntity）：
 *         落地后生成橙色常规火焰粒子（客户端），伤害走 molotov_fire 系列伤害类型。
 * 【被谁使用】GrenadeThrownPayload（服务端生成）、ModEntities.MOLOTOV（注册）。
 */
public class MolotovEntity extends AbstractFireGrenadeEntity {

    public MolotovEntity(EntityType<? extends ThrownItemEntity> type, World world) {
        super(type, world);
    }

    // 【作用】投掷物默认展示物品类型（molotov 物品模型）
    @Override
    protected Item getDefaultItem() {
        return ModItems.MOLOTOV;
    }

    @Override
    protected RegistryKey<DamageType> getFireDamageType() {
        return GrenadeDamageTypes.MOLOTOV_FIRE;
    }

    @Override
    protected RegistryKey<DamageType> getSelfFireDamageType() {
        return GrenadeDamageTypes.MOLOTOV_FIRE_SELF;
    }

    @Override
    protected RegistryKey<DamageType> hitDamageType() {
        return GrenadeDamageTypes.MOLOTOV_HIT;
    }
}
