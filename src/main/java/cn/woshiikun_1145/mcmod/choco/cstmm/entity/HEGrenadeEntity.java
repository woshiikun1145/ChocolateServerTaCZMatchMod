package cn.woshiikun_1145.mcmod.choco.cstmm.entity;

import cn.woshiikun_1145.mcmod.choco.cstmm.item.ModItems;
import cn.woshiikun_1145.mcmod.choco.cstmm.sound.ModSounds;
import cn.woshiikun_1145.mcmod.choco.cstmm.util.GrenadeDamageTypes;
import cn.woshiikun_1145.mcmod.choco.cstmm.util.GrenadeHooks;
import cn.woshiikun_1145.mcmod.choco.cstmm.util.GrenadeMath;
import cn.woshiikun_1145.mcmod.choco.cstmm.util.GrenadeSettings;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.thrown.ThrownItemEntity;
import net.minecraft.item.Item;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * 【作用】高爆手雷（HE）投掷物实体（移植自 CounterStrikeGrenades 的 HEGrenadeEntity）。
 *         引信 2 秒：服务端做"逐实体视线遮挡判定 + 线性距离衰减"的爆炸伤害结算；
 *         客户端触发爆炸视觉并引爆附近烟雾（HE 可炸开烟雾）。
 * 【被谁使用】GrenadeThrownPayload（服务端生成）、ModEntities.HEGRENADE（注册）、
 *           客户端手雷渲染器（渲染实体）。
 */
public class HEGrenadeEntity extends CounterStrikeGrenadeEntity {

    public HEGrenadeEntity(EntityType<? extends ThrownItemEntity> type, World world) {
        super(type, world);
        this.hitBlockSound = ModSounds.HEGRENADE_BOUNCE.value();
    }

    // 【作用】投掷物默认展示物品类型（hegrenade 物品模型，实体渲染器据此渲染）
    @Override
    protected Item getDefaultItem() {
        return ModItems.HEGRENADE;
    }

    // 【作用】引信计时：到点后服务端结算伤害、客户端播放爆炸视觉与烟雾引爆；随后标记激活并在服务端丢弃
    @Override
    public void tick() {
        if (this.dataTracker.get(IS_ACTIVATED)) {
            if (!this.getWorld().isClient) {
                this.discard();
            }
            return;
        }

        super.tick();

        if (GrenadeMath.ticksToSeconds(this.age) > GrenadeSettings.HE_FUSE_TIME_MS / 1000.0) {
            if (!this.getWorld().isClient) {
                doDamage();
            } else if (GrenadeHooks.client != null) {
                GrenadeHooks.client.onHeExploded(this.getPos());
                blowUpNearbySmokeGrenade();
            }
            this.dataTracker.set(IS_ACTIVATED, true);
        }
    }

    // 【作用】服务端爆炸伤害：半径 HE_DAMAGE_RANGE 内逐实体检查，
    //         对每个候选实体做"头部射线 + 身体射线"遮挡判定（被墙挡住则无伤），
    //         伤害 = 基础 × (1 - 距离/半径)，头部暴露时乘 HE_HEAD_DAMAGE_BOOST；
    //         对自己按 SelfDamageSetting 策略处理（ALWAYS 走自伤伤害类型，无击杀归因）
    private void doDamage() {
        ServerWorld world = (ServerWorld) this.getWorld();
        double damageRange = GrenadeSettings.HE_DAMAGE_RANGE;
        DamageSource baseDamageSource = createDamageSource(world, GrenadeDamageTypes.HEGRENADE_EXPLOSION,
                this.getOwner(), this.getOwner());
        DamageSource selfDamageSource = createDamageSource(world, GrenadeDamageTypes.HEGRENADE_EXPLOSION_SELF,
                null, null);

        Class<? extends LivingEntity> targetClass = GrenadeSettings.DAMAGE_NON_PLAYER_ENTITY
                ? LivingEntity.class
                : PlayerEntity.class;
        Box searchBox = new Box(this.getBlockPos()).expand(damageRange);

        for (LivingEntity entity : world.getEntitiesByClass(targetClass, searchBox, e -> true)) {
            double distance = entity.distanceTo(this);
            if (distance >= damageRange) {
                continue;
            }
            double damage = getDamageBlockingState(entity);
            if (damage <= 0.0) {
                continue;
            }
            EntityAttributeInstance knockbackAttr =
                    entity.getAttributeInstance(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE);
            double original = knockbackAttr != null ? knockbackAttr.getBaseValue() : 0.0;
            if (knockbackAttr != null) {
                knockbackAttr.setBaseValue(1.0);
            }

            if (entity == this.getOwner()) {
                switch (GrenadeSettings.HE_CAUSE_DAMAGE_TO_OWNER) {
                    case NEVER -> { /* 不自伤 */ }
                    case NOT_IN_TEAM -> entity.damage(baseDamageSource, (float) damage);
                    case ALWAYS -> entity.damage(selfDamageSource, (float) damage);
                }
            } else {
                entity.damage(baseDamageSource, (float) damage);
            }

            if (knockbackAttr != null) {
                knockbackAttr.setBaseValue(original);
            }
        }
    }

    // 【作用】取"头部暴露伤害"与"身体暴露伤害"的较大值：从爆心向实体眼部/脚部做方块射线，
    //         MISS（无遮挡）才计伤；眼部距离 <1.5 格视为贴身，按头部加成计算
    private double getDamageBlockingState(LivingEntity entity) {
        Vec3d origin = this.getPos();

        double headDamage = 0.0;
        // 【作用】第 5 参用 ShapeContext.absent()：yarn 的 ShapeContext.of(null) 会 NPE
        //         （原 mod 传 null 实体在 Forge 1.20.1 下合法，1.21.1 下必须显式无实体上下文）
        HitResult headClip = this.getWorld().raycast(new net.minecraft.world.RaycastContext(
                origin, entity.getEyePos(),
                net.minecraft.world.RaycastContext.ShapeType.COLLIDER,
                net.minecraft.world.RaycastContext.FluidHandling.ANY,
                net.minecraft.block.ShapeContext.absent()));
        if (headClip.getType() == HitResult.Type.MISS) {
            double distance = origin.distanceTo(entity.getEyePos());
            headDamage = distance < 1.5
                    ? calculateHEDamage(distance, 0.0, true)
                    : calculateHEDamage(distance, 0.0, false);
        }

        double bodyDamage = 0.0;
        HitResult bodyClip = this.getWorld().raycast(new net.minecraft.world.RaycastContext(
                origin, entity.getPos(),
                net.minecraft.world.RaycastContext.ShapeType.COLLIDER,
                net.minecraft.world.RaycastContext.FluidHandling.ANY,
                net.minecraft.block.ShapeContext.absent()));
        if (bodyClip.getType() == HitResult.Type.MISS) {
            bodyDamage = calculateHEDamage(origin.distanceTo(entity.getPos()), 0.0, false);
        }

        return Math.max(headDamage, bodyDamage);
    }

    // 【作用】HE 伤害线性衰减公式：base × (1 - distance/range) × (1 - armorReduction)
    private static double calculateHEDamage(double distance, double armorReduction, boolean headDamageBoost) {
        double baseDamage = headDamageBoost
                ? GrenadeSettings.HE_BASE_DAMAGE * GrenadeSettings.HE_HEAD_DAMAGE_BOOST
                : GrenadeSettings.HE_BASE_DAMAGE;
        return baseDamage * (1.0 - distance / GrenadeSettings.HE_DAMAGE_RANGE) * (1.0 - armorReduction);
    }

    // 【作用】碰撞伤害源：对自己无第三方归因，对他人携带投掷者归因（计入击杀统计）
    @Override
    protected DamageSource getHitDamageSource(LivingEntity hitEntity) {
        if (hitEntity == this.getOwner()) {
            return createDamageSource((ServerWorld) this.getWorld(), GrenadeDamageTypes.HEGRENADE_HIT,
                    this, null);
        }
        return createDamageSource((ServerWorld) this.getWorld(), GrenadeDamageTypes.HEGRENADE_HIT,
                this, this.getOwner());
    }

    // 【作用】服务端引爆附近烟雾：HE 可炸开烟雾（范围 = HE 半径 + 烟雾半径，向下含烟雾下沉高度），
    //         烟雾粒子按距离恢复透明度（客户端执行，原 mod 网格固定 2.5 格透明半径）
    private void blowUpNearbySmokeGrenade() {
        double heDamageRange = GrenadeSettings.HE_DAMAGE_RANGE;
        double smokeRadius = GrenadeSettings.SMOKE_RADIUS;
        double smokeFallingHeight = GrenadeSettings.SMOKE_MAX_FALLING_HEIGHT;
        Box searchBox = this.getBoundingBox().expand(heDamageRange + smokeRadius)
                .expand(0.0, smokeFallingHeight, 0.0);
        for (SmokeGrenadeEntity smoke : this.getWorld().getEntitiesByClass(
                SmokeGrenadeEntity.class, searchBox, e -> true)) {
            if (GrenadeHooks.client != null) {
                GrenadeHooks.client.clearSmokeWithinRange(smoke, this.getPos(), heDamageRange + 2.5, false);
            }
        }
    }

    /**
     * 【作用】按伤害类型注册键构造 DamageSource（attacker/source 均可空；
     *         yarn 1.21.1 的 DamageSources.create 直接接受 RegistryKey，无需查注册表）。
     * 【被谁使用】doDamage（爆炸伤害）、getHitDamageSource（碰撞伤害）、
     *           FlashBangEntity / SmokeGrenadeEntity（各自的碰撞伤害源）。
     */
    static DamageSource createDamageSource(ServerWorld world, RegistryKey<DamageType> key,
                                            Entity attacker, Entity source) {
        if (attacker == null) {
            return world.getDamageSources().create(key);
        }
        return world.getDamageSources().create(key, attacker, source);
    }
}
