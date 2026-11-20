package cn.woshiikun_1145.mcmod.choco.cstmm.entity;

import cn.woshiikun_1145.mcmod.choco.cstmm.item.ModItems;
import cn.woshiikun_1145.mcmod.choco.cstmm.network.payload.FlashBangExplodedPayload;
import cn.woshiikun_1145.mcmod.choco.cstmm.network.payload.FlashbangEffectData;
import cn.woshiikun_1145.mcmod.choco.cstmm.util.GrenadeDamageTypes;
import cn.woshiikun_1145.mcmod.choco.cstmm.util.GrenadeSettings;
import cn.woshiikun_1145.mcmod.choco.cstmm.util.GrenadeMath;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.projectile.thrown.ThrownItemEntity;
import net.minecraft.item.Item;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * 【作用】闪光弹投掷物实体（移植自 CounterStrikeGrenades 的 FlashBangEntity）：
 *         引信 1.6 秒后爆闪——服务端对全服广播爆闪包（含每个受影响玩家的致盲数据），
 *         客户端各自渲染白屏/白闪粒子与爆炸音。无爆炸实体伤害。
 * 【被谁使用】GrenadeThrownPayload（服务端生成）、ModEntities.FLASHBANG（注册）。
 */
public class FlashBangEntity extends CounterStrikeGrenadeEntity {

    public FlashBangEntity(EntityType<? extends ThrownItemEntity> type, World world) {
        super(type, world);
    }

    // 【作用】投掷物默认展示物品类型（flashbang 物品模型）
    @Override
    protected Item getDefaultItem() {
        return ModItems.FLASHBANG;
    }

    // 【作用】引信计时：到点后（服务端）计算受影响玩家并全服广播，随后标记激活并丢弃实体
    @Override
    public void tick() {
        super.tick();

        if (GrenadeMath.millisToTicks(GrenadeSettings.FLASH_FUSE_TIME_MS) < this.age
                && !this.dataTracker.get(IS_ACTIVATED)) {
            if (this.getWorld() instanceof ServerWorld serverWorld) {
                FlashBangExplodedPayload payload = new FlashBangExplodedPayload(
                        this.getPos(), calculateAffectedPlayers(serverWorld));
                for (ServerPlayerEntity player : serverWorld.getPlayers()) {
                    ServerPlayNetworking.send(player, payload);
                }
            }
            this.dataTracker.set(IS_ACTIVATED, true);
            this.discard();
        }
    }

    // 【作用】碰撞伤害源：对自己无第三方归因，对他人携带投掷者归因
    @Override
    protected DamageSource getHitDamageSource(LivingEntity hitEntity) {
        if (hitEntity == this.getOwner()) {
            return HEGrenadeEntity.createDamageSource((ServerWorld) this.getWorld(),
                    GrenadeDamageTypes.FLASHBANG_HIT, this, null);
        }
        return HEGrenadeEntity.createDamageSource((ServerWorld) this.getWorld(),
                GrenadeDamageTypes.FLASHBANG_HIT, this, this.getOwner());
    }

    // 【作用】服务端计算 256 格内所有玩家的致盲数据（sustain<=0 的玩家不进包，与原版过滤一致）
    private List<FlashBangExplodedPayload.Affected> calculateAffectedPlayers(ServerWorld level) {
        List<FlashBangExplodedPayload.Affected> affected = new ArrayList<>();
        for (ServerPlayerEntity player : level.getPlayers(
                p -> p.squaredDistanceTo(this.getPos()) < 256.0 * 256.0)) {
            FlashbangEffectData data = FlashbangEffectData.create(level, this.getPos(), player);
            if (data.sustain() > 0) {
                affected.add(new FlashBangExplodedPayload.Affected(player.getUuid(), data));
            }
        }
        return affected;
    }
}
