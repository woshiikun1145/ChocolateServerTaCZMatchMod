package cn.woshiikun_1145.mcmod.choco.cstmm.entity;

import cn.woshiikun_1145.mcmod.choco.cstmm.network.payload.FireGrenadePayload;
import cn.woshiikun_1145.mcmod.choco.cstmm.sound.ModSounds;
import cn.woshiikun_1145.mcmod.choco.cstmm.util.GrenadeHooks;
import cn.woshiikun_1145.mcmod.choco.cstmm.util.GrenadeMath;
import cn.woshiikun_1145.mcmod.choco.cstmm.util.GrenadeSettings;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageType;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.thrown.ThrownItemEntity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 【作用】燃烧类手雷（燃烧弹/莫洛托夫）实体基类（按 CounterStrikeGrenades 的
 *         AbstractFireGrenade 1:1 移植）：落地起火（地面水平面）或空中引信自爆；起火后按
 *         随机游走算法计算火焰占据方块——与原版一致放进 SynchedEntityData（SPREAD_BLOCKS，
 *         序列化器 BlockPosListDataHandler），客户端随实体生成/同步天然拿到方块列表；
 *         逐跳对火焰方块附近实体施加爬升伤害（25%→100% 线性）；烟雾覆盖可熄灭；
 *         火焰持续 FIRE_LIFETIME_MS 后消失。FireGrenadePayload 仅承载音效/空爆粒子事件
 *         （与原版 FireGrenadeMessage 同为 type + position）。与原版差异仅两处：
 *         寿命用墙钟计时（激活后不走 baseTick，age 冻结）；服务端激活状态每 tick 重新读取。
 * 【被谁使用】IncendiaryEntity / MolotovEntity（子类）；SmokeGrenadeEntity
 *           （落在火焰上立即发烟 / 发烟后熄灭火焰）；GrenadeThrownPayload（服务端生成）。
 */
public abstract class AbstractFireGrenadeEntity extends CounterStrikeGrenadeEntity {

    /**
     * 火焰占据方块（SynchedEntityData 同步，与原版 spreadBlocksAccessor 同机制）。
     * 服务端 igniteOnGround 写入；客户端（含实体出生时随生成包下发的初始值）直接读取。
     */
    public static final TrackedData<List<BlockPos>> SPREAD_BLOCKS =
            DataTracker.registerData(AbstractFireGrenadeEntity.class, BlockPosListDataHandler.INSTANCE);

    /** 起火时刻（墙钟）：激活后不再调用 super.tick()，age 会冻结，故不能用 age 计寿命 */
    private Instant explosionTime = null;

    /** 是否已被烟雾熄灭（熄灭后落地不再起火） */
    private boolean extinguished = false;

    /** 是否空中自爆（影响客户端渲染行为） */
    private boolean poppedInAir = false;

    /** key: 实体 UUID, value: 首次进入火焰范围的 Epoch 毫秒（伤害爬升计时） */
    private final Map<UUID, Long> entitiesLastInRange = new HashMap<>();

    /** 上一 tick 是否在水中（水面落地判定用） */
    private boolean lastInWater = false;

    // ===== 冻结自转（起火后客户端把自转角冻结） =====
    private boolean hasSavedFinalRotation = false;
    private float finalXRot, finalYRot, finalZRot;

    public AbstractFireGrenadeEntity(EntityType<? extends ThrownItemEntity> type, World world) {
        super(type, world);
        this.hitBlockSound = ModSounds.INCENDIARY_BOUNCE.value();
        this.throwSound = ModSounds.INCENDIARY_THROW.value();
        this.lastInWater = isInWaterNow();
    }

    // 【作用】声明本类新增的同步数据（在基类 ITEM/SPEED/IS_LANDED/IS_ACTIVATED 之后追加）
    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
        super.initDataTracker(builder);
        builder.add(SPREAD_BLOCKS, List.of());
    }

    // ===== 子类契约 =====

    /** 火焰伤害类型（燃烧弹/莫洛托夫各自不同，用于击杀归因）。 */
    protected abstract RegistryKey<DamageType> getFireDamageType();

    /** 火焰自伤伤害类型（无归因）。 */
    protected abstract RegistryKey<DamageType> getSelfFireDamageType();

    // ===== 主循环 =====

    // 【作用】tick：已起火 → 客户端冻结自转 + 逐 tick 火焰粒子；服务端逐跳伤害 + 寿命到期回收；
    //         未起火 → 物理 + 服务端空中引信自爆（AirExploded 广播）+ 水面落地即熄爆
    @Override
    public void tick() {
        boolean isExploded = this.dataTracker.get(IS_ACTIVATED);

        if (isExploded) {
            // 【作用】客户端：起火后冻结自转角
            if (this.getWorld().isClient) {
                if (!hasSavedFinalRotation) {
                    finalXRot = this.customXRot;
                    finalYRot = this.customYRot;
                    finalZRot = this.customZRot;
                    hasSavedFinalRotation = true;
                }
                this.customXRot = finalXRot;
                this.customYRot = finalYRot;
                this.customZRot = finalZRot;
                this.customXRotO = finalXRot;
                this.customYRotO = finalYRot;
                this.customZRotO = finalZRot;
                // 【作用】落地起火的实体逐 tick 渲染火焰粒子（空中自爆的实体已 discard 无此循环）
                if (!this.poppedInAir && GrenadeHooks.client != null) {
                    GrenadeHooks.client.renderFireGrenade(this);
                }
            }
        } else {
            super.tick();
        }

        // 【作用】服务端逻辑：起火后逐跳伤害与寿命回收；未起火时空中引信自爆
        if (this.getWorld() instanceof ServerWorld serverWorld) {
            // 【作用】必须重新读取激活状态：本 tick 的 super.tick() 碰撞回调可能刚把实体点燃
            //         （飞行时间超过引信时长后才落地时，若沿用开头缓存的 false 会误走空中自爆并 discard，
            //         表现为落地实体瞬间消失、地面无火焰）
            isExploded = this.dataTracker.get(IS_ACTIVATED);
            if (isExploded) {
                doDamage(serverWorld);
                if (this.explosionTime != null && Duration.between(this.explosionTime, Instant.now()).toMillis()
                        > GrenadeSettings.FIRE_LIFETIME_MS) {
                    this.discard();
                    return;
                }
            } else if (this.dataTracker.get(IS_LANDED) || this.isOnGround()) {
                // 【作用】兜底：已接触地面但未点燃（滚动/贴地休息时可能未再产生 UP 面命中），
                //         就地起火，避免落到地面后等到引信耗尽被当成空中自爆清除
                BlockPos feet = this.getBlockPos();
                BlockPos ground = this.getWorld().getBlockState(feet).isAir() ? feet.down() : feet;
                igniteOnGround(serverWorld, ground);
            } else if (this.age > GrenadeMath.millisToTicks(GrenadeSettings.FIRE_FUSE_TIME_MS)) {
                this.dataTracker.set(IS_ACTIVATED, true);
                this.poppedInAir = true;
                broadcast(serverWorld, FireGrenadePayload.TYPE_AIR_EXPLODED, this.getPos());
                this.discard();
            }
        }

        // 【作用】水面拦截：下落途中下一位置是流体 → 按落地处理（被水浇灭不发火）
        if (!isExploded) {
            if (!this.lastInWater && GrenadeMath.snapToAxis(this.getVelocity()) == Direction.DOWN) {
                Vec3d nextPosition = this.getPos().add(this.getVelocity());
                BlockPos nextBlockPos = BlockPos.ofFloored(nextPosition);
                boolean nextInWater = !this.getWorld().getBlockState(nextBlockPos).getFluidState().isEmpty();
                if (nextInWater) {
                    this.onBlockHit(new BlockHitResult(nextPosition, Direction.UP, nextBlockPos, false));
                    this.setVelocity(Vec3d.ZERO);
                }
            }
            this.lastInWater = isInWaterNow();
        }
    }

    // 【作用】命中方块（与原版 onHitBlock 守卫一致）：已激活或已落地或已熄灭时不再处理起火；
    //         命中 UP 面（水平面）→ 立即起火；非水平面命中走基类弹跳。
    //         说明：上一版曾去掉 landed 守卫试图修"永不起火"，经与原版逐行核对后确认原版
    //         行为即如此（正常投掷第一次落地时速度仍大、onBlockHit 先于自动落地判定触发），
    //         为保持一致性恢复原版守卫
    @Override
    protected void onBlockHit(BlockHitResult result) {
        if (this.dataTracker.get(IS_ACTIVATED) || this.dataTracker.get(IS_LANDED)) return;
        if (this.extinguished) return;
        if (result.getSide() == Direction.UP) {
            this.setVelocity(Vec3d.ZERO);
            this.setNoGravity(true);
            if (this.getWorld() instanceof ServerWorld serverWorld) {
                igniteOnGround(serverWorld, result.getBlockPos());
            }
            return;
        }
        super.onBlockHit(result);
    }

    /**
     * 【作用】服务端落地起火：贴合地面方块 → 标记激活/落地、检查烟雾熄灭、计算并广播火焰方块。
     * 【被谁使用】onBlockHit（UP 面命中）与 tick（已落地/贴地未点燃时的兜底）。
     * @param groundBlock 火焰起点所在的实体方块（地面方块本身，不是其上方的空气块）
     */
    private void igniteOnGround(ServerWorld serverWorld, BlockPos groundBlock) {
        if (this.dataTracker.get(IS_ACTIVATED)) return;
        this.setVelocity(Vec3d.ZERO);
        this.setNoGravity(true);
        // 【作用】防止火焰实体嵌进方块：上移到命中方块中心上方
        this.setPosition(groundBlock.toCenterPos()
                .add(0.0, 0.5 + GrenadeSettings.GRENADE_ENTITY_SIZE, 0.0));
        this.dataTracker.set(IS_ACTIVATED, true);
        this.dataTracker.set(IS_LANDED, true);
        this.explosionTime = Instant.now();
        // 【作用】已被烟雾熄灭：保持无火焰（SPREAD_BLOCKS 保持空列表），仅等待寿命到期回收
        if (this.extinguished) return;
        // 【作用】检查附近烟雾是否足以熄灭本火焰
        double smokeRadius = GrenadeSettings.SMOKE_RADIUS;
        Box bb = new Box(this.getBlockPos()).expand(
                smokeRadius, GrenadeSettings.SMOKE_MAX_FALLING_HEIGHT, smokeRadius);
        double fireExtinguishRange = GrenadeSettings.FIRE_EXTINGUISH_RANGE;
        for (SmokeGrenadeEntity smoke : serverWorld.getEntitiesByClass(
                SmokeGrenadeEntity.class, bb, e -> true)) {
            if (this.getPos().distanceTo(smoke.getPos()) < fireExtinguishRange
                    && smoke.canDistinguishFire(this.getPos())) {
                this.extinguished = true;
                return;
            }
        }
        // 【作用】写入 SynchedEntityData（原版 spreadBlocksAccessor 同机制）：客户端随
        //         生成包/同步包拿到火焰方块，不再依赖额外广播包
        this.dataTracker.set(SPREAD_BLOCKS, FireSpreadCalculator.calculate(serverWorld, groundBlock));
        broadcast(serverWorld, FireGrenadePayload.TYPE_GROUND_EXPLODED, this.getPos());
    }

    // 【作用】被烟雾熄灭（SmokeGrenadeEntity.extinguishNearbyFires 调用）：广播噗声事件并回收实体。
    //         注意：方法名刻意取 extinguishBySmoke 而非 extinguish，因为 Entity 基类在 1.21 起
    //         新增了 public void extinguish()（baseTick 中客户端每 tick 无条件调用以清零 fireTicks），
    //         若同名覆盖会导致客户端实体在首个 tick 即被当作烟雾熄灭而丢弃（表现为本体瞬灭、无火焰）。
    public void extinguishBySmoke() {
        this.extinguished = true;
        if (this.getWorld() instanceof ServerWorld serverWorld) {
            broadcast(serverWorld, FireGrenadePayload.TYPE_EXTINGUISHED_BY_SMOKE, this.getPos());
        }
        this.discard();
    }

    // 【作用】火焰当前占据方块（双侧统一读 SynchedEntityData，原版 entityData.get 同口径）
    public List<BlockPos> getSpreadBlocks() {
        return this.dataTracker.get(SPREAD_BLOCKS);
    }

    // ===== 伤害结算 =====

    // 【作用】服务端逐跳伤害：对每个火焰方块上方 1 格做水平 1 格内的实体命中判定（Y ±2.8，
    //         且实体不在烟雾中）；伤害从 25% 爬升到 100%（按首次接触时间线性）；自伤按策略走
    //         自伤伤害类型；每次结算后设置无敌帧 10 tick 并临时抬高击退抗性
    private void doDamage(ServerWorld world) {
        List<BlockPos> spreadBlocks = getSpreadBlocks();
        if (spreadBlocks.isEmpty()) {
            return;
        }

        Class<? extends LivingEntity> targetClass = GrenadeSettings.DAMAGE_NON_PLAYER_ENTITY
                ? LivingEntity.class
                : PlayerEntity.class;
        List<? extends LivingEntity> entities = world.getEntitiesByClass(targetClass,
                new Box(this.getBlockPos()).expand(GrenadeSettings.HE_DAMAGE_RANGE), e -> true);

        List<LivingEntity> entitiesInRange = new ArrayList<>();
        for (LivingEntity entity : entities) {
            for (BlockPos blockPos : spreadBlocks) {
                Vec3d fireCenter = blockPos.up().toCenterPos();
                double horizontal = Math.sqrt(
                        (fireCenter.x - entity.getX()) * (fireCenter.x - entity.getX())
                                + (fireCenter.z - entity.getZ()) * (fireCenter.z - entity.getZ()));
                if (horizontal < 1.0
                        && entity.getY() < blockPos.getY() + 2.8
                        && entity.getY() > blockPos.getY() - 2.8
                        && !isPositionInSmoke(this.getWorld(), entity.getPos())) {
                    entitiesInRange.add(entity);
                    break;
                }
            }
        }

        // 【作用】清理已离开火焰范围的实体计时条目
        this.entitiesLastInRange.keySet().removeIf(
                uuid -> entitiesInRange.stream().noneMatch(e -> e.getUuid().equals(uuid)));

        long timeNow = System.currentTimeMillis();
        float fullDamage = (float) GrenadeSettings.FIRE_DAMAGE;
        float minDamage = (float) (GrenadeSettings.FIRE_DAMAGE * GrenadeSettings.FIRE_MIN_DAMAGE_RATIO);

        for (LivingEntity entity : entitiesInRange) {
            DamageSource finalDamageSource = null;
            if (entity == this.getOwner()) {
                switch (GrenadeSettings.FIRE_CAUSE_DAMAGE_TO_OWNER) {
                    case NEVER -> { continue; }
                    case NOT_IN_TEAM -> finalDamageSource = createDamage(world,
                            getFireDamageType(), this, this.getOwner());
                    case ALWAYS -> finalDamageSource = createDamage(world,
                            getSelfFireDamageType(), null, null);
                }
            } else {
                finalDamageSource = createDamage(world, getFireDamageType(), this, this.getOwner());
            }

            if (finalDamageSource == null) {
                continue;
            }

            if (entity.timeUntilRegen > 0) {
                continue;
            }

            float damageToApply;
            Long startTime = this.entitiesLastInRange.get(entity.getUuid());
            if (startTime != null) {
                // 【作用】持续接触：伤害从最低爬升到满额（DAMAGE_INCREASE_TIME_MS 线性）
                double progress = (timeNow - startTime) / (double) GrenadeSettings.FIRE_DAMAGE_INCREASE_TIME_MS;
                damageToApply = (float) Math.min(fullDamage, GrenadeMath.linearInterpolate(minDamage, fullDamage, progress));
            } else {
                damageToApply = minDamage;
                this.entitiesLastInRange.put(entity.getUuid(), timeNow);
            }

            EntityAttributeInstance knockbackAttr =
                    entity.getAttributeInstance(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE);
            double original = knockbackAttr != null ? knockbackAttr.getBaseValue() : 0.0;
            if (knockbackAttr != null) {
                knockbackAttr.setBaseValue(1.0);
            }

            entity.damage(finalDamageSource, damageToApply);
            entity.timeUntilRegen = GrenadeSettings.FIRE_INVULNERABLE_TICKS;

            if (knockbackAttr != null) {
                knockbackAttr.setBaseValue(original);
            }
        }
    }

    // 【作用】碰撞伤害源：对自己无第三方归因，对他人携带投掷者归因
    @Override
    protected DamageSource getHitDamageSource(LivingEntity hitEntity) {
        if (hitEntity == this.getOwner()) {
            return createDamage((ServerWorld) this.getWorld(),
                    hitDamageType(), this, null);
        }
        return createDamage((ServerWorld) this.getWorld(),
                hitDamageType(), this, this.getOwner());
    }

    /** 碰撞伤害的伤害类型键（燃烧弹/莫洛托夫各自的 *_hit）。 */
    protected abstract RegistryKey<DamageType> hitDamageType();

    // ===== 工具 =====

    // 【作用】当前是否处于流体方块中
    private boolean isInWaterNow() {
        return !this.getWorld().getBlockState(this.getBlockPos()).getFluidState().isEmpty();
    }

    // 【作用】广播燃烧事件到全服（与原版 FireGrenadeMessage 同为 type + position，仅承载
    //         音效与空爆粒子事件；火焰方块走 SynchedEntityData 同步）
    private void broadcast(ServerWorld world, int type, Vec3d pos) {
        FireGrenadePayload payload = new FireGrenadePayload(type, pos);
        for (var player : world.getPlayers()) {
            ServerPlayNetworking.send(player, payload);
        }
    }

    /**
     * 【作用】判断某位置是否处于烟雾方块覆盖下（燃烧伤害与客户端火焰粒子共用；
     *         遍历世界中激活的烟雾实体并比对其扩散方块）。
     * 【被谁使用】doDamage（火焰中实体免伤）、客户端 FireGrenadeClientRenderer（烟雾中不生成火粒子）。
     */
    public static boolean isPositionInSmoke(World world, Vec3d pos) {
        BlockPos blockPos = BlockPos.ofFloored(pos);
        double smokeRadius = GrenadeSettings.SMOKE_RADIUS;
        double smokeFallingHeight = GrenadeSettings.SMOKE_MAX_FALLING_HEIGHT;
        for (SmokeGrenadeEntity smoke : world.getEntitiesByClass(SmokeGrenadeEntity.class,
                new Box(blockPos).expand(smokeRadius * 2.0,
                        smokeFallingHeight + smokeRadius * 2.0,
                        smokeRadius * 2.0),
                e -> true)) {
            if (smoke.getSpreadBlocks().contains(blockPos)) {
                return true;
            }
        }
        return false;
    }

    // 【作用】伤害源构造（委托 HEGrenadeEntity 的同名工具）
    private static DamageSource createDamage(ServerWorld world, RegistryKey<DamageType> key,
                                              Entity attacker, Entity source) {
        return HEGrenadeEntity.createDamageSource(world, key, attacker, source);
    }

    /**
     * 【作用】火焰随机游走蔓延计算器（移植自原 mod FireSpreadCalculator + SpreadPathData）：
     *         每条路径随机走向四方向（不回头），可向下找地面（最深 FIRE_MAX_SPREAD_DOWNWARD），
     *         平地跳跃最多 3 次；距离原点超出 FIRE_RANGE 的移动被拒绝。
     * 【被谁使用】AbstractFireGrenadeEntity.igniteOnGround。
     */
    private static final class FireSpreadCalculator {

        static List<BlockPos> calculate(ServerWorld world, BlockPos origin) {
            if (!world.getBlockState(origin.up()).getFluidState().isEmpty()) {
                return List.of(); // 原点上方是流体（水中）不起火
            }
            List<BlockPos> result = new ArrayList<>(List.of(origin));
            for (int path = 0; path < GrenadeSettings.FIRE_RANGE * 3; path++) {
                SpreadPathData pathData = new SpreadPathData(origin);
                for (int step = 0; step < GrenadeSettings.FIRE_RANGE; step++) {
                    pathData.randomMoveOnce(world);
                }
                result.addAll(pathData.visited);
            }
            return result.stream()
                    .filter(pos -> horizontalDist(pos, origin) < GrenadeSettings.FIRE_RANGE)
                    .distinct()
                    .toList();
        }

        // BlockPos 水平距离（忽略 Y）
        static double horizontalDist(BlockPos a, BlockPos b) {
            double dx = a.getX() - b.getX();
            double dz = a.getZ() - b.getZ();
            return Math.sqrt(dx * dx + dz * dz);
        }
    }

    /** 【作用】单条火焰蔓延路径状态（移植自原 mod SpreadPathData）。 */
    private static final class SpreadPathData {
        private static final Direction[] DIRECTIONS = {
                Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};

        private static final java.util.Random RANDOM = new java.util.Random();

        private final List<BlockPos> visited;
        private final BlockPos center;
        private BlockPos currentPos;
        private int jumpCount = 0;
        private Direction lastMoveDirection = null;

        SpreadPathData(BlockPos origin) {
            this.visited = new ArrayList<>(List.of(origin));
            this.center = origin;
            this.currentPos = origin;
        }

        // 【作用】随机移动 5 次尝试（不选上一次的方向）；成功移动记录方向
        void randomMoveOnce(ServerWorld level) {
            for (int i = 0; i < 5; i++) {
                Direction direction = getRandomDirection();
                if (tryMoveToDirection(level, direction)) {
                    this.lastMoveDirection = direction;
                }
            }
        }

        private Direction getRandomDirection() {
            if (this.lastMoveDirection != null) {
                List<Direction> options = new ArrayList<>(List.of(DIRECTIONS));
                options.remove(this.lastMoveDirection);
                return options.get(RANDOM.nextInt(options.size()));
            }
            return DIRECTIONS[RANDOM.nextInt(DIRECTIONS.length)];
        }

        // 【作用】尝试向指定方向移动：超范围拒绝 → 向下探测地面（无地面拒绝）→
        //         高度差 0 时需两侧空气且跳跃数 <3 → 已访问拒绝 → 记录并前进
        private boolean tryMoveToDirection(ServerWorld level, Direction direction) {
            BlockPos horizontalShifted = this.currentPos.offset(direction);
            if (FireSpreadCalculator.horizontalDist(horizontalShifted, this.center)
                    > GrenadeSettings.FIRE_RANGE) {
                return false;
            }
            BlockPos probe = horizontalShifted.up();
            BlockPos ground = getGroundBelow(level, probe);
            if (ground == null) {
                return false;
            }
            int fallDepth = probe.getY() - ground.getY();
            if (fallDepth == 0) {
                if (level.getBlockState(horizontalShifted.up().up()).isAir()
                        && level.getBlockState(this.currentPos.up()).isAir()
                        && this.jumpCount < 3) {
                    this.jumpCount++;
                } else {
                    return false;
                }
            }
            if (this.visited.contains(ground)) {
                return false;
            }
            this.visited.add(ground);
            this.currentPos = ground;
            return true;
        }

        // 【作用】从 origin 垂直向下探测最近地面（ShapeContext.absent() 避免 yarn 的 null NPE）；
        //         返回射线命中的实体方块坐标（原版 clipResult.blockPos 语义：垂直命中顶面时
        //         命中点 Y=方块Y+1，若按命中点取整会错拿地面上方的空气块，导致火焰整体悬空一格）
        private BlockPos getGroundBelow(ServerWorld level, BlockPos origin) {
            HitResult clipResult = level.raycast(new RaycastContext(
                    origin.toCenterPos(),
                    origin.add(0, -GrenadeSettings.FIRE_MAX_SPREAD_DOWNWARD, 0).toCenterPos(),
                    RaycastContext.ShapeType.COLLIDER,
                    RaycastContext.FluidHandling.ANY,
                    ShapeContext.absent()));
            if (clipResult.getType() == HitResult.Type.MISS
                    || !(clipResult instanceof BlockHitResult blockHit)) {
                return null;
            }
            return blockHit.getBlockPos();
        }
    }
}
