package cn.woshiikun_1145.mcmod.choco.cstmm.entity;

import cn.woshiikun_1145.mcmod.choco.cstmm.item.ModItems;
import cn.woshiikun_1145.mcmod.choco.cstmm.network.payload.DecoyShotPayload;
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
import net.minecraft.world.World;

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
}
