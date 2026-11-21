package cn.woshiikun_1145.mcmod.choco.cstmm.util;

import net.minecraft.entity.damage.DamageType;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;

/**
 * 【作用】手雷系统伤害类型注册键（移植自 CounterStrikeGrenades 的 ModDamageType，命名空间
 *         统一改为 cstmm）。对应数据包 JSON 位于 data/cstmm/damage_type/*.json，
 *         运行时由原版数据包加载器注册；本类仅持有键，不直接注册。
 * 【被谁使用】HEGrenadeEntity（爆炸/碰撞伤害）、FlashBangEntity 与 SmokeGrenadeEntity
 *           （碰撞伤害）；阶段 2 燃烧弹接入 incendiary/molotov 系列。
 */
public final class GrenadeDamageTypes {

    private GrenadeDamageTypes() {
    }

    /** HE 爆炸伤害（有攻击者=投掷者，计入击杀归因） */
    public static final RegistryKey<DamageType> HEGRENADE_EXPLOSION =
            of("hegrenade_explosion");

    /** HE 爆炸自伤（无攻击者，不产生击杀归因） */
    public static final RegistryKey<DamageType> HEGRENADE_EXPLOSION_SELF =
            of("hegrenade_explosion_self");

    /** HE 手雷击中身体（弹跳碰撞的 1 点伤害） */
    public static final RegistryKey<DamageType> HEGRENADE_HIT = of("hegrenade_hit");

    /** 闪光弹击中身体（1 点伤害） */
    public static final RegistryKey<DamageType> FLASHBANG_HIT = of("flashbang_hit");

    /** 烟雾弹击中身体（1 点伤害） */
    public static final RegistryKey<DamageType> SMOKEGRENADE_HIT = of("smokegrenade_hit");

    // ===== 阶段 2/3 预留（JSON 已随素材迁移，实体未实现前不会被引用） =====

    /** 燃烧弹火焰伤害 */
    public static final RegistryKey<DamageType> INCENDIARY_FIRE = of("incendiary_fire");

    /** 燃烧弹火焰自伤 */
    public static final RegistryKey<DamageType> INCENDIARY_FIRE_SELF = of("incendiary_fire_self");

    /** 燃烧弹击中身体 */
    public static final RegistryKey<DamageType> INCENDIARY_HIT = of("incendiary_hit");

    /** 莫洛托夫火焰伤害 */
    public static final RegistryKey<DamageType> MOLOTOV_FIRE = of("molotov_fire");

    /** 莫洛托夫火焰自伤 */
    public static final RegistryKey<DamageType> MOLOTOV_FIRE_SELF = of("molotov_fire_self");

    /** 莫洛托夫击中身体 */
    public static final RegistryKey<DamageType> MOLOTOV_HIT = of("molotov_hit");

    /** 诱饵弹击中身体 */
    public static final RegistryKey<DamageType> DECOY_HIT = of("decoy_hit");

    /** 构造 cstmm 命名空间的伤害类型注册键。 */
    private static RegistryKey<DamageType> of(String name) {
        return RegistryKey.of(RegistryKeys.DAMAGE_TYPE, Identifier.of("cstmm", name));
    }
}
