package cn.woshiikun_1145.mcmod.choco.cstmm.entity;

import cn.woshiikun_1145.mcmod.choco.cstmm.Cstmm;
import cn.woshiikun_1145.mcmod.choco.cstmm.item.ModItems;
import cn.woshiikun_1145.mcmod.choco.cstmm.network.payload.GrenadePayloads.SmokeSpreadPayload;
import cn.woshiikun_1145.mcmod.choco.cstmm.util.GrenadeDamageTypes;
import cn.woshiikun_1145.mcmod.choco.cstmm.util.GrenadeHooks;
import cn.woshiikun_1145.mcmod.choco.cstmm.util.GrenadeMath;
import cn.woshiikun_1145.mcmod.choco.cstmm.util.GrenadeSettings;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.AbstractSignBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChainBlock;
import net.minecraft.block.FenceBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.PaneBlock;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.block.WallBlock;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.SlabType;
import net.minecraft.block.enums.WallShape;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.projectile.thrown.ThrownItemEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 【作用】烟雾弹投掷物实体（移植自 CounterStrikeGrenades 的 SmokeGrenadeEntity）：
 *         落地停稳后引信（500ms）触发发烟——服务端随机游走算法计算烟雾占据的方块集合
 *         （含"下垂/坍塌"形状修正），一次性全服广播；客户端生成烟雾粒子、
 *         处理箭矢/TACZ 子弹穿烟打散（透明度按距离渐恢复）；发烟后熄灭附近燃烧手雷；
 *         烟雾持续 20 秒后消失。烟雾弹落在燃烧手雷范围内时立即发烟。
 * 【被谁使用】GrenadeThrownPayload（服务端生成）、ModEntities.SMOKEGRENADE（注册）、
 *           HEGrenadeEntity（爆炸炸开烟雾）、客户端烟雾渲染与穿烟逻辑。
 */
public class SmokeGrenadeEntity extends CounterStrikeGrenadeEntity {

    /** 发烟后在服务端记录的激活时刻（烟雾寿命计时基准） */
    private Instant explosionTime = null;

    /** 激活前卡在方块缝里的静止 tick 计数（连续静止超时强制落地） */
    private int stationaryTicks = 0;

    // ===== 冻结自转（激活后客户端把自转角冻结在激活瞬间的值） =====
    private boolean hasSavedFinalRotation = false;
    private float finalXRot, finalYRot, finalZRot;

    // ===== 扩散方块缓存：服务端激活时写入；客户端收到 SmokeSpreadPayload 时写入 =====
    private List<BlockPos> spreadBlocksCache = null;

    public SmokeGrenadeEntity(EntityType<? extends ThrownItemEntity> type, World world) {
        super(type, world);
    }

    // 【作用】投掷物默认展示物品类型（smokegrenade 物品模型）
    @Override
    protected Item getDefaultItem() {
        return ModItems.SMOKEGRENADE;
    }

    // 【作用】tick 主流程：已激活 → 客户端冻结自转 + 穿烟打散；服务端寿命到期回收 + 熄灭附近火焰；
    //         未激活 → 物理推进 + 卡住强制落地 + 落地引信后激活（服务端计算并广播扩散方块）
    @Override
    public void tick() {
        if (this.dataTracker.get(IS_ACTIVATED)) {
            // 【作用】客户端：激活瞬间冻结自转角并逐 tick 强制回写（防止渲染抖动）
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
            }

            // 【作用】服务端：烟雾寿命到期回收实体；每 tick 尝试熄灭覆盖范围内火焰
            if (this.getWorld() instanceof ServerWorld serverWorld) {
                if (this.explosionTime != null && Duration.between(this.explosionTime, Instant.now()).toMillis()
                        > GrenadeSettings.SMOKE_LIFETIME_MS) {
                    this.discard();
                }
                extinguishNearbyFires();
            }
            // 【作用】客户端：箭矢/TACZ 子弹穿烟打散（经客户端桥执行，common 无法 import ClientWorld）
            if (this.getWorld().isClient && GrenadeHooks.client != null) {
                GrenadeHooks.client.disperseSmokeByProjectiles(this);
            }
            return;
        }

        super.tick();

        // 【作用】兜底强制落地：未落地但速度极小且位置不动（卡在半砖/楼梯缝里），连续 1 秒后强制落地
        if (!this.dataTracker.get(IS_LANDED)) {
            boolean stuck = this.getVelocity().lengthSquared() < 0.0025
                    && this.getPos().equals(new Vec3d(this.prevX, this.prevY, this.prevZ));
            if (stuck) {
                stationaryTicks++;
            } else {
                stationaryTicks = 0;
            }
            if (stationaryTicks > 20) {
                this.setVelocity(Vec3d.ZERO);
                this.dataTracker.set(IS_LANDED, true);
                this.setNoGravity(true);
                stationaryTicks = 0;
            }
        }

        // 【作用】落地后引信：位置静止满 SMOKE_FUSE_TIME_AFTER_LANDING_MS → 激活
        //         仅服务端执行激活（客户端修复：此前强转 ServerWorld 在客户端 CCE 崩溃；
        //         客户端粒子由 SmokeSpreadPayload 广播触达后经 onSmokeEmit 启动）
        if (this.dataTracker.get(IS_LANDED)) {
            boolean still = this.getPos().equals(new Vec3d(this.prevX, this.prevY, this.prevZ));
            if (still) {
                stationaryTicks++;
            } else {
                stationaryTicks = 0;
            }
            if (stationaryTicks > GrenadeMath.millisToTicks(GrenadeSettings.SMOKE_FUSE_TIME_AFTER_LANDING_MS)
                    && this.explosionTime == null
                    && this.getWorld() instanceof ServerWorld serverWorld) {
                activateSmoke(serverWorld);
            }
        }
    }

    // 【作用】服务端激活发烟：计算扩散方块（含坍塌形状）、广播 SmokeSpreadPayload、
    //         清空展示物品（粒子由客户端按方块列表渲染）、标记激活、记录激活时刻
    private void activateSmoke(ServerWorld world) {
        List<BlockPos> spreadBlocks = calculateSpreadBlocks(world);
        this.spreadBlocksCache = spreadBlocks;
        SmokeSpreadPayload payload = new SmokeSpreadPayload(this.getId(), spreadBlocks);
        for (var player : world.getPlayers()) {
            ServerPlayNetworking.send(player, payload);
        }
        this.setItem(ItemStack.EMPTY);
        this.dataTracker.set(IS_ACTIVATED, true);
        this.explosionTime = Instant.now();
    }

    // 【作用】命中方块（与原版 onHitBlock 对齐）：先走基类弹跳；命中 UP 面且附近有可熄灭火焰时
    //         立即标记落地（烟雾落在火上马上发烟），服务端且命中点在碰撞箱内时把实体提到方块顶
    @Override
    protected void onBlockHit(BlockHitResult result) {
        super.onBlockHit(result);
        if (result.getSide() == Direction.UP) {
            if (extinguishNearbyFires() > 0) {
                this.dataTracker.set(IS_LANDED, true);
                if (this.getWorld() instanceof ServerWorld && result.isInsideBlock()) {
                    this.setPosition(new Vec3d(this.getX(), result.getBlockPos().getY() + 1.0, this.getZ()));
                }
            }
        }
    }

    // 【作用】熄灭附近火焰：已激活时以烟雾半径为界，未激活时以火焰射程为界；
    //         只有火焰占据方块与烟雾弹距离 <2 格（或已激活后与烟雾方块相交）才熄灭；
    //         返回熄灭数量（onBlockHit 用它判断是否立即发烟）
    private int extinguishNearbyFires() {
        List<AbstractFireGrenadeEntity> extinguished;
        double smokeRadius = GrenadeSettings.SMOKE_RADIUS;
        double smokeFallingHeight = GrenadeSettings.SMOKE_MAX_FALLING_HEIGHT;
        if (this.dataTracker.get(IS_ACTIVATED)) {
            Box bb = new Box(this.getBlockPos()).expand(smokeRadius, smokeFallingHeight, smokeRadius);
            extinguished = this.getWorld().getEntitiesByClass(AbstractFireGrenadeEntity.class, bb,
                    it -> it.isActivated() && canDistinguishFire(it.getPos()));
        } else {
            extinguished = this.getWorld().getEntitiesByClass(AbstractFireGrenadeEntity.class,
                    new Box(this.getBlockPos()).expand(GrenadeSettings.FIRE_RANGE),
                    it -> it.isActivated() && it.getSpreadBlocks().stream()
                            // 【作用】判定点是火焰所在位置（地面方块上方一格的中心），与原版 pos.above().center 一致
                            .anyMatch(pos -> pos.up().toCenterPos().squaredDistanceTo(this.getPos()) < 2.0));
        }
        // 【作用】与原版一致：只有服务端才真正执行熄灭（客户端实体是幽灵副本，
        //         熄灭广播由服务端 extinguishBySmoke() 发出，客户端重复调用会造成状态错乱）
        if (this.getWorld() instanceof ServerWorld) {
            for (AbstractFireGrenadeEntity fire : extinguished) {
                fire.extinguishBySmoke();
            }
        }
        return extinguished.size();
    }

    // 【作用】碰撞伤害源：对自己无第三方归因，对他人携带投掷者归因
    @Override
    protected DamageSource getHitDamageSource(LivingEntity hitEntity) {
        if (hitEntity == this.getOwner()) {
            return HEGrenadeEntity.createDamageSource((ServerWorld) this.getWorld(),
                    GrenadeDamageTypes.SMOKEGRENADE_HIT, this, null);
        }
        return HEGrenadeEntity.createDamageSource((ServerWorld) this.getWorld(),
                GrenadeDamageTypes.SMOKEGRENADE_HIT, this, this.getOwner());
    }

    // ===== 扩散方块计算 =====

    /**
     * 【作用】取扩散方块列表（服务端激活时/客户端收到广播时各写一次缓存）。
     * 【被谁使用】extinguishNearbyFires（火焰相交判定）、canDistinguishFire、客户端穿烟渲染。
     */
    public List<BlockPos> getSpreadBlocks() {
        return spreadBlocksCache == null ? List.of() : spreadBlocksCache;
    }

    // 【作用】客户端回写广播的扩散方块（SmokeSpreadPayload 接收方调用）
    public void setClientSpreadBlocks(List<BlockPos> blocks) {
        this.spreadBlocksCache = blocks;
    }

    // 【作用】火焰/子弹能否在此位置被本烟雾"看到"：任一扩散方块中心与该位置距离平方 <2
    public boolean canDistinguishFire(Vec3d position) {
        for (BlockPos pos : getSpreadBlocks()) {
            if (pos.toCenterPos().squaredDistanceTo(position) < 2.0) {
                return true;
            }
        }
        return false;
    }

    // 【作用】计算烟雾占据的方块集合：先找出生点周围可发烟的空气块（细节方块语义判定），
    //         对每个候选空气块跑随机游走（5 轮 × 每轮 1500 次 × 每次 2 步）取最小集合为初始烟雾；
    //         再按列统计下方支撑：≥30% 列触地时应用"中心加权坍塌"（远端少下垂、中心多下垂），否则保持球形
    private List<BlockPos> calculateSpreadBlocks(ServerWorld world) {
        List<BlockPos> surroundingAir = getSurroundingAirBlocks(world);
        if (surroundingAir.isEmpty()) {
            Cstmm.LOGGER.warn("[CSTMM - Grenade] Smoke grenade at {} found no air blocks to emit smoke", this.getBlockPos());
            return List.of();
        }

        // 【作用】用能生成最小烟雾的空气块作为发烟原点（原 mod 语义：取所有候选中最小的初始集合）
        Set<BlockPos> initialSmoke = null;
        for (BlockPos origin : surroundingAir) {
            Set<BlockPos> candidate = new SmokeGrenadeSpreadBlockCalculator(5, 1500, 2, origin)
                    .calculate(world);
            if (candidate.isEmpty()) {
                continue;
            }
            if (initialSmoke == null || candidate.size() < initialSmoke.size()) {
                initialSmoke = candidate;
            }
        }
        if (initialSmoke == null || initialSmoke.isEmpty()) {
            return List.of();
        }

        int maxFallHeight = GrenadeSettings.SMOKE_MAX_FALLING_HEIGHT;
        // 【作用】按 (x,z) 列分组，统计每列原始下垂距离与是否触地
        Map<Long, List<BlockPos>> smokeColumns = new LinkedHashMap<>();
        for (BlockPos pos : initialSmoke) {
            smokeColumns.computeIfAbsent(columnKey(pos), k -> new ArrayList<>()).add(pos);
        }
        int totalColumnCount = Math.max(smokeColumns.size(), 1);

        Map<Long, Integer> columnFallInfo = new LinkedHashMap<>();
        int landingColumnCount = 0;
        for (Map.Entry<Long, List<BlockPos>> entry : smokeColumns.entrySet()) {
            List<BlockPos> columnBlocks = entry.getValue();
            BlockPos lowestBlock = columnBlocks.stream()
                    .min((a, b) -> Integer.compare(a.getY(), b.getY())).orElse(null);
            if (lowestBlock == null) {
                continue;
            }
            int fallDistance = 0;
            boolean hitGround = false;
            BlockPos currentPos = lowestBlock;
            for (int i = 0; i < maxFallHeight; i++) {
                BlockPos below = currentPos.down();
                if (world.getBlockState(below).getCollisionShape(world, below).isEmpty()) {
                    fallDistance++;
                    currentPos = below;
                } else {
                    hitGround = true;
                    break;
                }
            }
            columnFallInfo.put(entry.getKey(), fallDistance);
            if (hitGround) {
                landingColumnCount++;
            }
        }

        // 【作用】支撑率 <30% → 保持球形不下垂
        double landingPercentage = (double) landingColumnCount / totalColumnCount;
        if (landingPercentage < 0.3) {
            return new ArrayList<>(initialSmoke);
        }

        // 【作用】坍塌修正：每列最终下垂 = 原始下垂 × (1-权重) + 中心列下垂 × 权重，权重随距中心距离线性衰减
        Set<BlockPos> finalSmoke = new HashSet<>();
        BlockPos grenadePos = this.getBlockPos();
        long centerKey = columnKey(grenadePos);
        int centerFallDistance = columnFallInfo.getOrDefault(centerKey, 0);

        double maxDist = 1.0;
        for (long key : smokeColumns.keySet()) {
            double dist = Math.hypot(unpackColumnX(key) - grenadePos.getX(), unpackColumnZ(key) - grenadePos.getZ());
            if (dist > maxDist) {
                maxDist = dist;
            }
        }

        for (Map.Entry<Long, List<BlockPos>> entry : smokeColumns.entrySet()) {
            int rawFallDistance = columnFallInfo.getOrDefault(entry.getKey(), 0);
            double distFromCenter = Math.hypot(
                    unpackColumnX(entry.getKey()) - grenadePos.getX(),
                    unpackColumnZ(entry.getKey()) - grenadePos.getZ());
            double weight = Math.min(Math.max(1.0 - (distFromCenter / maxDist), 0.0), 1.0);
            int finalFallDistance = (int) Math.round(
                    rawFallDistance * (1.0 - weight) + centerFallDistance * weight);
            for (BlockPos block : entry.getValue()) {
                finalSmoke.add(block.down(finalFallDistance));
            }
        }
        return new ArrayList<>(finalSmoke);
    }

    // 【作用】(x,z) 列键打包（低位 x、高位 z）
    private static long columnKey(BlockPos pos) {
        return ((long) pos.getZ() << 32) ^ (pos.getX() & 0xFFFFFFFFL);
    }

    private static int unpackColumnX(long key) {
        return (int) key;
    }

    private static int unpackColumnZ(long key) {
        return (int) (key >> 32);
    }

    // 【作用】找出生点所在方块的"可发烟空气块"：
    //         普通空气 → 自身；栅栏/铁链/告示牌 → 六邻空气；楼梯 → 上下 + 朝向两侧；
    //         台阶 → 邻居空气（去掉与实心层同侧的）；活板门 → 按开合/上下半排除被挡侧；
    //         玻璃板/铁栏杆与墙 → 按手雷所在角落的"非阻断邻居"算法精确建模；
    //         含水方块 → 不发烟；其余（按钮/地毯等）→ 兜底取六邻空气
    private List<BlockPos> getSurroundingAirBlocks(ServerWorld world) {
        BlockPos blockAt = BlockPos.ofFloored(this.center());
        BlockState state = world.getBlockState(blockAt);

        // 【作用】含水方块（含水 log/海绵等）不发烟
        if (state.getOrEmpty(Properties.WATERLOGGED).orElse(false)) {
            return List.of();
        }

        // 【作用】普通空气：直接以自身为发烟原点
        if (state.isAir()) {
            return List.of(blockAt);
        }

        if (state.getBlock() instanceof FenceBlock
                || state.getBlock() instanceof FenceGateBlock
                || state.getBlock() instanceof ChainBlock
                || state.getBlock() instanceof AbstractSignBlock) {
            return adjacent(blockAt).stream().filter(p -> world.getBlockState(p).isAir()).toList();
        }

        if (state.getBlock() instanceof StairsBlock) {
            List<BlockPos> result = new ArrayList<>();
            BlockPos vertical = state.get(Properties.BLOCK_HALF) == BlockHalf.TOP ? blockAt.down() : blockAt.up();
            if (world.getBlockState(vertical).isAir()) {
                result.add(vertical);
            }
            for (BlockPos side : horizontalSides(state.get(Properties.HORIZONTAL_FACING), blockAt)) {
                if (world.getBlockState(side).isAir()) {
                    result.add(side);
                }
            }
            return result;
        }

        if (state.getBlock() instanceof SlabBlock) {
            List<BlockPos> adjacent = new ArrayList<>(adjacent(blockAt));
            SlabType type = state.get(Properties.SLAB_TYPE);
            if (type == SlabType.TOP) {
                adjacent.remove(blockAt.up());
            } else if (type == SlabType.BOTTOM) {
                adjacent.remove(blockAt.down());
            } else {
                return List.of(); // 双层台阶视为实心
            }
            return adjacent.stream().filter(p -> world.getBlockState(p).isAir()).toList();
        }

        if (state.getBlock() instanceof TrapdoorBlock) {
            List<BlockPos> result = new ArrayList<>(adjacent(blockAt));
            if (state.get(Properties.OPEN)) {
                Direction facing = state.get(Properties.HORIZONTAL_FACING);
                switch (facing) {
                    case NORTH -> result.remove(blockAt.south());
                    case SOUTH -> result.remove(blockAt.north());
                    case WEST -> result.remove(blockAt.east());
                    case EAST -> result.remove(blockAt.west());
                    default -> { /* 上/下不可能 */ }
                }
            } else {
                BlockPos half = state.get(Properties.BLOCK_HALF) == BlockHalf.TOP ? blockAt.down() : blockAt.up();
                result.remove(half);
            }
            return result.stream().filter(p -> world.getBlockState(p).isAir()).toList();
        }

        // 【作用】玻璃板/铁栏杆（PaneBlock 覆盖原 mod 的 IronBarsBlock + Tags.GLASS_PANES）：
        //         按四向连接状态与手雷所在角落取非阻断邻居（原 mod ExtendableBlockState 精确建模）
        if (state.getBlock() instanceof PaneBlock) {
            ExtendableBlockState extendable = new ExtendableBlockState(
                    state.get(Properties.NORTH),
                    state.get(Properties.SOUTH),
                    state.get(Properties.WEST),
                    state.get(Properties.EAST));
            return nonBlockingAdjacentForCorner(blockAt, extendable,
                            getGrenadeCornerType(blockAt, this.center()))
                    .stream().filter(p -> world.getBlockState(p).isAir()).toList();
        }

        // 【作用】墙：Low/Tall 连接都视为阻挡（原版注释），同用角落非阻断算法
        if (state.getBlock() instanceof WallBlock) {
            ExtendableBlockState extendable = new ExtendableBlockState(
                    state.get(Properties.NORTH_WALL_SHAPE) != WallShape.NONE,
                    state.get(Properties.SOUTH_WALL_SHAPE) != WallShape.NONE,
                    state.get(Properties.WEST_WALL_SHAPE) != WallShape.NONE,
                    state.get(Properties.EAST_WALL_SHAPE) != WallShape.NONE);
            return nonBlockingAdjacentForCorner(blockAt, extendable,
                            getGrenadeCornerType(blockAt, this.center()))
                    .stream().filter(p -> world.getBlockState(p).isAir()).toList();
        }

        // 【作用】兜底（按钮/地毯等未逐一建模的方块）：取六邻空气
        return adjacent(blockAt).stream().filter(p -> world.getBlockState(p).isAir()).toList();
    }

    // 【作用】楼梯朝向 → 两侧相邻水平位（与原 mod 的朝向映射一致）
    private static List<BlockPos> horizontalSides(Direction facing, BlockPos center) {
        return switch (facing) {
            case NORTH -> List.of(center.south(), center.west(), center.east());
            case SOUTH -> List.of(center.north(), center.west(), center.east());
            case WEST -> List.of(center.north(), center.south(), center.east());
            case EAST -> List.of(center.north(), center.south(), center.west());
            default -> List.of();
        };
    }

    // 【作用】六邻坐标
    private static Set<BlockPos> adjacent(BlockPos pos) {
        return Set.of(pos.up(), pos.down(), pos.north(), pos.south(), pos.west(), pos.east());
    }

    // ===== 玻璃板/墙的角落建模工具（移植自原 mod Corner + ExtendableBlockState + getGrenadeCornerType） =====

    /** 【作用】手雷相对方块中心的角落方位（决定玻璃板/墙两侧的"开口"方向）。 */
    private enum Corner {
        SOUTHWEST(Direction.SOUTH, Direction.WEST),
        SOUTHEAST(Direction.SOUTH, Direction.EAST),
        NORTHWEST(Direction.NORTH, Direction.WEST),
        NORTHEAST(Direction.NORTH, Direction.EAST);

        final Direction main;
        final Direction secondary;

        Corner(Direction main, Direction secondary) {
            this.main = main;
            this.secondary = secondary;
        }
    }

    /**
     * 【作用】四向连接状态容器（玻璃板/墙通用，对应原 mod ExtendableBlockState）。
     * 【被谁使用】getSurroundingAirBlocks 构造、nonBlockingAdjacentForCorner 查询。
     */
    private record ExtendableBlockState(boolean north, boolean south, boolean west, boolean east) {

        boolean get(Direction direction) {
            return switch (direction) {
                case NORTH -> this.north;
                case SOUTH -> this.south;
                case WEST -> this.west;
                case EAST -> this.east;
                default -> throw new IllegalArgumentException("No horizontal property for " + direction);
            };
        }
    }

    /**
     * 【作用】按手雷中心相对方块中心的偏移判定角落方位（原 mod getGrenadeCornerType）。
     * 【被谁使用】getSurroundingAirBlocks 的玻璃板/墙分支。
     */
    private static Corner getGrenadeCornerType(BlockPos blockPos, Vec3d position) {
        Vec3d relative = position.subtract(Vec3d.ofCenter(blockPos));
        if (relative.z > 0) {
            return relative.x > 0 ? Corner.SOUTHEAST : Corner.SOUTHWEST;
        }
        return relative.x > 0 ? Corner.NORTHEAST : Corner.NORTHWEST;
    }

    /**
     * 【作用】角落非阻断邻居集合（原 mod ExtendableBlockState.nonBlockingAdjacentForCorner）：
     *         角落的两个连接方向邻居必加；仅当角落方向形成"贯通连接"（连接了角落两侧）
     *         时才排除对面邻居——贴近墙/玻璃板角落投烟时，开口侧照常发烟。
     * 【被谁使用】getSurroundingAirBlocks 的玻璃板/墙分支。
     */
    private static Set<BlockPos> nonBlockingAdjacentForCorner(BlockPos blockPos, ExtendableBlockState state,
                                                              Corner corner) {
        Set<BlockPos> result = new HashSet<>(Set.of(
                blockPos.offset(corner.main), blockPos.offset(corner.secondary)));
        boolean mainConnected = state.get(corner.main);
        boolean secondaryConnected = state.get(corner.secondary);
        if (!(secondaryConnected && mainConnected)
                && !(secondaryConnected && state.get(corner.secondary.getOpposite()))) {
            result.add(blockPos.offset(corner.main.getOpposite()));
        }
        if (!(mainConnected && secondaryConnected)
                && !(mainConnected && state.get(corner.main.getOpposite()))) {
            result.add(blockPos.offset(corner.secondary.getOpposite()));
        }
        return result;
    }

    /**
     * 【作用】烟雾随机游走方块计算器（移植自原 mod SmokeGrenadeSpreadBlockCalculator）：
     *         generateCycle 轮，每轮从上一轮集合随机取点游走 blockPerCycle 次（每次 stepPerMove 步，
     *         只能走入无碰撞方块），累积所有经过的方块。
     * 【被谁使用】SmokeGrenadeEntity.calculateSpreadBlocks。
     */
    private static final class SmokeGrenadeSpreadBlockCalculator {
        private final int generateCycle;
        private final int blockPerCycle;
        private final int stepPerMove;
        private final BlockPos origin;
        private final Random random = new Random();

        SmokeGrenadeSpreadBlockCalculator(int generateCycle, int blockPerCycle, int stepPerMove, BlockPos origin) {
            this.generateCycle = generateCycle;
            this.blockPerCycle = blockPerCycle;
            this.stepPerMove = stepPerMove;
            this.origin = origin;
        }

        Set<BlockPos> calculate(World world) {
            Set<BlockPos> blocks = new HashSet<>();
            Set<BlockPos> generatedInLastCycle = new HashSet<>(List.of(origin));
            Set<BlockPos> generatedInCurrentCycle = new HashSet<>();
            for (int cycle = 0; cycle < generateCycle; cycle++) {
                List<BlockPos> lastList = new ArrayList<>(generatedInLastCycle);
                for (int i = 0; i < blockPerCycle; i++) {
                    BlockPos currentLocation = lastList.get(random.nextInt(lastList.size()));
                    for (int step = 0; step < stepPerMove; step++) {
                        currentLocation = randomMoveOnce(world, currentLocation);
                    }
                    generatedInCurrentCycle.add(currentLocation);
                }
                blocks.addAll(generatedInLastCycle);
                generatedInLastCycle = generatedInCurrentCycle;
                generatedInCurrentCycle = new HashSet<>();
            }
            return blocks;
        }

        // 【作用】单步随机游走：按权重方向（1 上 / 2 下 / 2 北 / 2 南 / 2 西 / 2 东偏置）尝试移动，
        //         目标无碰撞方块才生效
        private BlockPos randomMoveOnce(World world, BlockPos blockPos) {
            BlockPos newLocation = switch (randomDirection()) {
                case UP -> blockPos.up();
                case DOWN -> blockPos.down();
                case NORTH -> blockPos.north();
                case SOUTH -> blockPos.south();
                case WEST -> blockPos.west();
                case EAST -> blockPos.east();
            };
            if (world.getBlockState(newLocation).getCollisionShape(world, newLocation).isEmpty()) {
                return newLocation;
            }
            return blockPos;
        }

        private Direction randomDirection() {
            return switch (random.nextInt(10)) {
                case 0 -> Direction.UP;
                case 1, 5 -> Direction.DOWN;
                case 2, 6 -> Direction.NORTH;
                case 3, 7 -> Direction.SOUTH;
                case 4, 8 -> Direction.WEST;
                default -> Direction.EAST;
            };
        }
    }
}
