package cn.woshiikun_1145.mcmod.choco.cstmm.client.grenade;

import cn.woshiikun_1145.mcmod.choco.cstmm.client.grenade.render.FlashbangEffects.FlashbangBlindEffectRenderer;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.grenade.render.FlashbangEffects.FlashbangParticleEffectRenderer;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.grenade.sound.DecoySoundController;
import cn.woshiikun_1145.mcmod.choco.cstmm.entity.SmokeGrenadeEntity;
import cn.woshiikun_1145.mcmod.choco.cstmm.network.payload.GrenadePayloads.DecoyShotPayload;
import cn.woshiikun_1145.mcmod.choco.cstmm.network.payload.GrenadePayloads.FlashBangExplodedPayload;
import cn.woshiikun_1145.mcmod.choco.cstmm.network.payload.GrenadePayloads.FireGrenadePayload;
import cn.woshiikun_1145.mcmod.choco.cstmm.network.payload.GrenadePayloads.SmokeSpreadPayload;
import cn.woshiikun_1145.mcmod.choco.cstmm.util.GrenadeHooks;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.entity.Entity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.math.random.Random;

/**
 * 【作用】手雷系统 S2C 数据包客户端接收器：
 *         cstmm:flashbang_exploded → 本地玩家触发白屏 overlay（FlashbangBlindEffectRenderer）、
 *         其他玩家触发白闪粒子（FlashbangParticleEffectRenderer）、按距离播放爆炸音、
 *         爆心生成 20 个原版 FLASH 粒子；
 *         cstmm:smoke_spread → 按 entityId 定位烟雾实体回写扩散方块，并触发首次发烟
 *         （发烟音 + 粒子生成器启动）。
 * 【被谁使用】CstmmClient（register）；服务端 FlashBangEntity / SmokeGrenadeEntity（发送方）。
 */
@Environment(EnvType.CLIENT)
public final class GrenadeClientPayloads {

    private GrenadeClientPayloads() {
    }

    // 【作用】注册两个 S2C 接收器（幂等，仅客户端初始化调用一次）
    public static void register() {
        // 【作用】闪光弹爆闪广播：白屏/白闪粒子/爆炸音/FLASH 粒子
        ClientPlayNetworking.registerGlobalReceiver(FlashBangExplodedPayload.ID, (payload, context) -> {
            var client = context.client();
            client.execute(() -> {
                var localPlayer = client.player;
                if (localPlayer == null) {
                    return;
                }
                for (FlashBangExplodedPayload.Affected affected : payload.affected()) {
                    if (affected.uuid().equals(localPlayer.getUuid())) {
                        FlashbangBlindEffectRenderer.render(affected.effect(), payload.position());
                    } else {
                        FlashbangParticleEffectRenderer.render(affected);
                    }
                }
                playExplosionSound(payload.position());

                // 【作用】爆心 20 个原版 FLASH 粒子（高斯速度抖动，与原 mod 一致）
                Random random = Random.create();
                for (int i = 0; i < 20; i++) {
                    client.particleManager.addParticle(ParticleTypes.FLASH,
                            payload.position().x, payload.position().y, payload.position().z,
                            random.nextGaussian() * 0.02,
                            random.nextGaussian() * 0.02,
                            random.nextGaussian() * 0.02);
                }
            });
        });

        // 【作用】烟雾扩散方块广播：回写实体客户端缓存并触发首次发烟（音效+粒子）
        ClientPlayNetworking.registerGlobalReceiver(SmokeSpreadPayload.ID, (payload, context) -> {
            var client = context.client();
            client.execute(() -> {
                if (client.world == null) {
                    return;
                }
                Entity entity = client.world.getEntityById(payload.entityId());
                if (entity instanceof SmokeGrenadeEntity smoke) {
                    smoke.setClientSpreadBlocks(payload.blocks());
                    if (GrenadeHooks.client != null) {
                        GrenadeHooks.client.onSmokeEmit(smoke);
                    }
                }
            });
        });

        // 【作用】燃烧类事件广播：落地起火（起火音；火焰方块由实体同步数据承载，无需本包处理）/
        //         空中自爆（爆燃音 + 500 火焰粒子）/ 被烟雾熄灭（噗声）
        ClientPlayNetworking.registerGlobalReceiver(FireGrenadePayload.ID, (payload, context) -> {
            var client = context.client();
            client.execute(() -> {
                if (client.world == null) {
                    return;
                }
                switch (payload.type()) {
                    case FireGrenadePayload.TYPE_GROUND_EXPLODED -> playPositionedSound(payload.position(),
                            cn.woshiikun_1145.mcmod.choco.cstmm.sound.ModSounds.INCENDIARY_EXPLODE.value(),
                            cn.woshiikun_1145.mcmod.choco.cstmm.entity.CounterStrikeGrenadeEntity.SoundVolume.INCENDIARY_EXPLODE_VOLUME,
                            cn.woshiikun_1145.mcmod.choco.cstmm.entity.CounterStrikeGrenadeEntity.SoundVolume.INCENDIARY_EXPLODE_RANGE,
                            1.0f);
                    case FireGrenadePayload.TYPE_AIR_EXPLODED -> {
                        playPositionedSound(payload.position(),
                                cn.woshiikun_1145.mcmod.choco.cstmm.sound.ModSounds.INCENDIARY_EXPLODE_AIR.value(),
                                cn.woshiikun_1145.mcmod.choco.cstmm.entity.CounterStrikeGrenadeEntity.SoundVolume.INCENDIARY_EXPLODE_AIR_VOLUME,
                                cn.woshiikun_1145.mcmod.choco.cstmm.entity.CounterStrikeGrenadeEntity.SoundVolume.INCENDIARY_EXPLODE_AIR_RANGE,
                                1.0f);
                        // 【作用】空中自爆：500 个火焰粒子四散（原 mod 语义，与实体无关）
                        Random random = Random.create();
                        for (int i = 0; i < 500; i++) {
                            client.particleManager.addParticle(ParticleTypes.FLAME,
                                    payload.position().x, payload.position().y, payload.position().z,
                                    random.nextDouble() - 0.5,
                                    random.nextDouble() - 0.5,
                                    random.nextDouble() - 0.5);
                        }
                    }
                    case FireGrenadePayload.TYPE_EXTINGUISHED_BY_SMOKE -> playPositionedSound(payload.position(),
                            cn.woshiikun_1145.mcmod.choco.cstmm.sound.ModSounds.INCENDIARY_POP.value(),
                            cn.woshiikun_1145.mcmod.choco.cstmm.entity.CounterStrikeGrenadeEntity.SoundVolume.INCENDIARY_POP_VOLUME,
                            cn.woshiikun_1145.mcmod.choco.cstmm.entity.CounterStrikeGrenadeEntity.SoundVolume.INCENDIARY_POP_RANGE,
                            0.8f);
                    default -> { /* 未知类型忽略 */ }
                }
            });
        });

        // 【作用】诱饵弹枪声广播（阶段 3）：按优先级播枪声（自定义音效 > TACZ > 动物声 fallback）
        //         + 实体上方 0.5 格生成枪口焰粒子
        ClientPlayNetworking.registerGlobalReceiver(DecoyShotPayload.ID, (payload, context) -> {
            var client = context.client();
            client.execute(() -> {
                if (client.world == null) {
                    return;
                }
                DecoySoundController.playShotSound(payload.entityId(), payload.gunId(), payload.customSound());
                Entity entity = client.world.getEntityById(payload.entityId());
                if (entity != null) {
                    spawnMuzzleFlashParticles(client, entity);
                }
            });
        });
    }

    // 【作用】枪口焰粒子：5 个 FLAME（存活 5 tick）+ 3 个 SMOKE（存活 10 tick），位置实体上方 0.5 格（原版参数）
    private static void spawnMuzzleFlashParticles(MinecraftClient client, Entity entity) {
        net.minecraft.util.math.Vec3d pos = entity.getPos();
        Random random = Random.create();
        for (int i = 0; i < 5; i++) {
            net.minecraft.client.particle.Particle particle = client.particleManager.addParticle(
                    ParticleTypes.FLAME, pos.x, pos.y + 0.5, pos.z,
                    (random.nextDouble() - 0.5) * 0.1,
                    (random.nextDouble() - 0.5) * 0.1,
                    (random.nextDouble() - 0.5) * 0.1);
            if (particle != null) {
                particle.setMaxAge(5);
            }
        }
        for (int i = 0; i < 3; i++) {
            net.minecraft.client.particle.Particle particle = client.particleManager.addParticle(
                    ParticleTypes.SMOKE, pos.x, pos.y + 0.5, pos.z,
                    (random.nextDouble() - 0.5) * 0.05,
                    (random.nextDouble() - 0.5) * 0.05,
                    (random.nextDouble() - 0.5) * 0.05);
            if (particle != null) {
                particle.setMaxAge(10);
            }
        }
    }

    // 【作用】按距离衰减播放定位音效（原 mod FireGrenadeMessage 的三事件共用单一 SoundEvent + 距离音量）
    private static void playPositionedSound(net.minecraft.util.math.Vec3d position,
                                             net.minecraft.sound.SoundEvent sound,
                                             double initialVolume, double hearableRange, float pitch) {
        var player = MinecraftClient.getInstance().player;
        if (player == null) {
            return;
        }
        double distance = Math.sqrt(player.squaredDistanceTo(position));
        double volume = Math.max(0.001, initialVolume * (1 - distance / hearableRange));
        MinecraftClient.getInstance().getSoundManager().play(new PositionedSoundInstance(
                sound, SoundCategory.AMBIENT, (float) volume, pitch, Random.create(),
                position.x, position.y, position.z));
    }

    // 【作用】闪光爆炸音：≤15 格用近音（0.08/80），否则远音（0.5/80），按距离衰减
    private static void playExplosionSound(net.minecraft.util.math.Vec3d position) {
        var player = MinecraftClient.getInstance().player;
        if (player == null) {
            return;
        }
        double distance = Math.sqrt(player.squaredDistanceTo(position));
        boolean close = distance <= 15.0;
        var sound = close
                ? cn.woshiikun_1145.mcmod.choco.cstmm.sound.ModSounds.FLASHBANG_EXPLODE.value()
                : cn.woshiikun_1145.mcmod.choco.cstmm.sound.ModSounds.FLASHBANG_EXPLODE_DISTANT.value();
        double initialVolume = close ? 0.08 : 0.5;
        double hearableRange = 80.0;
        double volume = Math.max(0.001, initialVolume * (1 - distance / hearableRange));
        MinecraftClient.getInstance().getSoundManager().play(new PositionedSoundInstance(
                sound, SoundCategory.AMBIENT, (float) volume, 1.0f, Random.create(),
                position.x, position.y, position.z));
    }
}
