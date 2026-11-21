package cn.woshiikun_1145.mcmod.choco.cstmm.client.grenade;

import cn.woshiikun_1145.mcmod.choco.cstmm.client.grenade.render.HEExplosionEffects;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.grenade.render.SmokeRenderManager;
import cn.woshiikun_1145.mcmod.choco.cstmm.entity.AbstractFireGrenadeEntity;
import cn.woshiikun_1145.mcmod.choco.cstmm.entity.CounterStrikeGrenadeEntity;
import cn.woshiikun_1145.mcmod.choco.cstmm.entity.SmokeGrenadeEntity;
import cn.woshiikun_1145.mcmod.choco.cstmm.sound.ModSounds;
import cn.woshiikun_1145.mcmod.choco.cstmm.util.GrenadeHooks;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.EntityTrackingSoundInstance;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.entity.Entity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;

/**
 * 【作用】手雷系统客户端钩子实现（实现 common 侧 {@link GrenadeHooks.ClientHooks} 契约）：
 *         距离衰减音效、HE 爆炸视觉、烟雾发烟/打散/穿烟、拔销音，全部经本类落地到
 *         MinecraftClient 能力上。CstmmClient 初始化时注入 GrenadeHooks.client。
 * 【被谁使用】CstmmClient（注入）；common 侧实体与物品（经 GrenadeHooks.client 调用）。
 */
@Environment(EnvType.CLIENT)
public class GrenadeClientHooksImpl implements GrenadeHooks.ClientHooks {

    // 【作用】按实体到本地玩家距离播放衰减音效（原 SoundUtils.getVolumeFromDistance 口径）
    @Override
    public void playDistanceSound(Entity entity, SoundEvent sound, double initialVolume, double hearableRange) {
        var player = MinecraftClient.getInstance().player;
        if (player == null) {
            return;
        }
        double distance = entity.getPos().add(player.getPos().negate()).length();
        double volume = Math.max(0.0, initialVolume * (1 - distance / hearableRange));
        MinecraftClient.getInstance().getSoundManager().play(new EntityTrackingSoundInstance(
                sound, SoundCategory.AMBIENT, (float) volume, 1.0f, entity, 0));
    }

    // 【作用】HE 爆炸视觉与音效（近/远音按 15 格分界）
    @Override
    public void onHeExploded(Vec3d pos) {
        HEExplosionEffects.trigger(pos);
    }

    // 【作用】烟雾弹发烟：按距离选近/远发烟音 + 启动粒子生成器（等待扩散方块广播）
    @Override
    public void onSmokeEmit(SmokeGrenadeEntity entity) {
        var player = MinecraftClient.getInstance().player;
        if (player == null) {
            return;
        }
        double distance = entity.getPos().subtract(player.getPos()).length();
        SoundEvent sound = distance > 30 ? ModSounds.SMOKE_EXPLODE_DISTANT.value() : ModSounds.SMOKE_EMIT.value();
        double initialVolume = distance > 30
                ? CounterStrikeGrenadeEntity.SoundVolume.SMOKE_EXPLODE_DISTANT_VOLUME
                : CounterStrikeGrenadeEntity.SoundVolume.SMOKE_EMIT_VOLUME;
        double hearableRange = distance > 30
                ? CounterStrikeGrenadeEntity.SoundVolume.SMOKE_EXPLODE_DISTANT_RANGE
                : CounterStrikeGrenadeEntity.SoundVolume.SMOKE_EMIT_RANGE;
        double volume = Math.max(0.0, initialVolume * (1 - distance / hearableRange));
        MinecraftClient.getInstance().getSoundManager().play(new EntityTrackingSoundInstance(
                sound, SoundCategory.AMBIENT, (float) volume, 1.0f, entity, 0));

        SmokeRenderManager.beginRendering(entity, entity.getPos());
    }

    // 【作用】烟雾透明度清理（HE 爆炸/子弹/箭矢共用）
    @Override
    public void clearSmokeWithinRange(SmokeGrenadeEntity entity, Vec3d pos, double range, boolean debugHeader) {
        SmokeRenderManager.clearSmokeWithinRange(pos, range);
    }

    // 【作用】箭矢/TACZ 子弹穿烟打散（每 tick 由烟雾实体客户端分支触发）
    @Override
    public void disperseSmokeByProjectiles(SmokeGrenadeEntity entity) {
        SmokeRenderManager.disperseSmokeByProjectiles(entity);
    }

    // 【作用】已起火燃烧类手雷的逐 tick 火焰粒子渲染
    @Override
    public void renderFireGrenade(AbstractFireGrenadeEntity entity) {
        cn.woshiikun_1145.mcmod.choco.cstmm.client.grenade.render.FireGrenadeClientRenderer.renderOne(entity);
    }

    // 【作用】手雷拔销音（本地低音量播放）
    @Override
    public void playDrawSound(SoundEvent sound) {
        MinecraftClient.getInstance().getSoundManager().play(
                PositionedSoundInstance.master(sound, 1.0f, 0.2f));
    }

    /**
     * 【作用】球体内均匀随机取点（HE 爆炸大烟雾粒子分布用；拒绝采样实现）。
     * 【被谁使用】HEExplosionEffects。
     */
    public static Vec3d randomInSphere(Vec3d center, double radius) {
        Random random = Random.create();
        while (true) {
            Vec3d delta = new Vec3d(
                    random.nextDouble() * radius * 2 - radius,
                    random.nextDouble() * radius * 2 - radius,
                    random.nextDouble() * radius * 2 - radius);
            if (delta.length() < radius) {
                return center.add(delta);
            }
        }
    }
}
