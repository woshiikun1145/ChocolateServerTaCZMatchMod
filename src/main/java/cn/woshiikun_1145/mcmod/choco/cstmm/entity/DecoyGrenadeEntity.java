package cn.woshiikun_1145.mcmod.choco.cstmm.entity;

import cn.woshiikun_1145.mcmod.choco.cstmm.item.ModItems;
import cn.woshiikun_1145.mcmod.choco.cstmm.network.payload.GrenadePayloads.DecoyShotPayload;
import cn.woshiikun_1145.mcmod.choco.cstmm.util.GrenadeDamageTypes;
import cn.woshiikun_1145.mcmod.choco.cstmm.util.TaczGunDataProvider;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.projectile.thrown.ThrownItemEntity;
import net.minecraft.item.Item;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * 【作用】诱饵弹投掷物实体（移植自 CounterStrikeGrenades 的 DecoyGrenadeEntity）：
 *         落地后激活，服务端按 DecoyFirePatternGenerator 生成的射击时间戳逐发广播枪声
 *         （客户端优先播自定义音效，其次 TACZ 枪械音效，否则动物声 fallback），
 *         15 秒后产生 0.1 威力、不破坏方块的小爆炸并丢弃；投掷时从投掷者背包
 *         查找第一把 TACZ 枪械决定射击节奏。无 TACZ 时静默（不响枪、仍定时爆炸）。
 * 【被谁使用】NetworkHandler（服务端生成）、ModEntities.DECOY（注册）、客户端手雷渲染器。
 */
public class DecoyGrenadeEntity extends CounterStrikeGrenadeEntity {

    // 【作用】同步枪械 ID 到客户端（原版 GUN_DATA_ACCESSOR；枪声本体经 DecoyShotPayload 下发）
    public static final TrackedData<String> GUN_DATA =
            DataTracker.registerData(DecoyGrenadeEntity.class, TrackedDataHandlerRegistry.STRING);

    // 射击时间戳列表（服务端）：记录何时应该播一枪
    private List<Integer> fireTimestamps = List.of();
    // 下一次射击的时间戳索引
    private int nextFireTimestampIndex = 0;
    // 激活时的 tick 计数（null = 未激活）
    private Integer activationTick = null;
    // 投掷时查找的 TACZ 枪械数据（无 TACZ 为 null）
    private DecoyGunData gunData = null;
    // 自定义音效 ID（可空，来自物品 "DecoySound" 自定义数据）
    private String customSound = null;

    public DecoyGrenadeEntity(EntityType<? extends ThrownItemEntity> type, World world) {
        super(type, world);
    }

    // 【作用】声明 TrackedData 初始值（枪械 ID 默认空串）
    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
        super.initDataTracker(builder);
        builder.add(GUN_DATA, "");
    }

    // 【作用】投掷物默认展示物品类型（decoy 物品模型，实体渲染器据此渲染）
    @Override
    protected Item getDefaultItem() {
        return ModItems.DECOY;
    }

    // 【作用】每 tick：未落地走基类物理与自转；客户端落地后基类已冻结自转（等价原版 freezeRotation）直接返回；
    //         服务端落地后激活 → 按时间戳逐发开枪 → 超过总时长小爆炸并丢弃
    @Override
    public void tick() {
        super.tick();
        if (this.getWorld().isClient || !this.dataTracker.get(IS_LANDED)) {
            return;
        }

        // 【作用】服务端：落地后首次进入即激活
        if (activationTick == null) {
            activate();
        }

        if (activationTick != null) {
            // 【作用】当前生命周期内的 tick 数（对齐原版 tickCount - startTick）
            int currentTickInLifetime = this.age - activationTick;
            if (nextFireTimestampIndex < fireTimestamps.size()) {
                int nextShotTick = fireTimestamps.get(nextFireTimestampIndex);
                if (currentTickInLifetime >= nextShotTick) {
                    fireShot();
                    nextFireTimestampIndex++;
                }
            }
            // 【作用】生命周期结束：小爆炸（0.1 威力、不点火、不破坏方块）后丢弃
            if (currentTickInLifetime > DecoyConstants.TOTAL_DURATION_TICKS) {
                endOfLifeExplosion();
            }
        }
    }

    // 【作用】激活：记录激活 tick 并按枪械数据生成射击时间戳（客户端/重复激活跳过，原版逻辑）
    @Override
    protected void activate() {
        if (activationTick != null || this.getWorld().isClient) {
            return;
        }
        activationTick = this.age;
        this.fireTimestamps = DecoyFirePatternGenerator.generateFireTimestamps(this);
    }

    // 【作用】单发枪声广播：无枪械数据时静默（原版 gunId ?: return 语义）；
    //         有则向全服所有玩家发 DecoyShotPayload（对齐原版 PacketDistributor.ALL）
    private void fireShot() {
        if (gunData == null) {
            return;
        }
        CustomPayload payload = new DecoyShotPayload(this.getId(), gunData.gunId(), customSound);
        ServerWorld world = (ServerWorld) this.getWorld();
        if (world.getServer() != null) {
            for (ServerPlayerEntity player : world.getServer().getPlayerManager().getPlayerList()) {
                ServerPlayNetworking.send(player, payload);
            }
        }
    }

    /**
     * 【作用】投掷时从投掷者背包查找第一把 TACZ 枪械并设置射击数据，同步枪械 ID 到客户端。
     * 【被谁使用】NetworkHandler.handleGrenadeThrown（服务端生成实体后）。
     */
    public void findAndSetTaczGunIdOnThrow() {
        if (this.getOwner() instanceof ServerPlayerEntity owner) {
            this.gunData = TaczGunDataProvider.findGunDataFromPlayer(owner);
            if (gunData != null) {
                this.dataTracker.set(GUN_DATA, gunData.gunId());
            }
        }
    }

    // 【作用】设置自定义音效 ID（物品 "DecoySound" 自定义数据透传，可空）
    public void setCustomSound(String sound) {
        this.customSound = sound;
    }

    // 【作用】获取枪械数据（射击模式生成器使用）
    public DecoyGunData getGunData() {
        return gunData;
    }

    // 【作用】生命周期结束小爆炸：0.1 威力、不点火、ExplosionInteraction.NONE 对应（不破坏方块），随后丢弃
    private void endOfLifeExplosion() {
        if (!this.getWorld().isClient) {
            this.getWorld().createExplosion(this, this.getX(), this.getY(), this.getZ(),
                    0.1f, false, World.ExplosionSourceType.NONE);
        }
        this.discard();
    }

    // 【作用】碰撞伤害源：decoy_hit 伤害类型，对自己无第三方归因，对他人携带投掷者归因（计入击杀统计）
    @Override
    protected DamageSource getHitDamageSource(LivingEntity hitEntity) {
        if (hitEntity == this.getOwner()) {
            return HEGrenadeEntity.createDamageSource((ServerWorld) this.getWorld(),
                    GrenadeDamageTypes.DECOY_HIT, this, null);
        }
        return HEGrenadeEntity.createDamageSource((ServerWorld) this.getWorld(),
                GrenadeDamageTypes.DECOY_HIT, this, this.getOwner());
    }

    /**
     * 【作用】诱饵弹模拟的枪械数据（移植自 CounterStrikeGrenades 的 DecoyGunData）：
     *         枪械 ID / 射击模式（TACZ FireMode 枚举名，如 "AUTO"/"SEMI"）/ 射速 RPM / 射击间隔毫秒。
     *         fireMode 以字符串存储，避免对 TACZ 类型产生编译期依赖（运行时经反射取得）。
     * 【被谁使用】DecoyGrenadeEntity（fireShot 使用 gunId、有效性判定）、
     *           DecoyFirePatternGenerator（按 fireMode 选择连发/半自动时间戳生成）、
     *           util/TaczGunDataProvider（反射构造）。
     */
    public record DecoyGunData(String gunId, String fireMode, int rpm, int shootIntervalMs) {

        /** 数据有效性：枪械 ID 非空白、射速与射击间隔均为正（与原版一致） */
        public boolean isValid() {
            return gunId != null && !gunId.isBlank() && rpm > 0 && shootIntervalMs > 0;
        }
    }

    /**
     * 【作用】诱饵弹相关常量（移植自 CounterStrikeGrenades 的 DecoyConstants，数值逐值一致）。
     * 【被谁使用】DecoyGrenadeEntity（生命周期与爆炸时机）、DecoyFirePatternGenerator（射击时间戳生成）。
     */
    public static final class DecoyConstants {

        /** 诱饵弹总持续时间（15 秒 = 300 tick） */
        public static final int TOTAL_DURATION_TICKS = 15 * 20;

        /** 最小爆发间延迟（25 tick ≈ 1.25 秒） */
        public static final int MIN_BURST_DELAY_TICKS = 25;

        /** 最大爆发间延迟（60 tick ≈ 3 秒） */
        public static final int MAX_BURST_DELAY_TICKS = 60;

        /** 单次爆发中最小射击次数 */
        public static final int MIN_SHOTS_IN_BURST = 2;

        /** 单次爆发中最大射击次数 */
        public static final int MAX_SHOTS_IN_BURST = 5;

        private DecoyConstants() {
        }
    }

    /**
     * 【作用】诱饵弹射击模式生成器（移植自 CounterStrikeGrenades 的 DecoyFirePatternGenerator）：
     *         按枪械射击模式生成"何时该放一枪"的时间戳列表（tick）：
     *         AUTO → 爆发式连发（段内按射击间隔连响 2-5 发），其余（SEMI 等）→ 单点节奏；
     *         无有效枪械数据时退回半自动节奏（fallback）。
     * 【被谁使用】DecoyGrenadeEntity.activate（落地激活时生成时间戳）。
     */
    public static final class DecoyFirePatternGenerator {

        private DecoyFirePatternGenerator() {
        }

        /**
         * 【作用】生成射击时间戳列表：按枪械模式分派（AUTO→连发，其余→半自动；无效数据→fallback 半自动）。
         * 【被谁使用】DecoyGrenadeEntity.activate。
         */
        public static List<Integer> generateFireTimestamps(DecoyGrenadeEntity entity) {
            DecoyGunData gunData = entity.getGunData();
            if (gunData == null || !gunData.isValid()) {
                return generateSemiTimestamps(entity.getWorld().getRandom());
            }
            if ("AUTO".equals(gunData.fireMode())) {
                return generateAutoTimestamps(gunData, entity.getWorld().getRandom());
            }
            return generateSemiTimestamps(entity.getWorld().getRandom());
        }

        // 【作用】连发模式时间戳：首段延迟减半（增加真实性），每段随机 2-5 发
        //         （段内按 shootIntervalMs/50 = tick 间隔），段间延迟 25-60 tick，持续生成直至总时长上限
        private static List<Integer> generateAutoTimestamps(DecoyGunData gunData, Random random) {
            List<Integer> timestamps = new ArrayList<>();
            int currentTime = 0;
            double shootIntervalTicks = Math.max(1.0, gunData.shootIntervalMs() / 50.0);

            currentTime += getRandomBurstDelay(random, true);

            while (currentTime < DecoyConstants.TOTAL_DURATION_TICKS) {
                // 【作用】随机生成爆发中的射击次数（kotlin nextInt(min, max+1) 复刻为闭区间取值）
                int shotsInBurst = random.nextInt(
                        DecoyConstants.MAX_SHOTS_IN_BURST - DecoyConstants.MIN_SHOTS_IN_BURST + 1)
                        + DecoyConstants.MIN_SHOTS_IN_BURST;
                for (int i = 0; i < shotsInBurst; i++) {
                    int shotTime = currentTime + (int) (i * shootIntervalTicks);
                    if (shotTime < DecoyConstants.TOTAL_DURATION_TICKS) {
                        timestamps.add(shotTime);
                    } else {
                        break;
                    }
                }
                // 【作用】更新当前时间到最后一次射击，再添加爆发间延迟（原版顺序）
                currentTime = timestamps.isEmpty() ? currentTime : timestamps.get(timestamps.size() - 1);
                currentTime += getRandomBurstDelay(random, false);
            }
            return timestamps;
        }

        // 【作用】半自动模式时间戳：首击延迟减半，之后每次随机 25-60 tick 一响，直至总时长上限
        private static List<Integer> generateSemiTimestamps(Random random) {
            List<Integer> timestamps = new ArrayList<>();
            int currentTime = 0;
            currentTime += getRandomBurstDelay(random, true);
            timestamps.add(currentTime);
            while (currentTime < DecoyConstants.TOTAL_DURATION_TICKS) {
                currentTime += getRandomBurstDelay(random, false);
                if (currentTime < DecoyConstants.TOTAL_DURATION_TICKS) {
                    timestamps.add(currentTime);
                }
            }
            return timestamps;
        }

        // 【作用】随机爆发间延迟：首段减半（原版逻辑）；区间 [min, max] 闭区间取值（与 kotlin 语义一致）
        private static int getRandomBurstDelay(Random random, boolean isFirstBurst) {
            int minDelay = isFirstBurst ? DecoyConstants.MIN_BURST_DELAY_TICKS / 2 : DecoyConstants.MIN_BURST_DELAY_TICKS;
            int maxDelay = isFirstBurst ? DecoyConstants.MAX_BURST_DELAY_TICKS / 2 : DecoyConstants.MAX_BURST_DELAY_TICKS;
            return random.nextInt(maxDelay - minDelay + 1) + minDelay;
        }
    }
}
