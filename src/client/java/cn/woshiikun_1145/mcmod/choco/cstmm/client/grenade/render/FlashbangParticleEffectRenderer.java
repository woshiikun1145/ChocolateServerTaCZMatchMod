package cn.woshiikun_1145.mcmod.choco.cstmm.client.grenade.render;

import cn.woshiikun_1145.mcmod.choco.cstmm.network.payload.FlashBangExplodedPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.entity.player.PlayerEntity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 【作用】其他玩家被闪光弹致盲时的可见提示（移植自 CounterStrikeGrenades 的
 *         FlashbangParticleEffectRenderer）：在该玩家眼部前方持续生成白色末地烛粒子
 *         （lifetime=1、放大 3 倍），直到其致盲总时长（attack+sustain+decay）结束。
 * 【被谁使用】GrenadeClientPayloads（收到爆闪包且 uuid 非本地玩家）。
 */
@Environment(EnvType.CLIENT)
public final class FlashbangParticleEffectRenderer {

    private FlashbangParticleEffectRenderer() {
    }

    /** key: 玩家 UUID，value: 停止渲染的 Epoch 毫秒时刻 */
    private static final Map<UUID, Long> renderingPlayers = new ConcurrentHashMap<>();

    /**
     * 【作用】登记一个被闪玩家：已在列表中的玩家只延长结束时刻（sustain+decay），
     *         新玩家按 attack+sustain+decay 全长计算。
     * 【被谁使用】GrenadeClientPayloads。
     */
    public static void render(FlashBangExplodedPayload.Affected affected) {
        long total = affected.effect().sustain() + affected.effect().decay();
        renderingPlayers.merge(affected.uuid(),
                System.currentTimeMillis() + total,
                (oldEnd, newEnd) -> Math.max(oldEnd, newEnd));
    }

    // 【作用】挂载客户端 tick：清理过期条目并为每个在列玩家生成 1 帧白闪粒子
    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            long now = System.currentTimeMillis();
            renderingPlayers.values().removeIf(end -> end < now);
            if (client.world == null || renderingPlayers.isEmpty()) {
                return;
            }
            for (UUID uuid : renderingPlayers.keySet()) {
                PlayerEntity player = client.world.getPlayerByUuid(uuid);
                if (player != null) {
                    createParticleAtPlayer(player);
                }
            }
        });
    }

    // 【作用】在玩家眼部前方 0.6 格生成单帧放大的末地烛粒子（白光感）
    private static void createParticleAtPlayer(PlayerEntity player) {
        var particleManager = MinecraftClient.getInstance().particleManager;
        var position = player.getEyePos().add(player.getRotationVector().multiply(0.6));
        var speed = player.getVelocity();
        particleManager.addParticle(ParticleTypes.END_ROD,
                position.x, position.y, position.z,
                speed.x, speed.y, speed.z);
    }
}
