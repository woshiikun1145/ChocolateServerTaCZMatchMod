package cn.woshiikun_1145.mcmod.choco.cstmm.entity;

import cn.woshiikun_1145.mcmod.choco.cstmm.sound.ModSounds;
import cn.woshiikun_1145.mcmod.choco.cstmm.util.GrenadeHooks;
import cn.woshiikun_1145.mcmod.choco.cstmm.util.GrenadeSettings;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.projectile.thrown.ThrownItemEntity;
import net.minecraft.item.Item;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * 【作用】CS 手雷投掷物实体基类（移植自 CounterStrikeGrenades 的 CounterStrikeGrenadeEntity，
 *         mojmap → yarn 1.21.1）。承担：自研弹跳物理（命中方块/实体按方向反弹并衰减）、
 *         落地/激活状态同步（TrackedData）、客户端自转动画（customRot 系列字段供渲染器使用）、
 *         击中实体时的 1 点碰撞伤害与击退处理。
 * 【被谁使用】HEGrenadeEntity / FlashBangEntity / SmokeGrenadeEntity（阶段 1）与
 *           阶段 2/3 的燃烧弹、莫洛托夫、诱饵弹（统一继承）；GrenadeThrownPayload
 *           （服务端生成实体）；客户端 GrenadeEntityRenderer（读取自转角度渲染）。
 */
public abstract class CounterStrikeGrenadeEntity extends ThrownItemEntity {

    // ===== 数据同步键：速度缓存/是否落地/是否已激活（均由服务端写入、客户端可读） =====
    public static final TrackedData<Float> SPEED =
            DataTracker.registerData(CounterStrikeGrenadeEntity.class, TrackedDataHandlerRegistry.FLOAT);
    public static final TrackedData<Boolean> IS_LANDED =
            DataTracker.registerData(CounterStrikeGrenadeEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
    public static final TrackedData<Boolean> IS_ACTIVATED =
            DataTracker.registerData(CounterStrikeGrenadeEntity.class, TrackedDataHandlerRegistry.BOOLEAN);

    /** 命中方块的弹跳音效（HE 覆盖为自己的金属弹跳声，其余用通用碰撞声） */
    public SoundEvent hitBlockSound = ModSounds.GRENADE_HIT.value();

    /** 投掷出手音效（onAdded 时服务端广播） */
    public SoundEvent throwSound = ModSounds.GRENADE_THROW.value();

    /** 上一次击中的实体 UUID（同一实体连续接触只反弹一次，防止抖动） */
    public java.util.UUID lastHitEntity = null;

    // ===== 客户端自转动画状态（渲染器每帧插值读取） =====
    public float customXRot, customYRot, customZRot;
    public float customXRotO, customYRotO, customZRotO;
    private float customXRotSpeed, customYRotSpeed, customZRotSpeed;

    public CounterStrikeGrenadeEntity(EntityType<? extends ThrownItemEntity> type, World world) {
        super(type, world);
        if (world.isClient) {
            randomizeRotation();
        }
    }

    // ===== 数据同步定义 =====

    // 【作用】声明 TrackedData 初始值（1.21.1 initDataTracker(Builder) 签名）
    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
        super.initDataTracker(builder);
        builder.add(SPEED, 0f);
        builder.add(IS_LANDED, false);
        builder.add(IS_ACTIVATED, false);
    }

    // ===== 物理与生命周期 =====

    // 【作用】击中实体：末影水晶直接打 1 点、生物做击退抗性临时抬高的 1 点碰撞伤害；随后按速度方向反弹
    @Override
    protected void onEntityHit(EntityHitResult result) {
        if (getWorld().isClient) {
            randomizeRotation();
        }

        Entity hit = result.getEntity();
        // 【作用】服务端结算碰撞伤害（客户端实体是幽灵副本，伤害只在服务端有效）
        if (getWorld() instanceof ServerWorld) {
            if (hit.getType() == EntityType.END_CRYSTAL) {
                hit.damage(getWorld().getDamageSources().generic(), 1.0f);
            } else if (hit instanceof LivingEntity living) {
                EntityAttributeInstance knockbackAttr =
                        living.getAttributeInstance(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE);
                double original = knockbackAttr != null ? knockbackAttr.getBaseValue() : 0.0;
                if (knockbackAttr != null) {
                    knockbackAttr.setBaseValue(1.0);
                }
                living.damage(getHitDamageSource(living), 1.0f);
                if (knockbackAttr != null) {
                    knockbackAttr.setBaseValue(original);
                }
            }
        }

        // 【作用】同一实体只反弹一次；反弹方向 = 速度主轴方向的相反面
        if (this.lastHitEntity == null || !this.lastHitEntity.equals(hit.getUuid())) {
            Direction direction = cn.woshiikun_1145.mcmod.choco.cstmm.util.GrenadeMath
                    .snapToAxis(this.getVelocity()).getOpposite();
            bounce(direction, 0.1f, 0.08f);
            this.lastHitEntity = hit.getUuid();
        }
    }

    // 【作用】每 tick：已落地/已激活时冻结位移并关闭重力；未落地时做"速度极小且贴地"的落地判定；
    //         客户端推进自转动画（空中旋转、空气阻力 0.99）
    @Override
    public void tick() {
        super.tick();
        if (this.dataTracker.get(IS_LANDED) || this.dataTracker.get(IS_ACTIVATED)) {
            this.setVelocity(Vec3d.ZERO);
            this.setNoGravity(true);
        }

        // 【作用】更稳的落地判定：贴地且速度平方小于 0.01^2
        if (!this.dataTracker.get(IS_LANDED) && !this.dataTracker.get(IS_ACTIVATED)) {
            if (this.isOnGround() && this.getVelocity().lengthSquared() < 0.01 * 0.01) {
                this.dataTracker.set(IS_LANDED, true);
            }
        }

        // 【作用】客户端自转：空中每 tick 递增并乘 0.99 空气阻力；落地/激活后停止
        if (this.getWorld().isClient) {
            boolean landed = this.dataTracker.get(IS_LANDED);
            if (!landed && !this.dataTracker.get(IS_ACTIVATED)) {
                this.customXRotO = this.customXRot;
                this.customYRotO = this.customYRot;
                this.customZRotO = this.customZRot;
                this.customXRot = (this.customXRot + customXRotSpeed) % 360;
                this.customYRot = (this.customYRot + customYRotSpeed) % 360;
                this.customZRot = (this.customZRot + customZRotSpeed) % 360;
                this.customXRotSpeed *= 0.99f;
                this.customYRotSpeed *= 0.99f;
                this.customZRotSpeed *= 0.99f;
            } else {
                this.customXRotSpeed = 0;
                this.customYRotSpeed = 0;
                this.customZRotSpeed = 0;
            }
        }
    }

    // 【作用】加入世界时的投掷破风声改由 NetworkHandler 在 spawnEntity 后用 world.playSound 广播
    //         （yarn 1.21.1 的 Entity 无 onAdded 生命周期钩子，原 mod 的 onAddedToWorld 无对应）

    // 【作用】命中方块的弹跳：忽略屏障可配置；回退到上一 tick 位置；按命中面反弹（速度系数 0.7、摩擦 0.9）；
    //         速度再整体乘 0.5；垂直命中且速度过小时判定落地。碰撞音效由客户端按距离衰减播放
    @Override
    protected void onBlockHit(BlockHitResult result) {
        if (getWorld().isClient) {
            randomizeRotation();
        }

        if (GrenadeSettings.ignoreBarrierBlock()
                && getWorld().getBlockState(result.getBlockPos()).getBlock() == Blocks.BARRIER) {
            return;
        }

        this.setPosition(this.prevX, this.prevY, this.prevZ);

        // 【作用】客户端按距离播放碰撞音（原 mod EntityBoundSoundInstance 的距离音量逻辑）
        if (this.getWorld().isClient && GrenadeHooks.client != null
                && !this.dataTracker.get(IS_ACTIVATED) && !this.dataTracker.get(IS_LANDED)) {
            GrenadeHooks.client.playDistanceSound(this, hitBlockSound,
                    SoundVolume.GRENADE_HIT_VOLUME, SoundVolume.GRENADE_HIT_RANGE);
        }

        if (this.dataTracker.get(IS_LANDED) || this.dataTracker.get(IS_ACTIVATED)) {
            return;
        }
        bounce(result.getSide(), 0.7f, 0.9f);
        this.setPosition(this.prevX, this.prevY, this.prevZ);
        this.setVelocity(this.getVelocity().multiply(0.5));

        // 【作用】垂直落在地面且反弹速度极小：直接静止并标记落地（防止反复微弹跳）
        if (result.getSide() == Direction.UP && this.getVelocity().length() < 0.05) {
            this.setVelocity(Vec3d.ZERO);
            this.dataTracker.set(IS_LANDED, true);
        }
    }

    // 【作用】把速度向量按命中面法线分解：法线方向取反乘速度系数，切向乘摩擦系数
    private void bounce(Direction direction, float speedCoefficient, float frictionFactor) {
        Vec3d v = this.getVelocity();
        switch (direction) {
            case UP, DOWN -> this.setVelocity(new Vec3d(
                    v.x * frictionFactor, -v.y * speedCoefficient, v.z * frictionFactor));
            case WEST, EAST -> this.setVelocity(new Vec3d(
                    -v.x * speedCoefficient, v.y * frictionFactor, v.z * frictionFactor));
            case NORTH, SOUTH -> this.setVelocity(new Vec3d(
                    v.x * frictionFactor, v.y * frictionFactor, -v.z * speedCoefficient));
        }
    }

    // 【作用】客户端碰撞时随机化自转角速度（每次碰撞翻滚方向不同）
    private void randomizeRotation() {
        this.customXRotSpeed = (this.random.nextFloat() - 0.5f) * 40;
        this.customYRotSpeed = (this.random.nextFloat() - 0.5f) * 40;
        this.customZRotSpeed = (this.random.nextFloat() - 0.5f) * 40;
    }

    // ===== 状态与约定 =====

    // 手雷实体不发光（原 mod isOnFire=false，防止投掷物自带火焰粒子）
    @Override
    public boolean isOnFire() {
        return false;
    }

    // 手雷不持久化（存档不保存，重启即消失——与原 mod shouldBeSaved=false 一致）
    @Override
    public boolean shouldSave() {
        return false;
    }

    /** 已激活（HE 已爆/烟雾已发/闪光已爆）判定，供子类与客户端使用。 */
    public boolean isActivated() {
        return this.dataTracker.get(IS_ACTIVATED);
    }

    /** 手雷中心点（实体位置 + 尺寸/2），供烟雾方块判定使用。 */
    public Vec3d center() {
        return this.getPos().add(
                GrenadeSettings.GRENADE_ENTITY_SIZE / 2.0,
                GrenadeSettings.GRENADE_ENTITY_SIZE / 2.0,
                GrenadeSettings.GRENADE_ENTITY_SIZE / 2.0);
    }

    /**
     * 【作用】碰撞伤害的伤害源（各手雷提供自己的伤害类型与归因）。
     * 【被谁使用】onEntityHit（击中生物时的 1 点伤害）。
     */
    protected abstract DamageSource getHitDamageSource(LivingEntity hitEntity);

    /**
     * 【作用】激活钩子（HE 爆炸/烟雾发烟/诱饵弹开始伪装音），阶段 1 由各子类自行处理。
     * 【被谁使用】各子类 tick。
     */
    protected void activate() {
    }

    /** 投掷物默认展示物品类型（渲染与掉落逻辑使用；yarn 的 ThrownItemEntity 契约返回 Item）。 */
    @Override
    protected abstract Item getDefaultItem();

    /**
     * 【作用】音量参数桥（原 mod SoundTypes 枚举中 phase 1 用到的值）。
     * 【被谁使用】CounterStrikeGrenadeEntity（碰撞音）、HEGrenadeEntity（爆炸音）、
     *           SmokeGrenadeEntity（发烟音）、客户端 HEExplosionEffects / GrenadeClientPayloads。
     */
    public static final class SoundVolume {
        /** 手雷碰撞音：初始音量 / 可听距离 */
        public static final double GRENADE_HIT_VOLUME = 0.09;
        public static final double GRENADE_HIT_RANGE = 30.0;

        /** HE 爆炸音（近距离）：初始音量 / 可听距离 */
        public static final double HE_EXPLODE_VOLUME = 0.15;
        public static final double HE_EXPLODE_RANGE = 50.0;

        /** HE 爆炸音（远距离）：初始音量 / 可听距离 */
        public static final double HE_EXPLODE_DISTANT_VOLUME = 3.0;
        public static final double HE_EXPLODE_DISTANT_RANGE = 100.0;

        /** 闪光爆炸音（近距离）：初始音量 / 可听距离 */
        public static final double FLASH_EXPLODE_VOLUME = 0.08;
        public static final double FLASH_EXPLODE_RANGE = 80.0;

        /** 闪光爆炸音（远距离）：初始音量 / 可听距离 */
        public static final double FLASH_EXPLODE_DISTANT_VOLUME = 0.5;
        public static final double FLASH_EXPLODE_DISTANT_RANGE = 80.0;

        /** 烟雾发烟音（近距离）：初始音量 / 可听距离 */
        public static final double SMOKE_EMIT_VOLUME = 0.1;
        public static final double SMOKE_EMIT_RANGE = 30.0;

        /** 烟雾发烟音（远距离）：初始音量 / 可听距离 */
        public static final double SMOKE_EXPLODE_DISTANT_VOLUME = 0.03;
        public static final double SMOKE_EXPLODE_DISTANT_RANGE = 80.0;

        /** 燃烧类落地起火音（近距离）：初始音量 / 可听距离 */
        public static final double INCENDIARY_EXPLODE_VOLUME = 0.15;
        public static final double INCENDIARY_EXPLODE_RANGE = 50.0;

        /** 燃烧类落地起火音（远距离）：初始音量 / 可听距离 */
        public static final double INCENDIARY_EXPLODE_DISTANT_VOLUME = 3.0;
        public static final double INCENDIARY_EXPLODE_DISTANT_RANGE = 100.0;

        /** 燃烧类空中自爆音（近距离）：初始音量 / 可听距离 */
        public static final double INCENDIARY_EXPLODE_AIR_VOLUME = 0.05;
        public static final double INCENDIARY_EXPLODE_AIR_RANGE = 80.0;

        /** 燃烧类熄灭噗声：初始音量 / 可听距离 */
        public static final double INCENDIARY_POP_VOLUME = 0.3;
        public static final double INCENDIARY_POP_RANGE = 30.0;

        private SoundVolume() {
        }
    }
}
