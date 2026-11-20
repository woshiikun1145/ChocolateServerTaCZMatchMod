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
 * 【作用】燃烧弹投掷物实体（移植自 CounterStrikeGrenades 的 IncendiaryEntity）：
 *         落地后生成蓝白色魂火粒子火焰（客户端），伤害走 incendiary_fire 系列伤害类型。
 * 【被谁使用】GrenadeThrownPayload（服务端生成）、ModEntities.INCENDIARY（注册）。
 */
public class IncendiaryEntity extends AbstractFireGrenadeEntity {

    public IncendiaryEntity(EntityType<? extends ThrownItemEntity> type, World world) {
        super(type, world);
    }

    // 【作用】投掷物默认展示物品类型（incendiary 物品模型）
    @Override
    protected Item getDefaultItem() {
        return ModItems.INCENDIARY;
    }

    @Override
    protected RegistryKey<DamageType> getFireDamageType() {
        return GrenadeDamageTypes.INCENDIARY_FIRE;
    }

    @Override
    protected RegistryKey<DamageType> getSelfFireDamageType() {
        return GrenadeDamageTypes.INCENDIARY_FIRE_SELF;
    }

    @Override
    protected RegistryKey<DamageType> hitDamageType() {
        return GrenadeDamageTypes.INCENDIARY_HIT;
    }
}
