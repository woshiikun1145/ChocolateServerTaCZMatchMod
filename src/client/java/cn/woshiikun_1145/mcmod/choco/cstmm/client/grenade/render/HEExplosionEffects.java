package cn.woshiikun_1145.mcmod.choco.cstmm.client.grenade.render;

import cn.woshiikun_1145.mcmod.choco.cstmm.client.grenade.GrenadeClientHooksImpl;
import cn.woshiikun_1145.mcmod.choco.cstmm.sound.ModSounds;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;

/**
 * 【作用】HE 手雷爆炸的客户端即时效果（移植自 CounterStrikeGrenades 的 HEGrenadeRenderManager，
 *         原实现是一次性触发、无逐 tick 状态，故简化为静态方法）：
 *         按距离选近/远爆炸音（≤15 格用近音）+ 500 个烟雾粒子 + 100 个球面分布的大烟雾粒子。
 * 【被谁使用】GrenadeClientHooksImpl.onHeExploded（HE 实体引信到点客户端分支）。
 */
@Environment(EnvType.CLIENT)
public final class HEExplosionEffects {

    private HEExplosionEffects() {
    }

    // 【作用】触发爆炸音效与粒子（音量按 GrenadeSettings 口径 HE_EXPLODE 0.15/50、DISTANT 3.0/100 衰减）
    public static void trigger(Vec3d pos) {
        var player = MinecraftClient.getInstance().player;
        if (player == null) {
            return;
        }
        double distance = player.getPos().distanceTo(pos);
        boolean close = distance <= 15.0;
        SoundEvent sound = close ? ModSounds.HEGRENADE_EXPLODE.value() : ModSounds.HEGRENADE_EXPLODE_DISTANT.value();
        double initialVolume = close
                ? CounterStrikeGrenadeEntityBridge.HE_EXPLODE_VOLUME
                : CounterStrikeGrenadeEntityBridge.HE_EXPLODE_DISTANT_VOLUME;
        double hearableRange = close
                ? CounterStrikeGrenadeEntityBridge.HE_EXPLODE_RANGE
                : CounterStrikeGrenadeEntityBridge.HE_EXPLODE_DISTANT_RANGE;
        double volume = Math.max(0.0, initialVolume * (1 - distance / hearableRange));

        MinecraftClient.getInstance().getSoundManager().play(new PositionedSoundInstance(
                sound, SoundCategory.AMBIENT, (float) Math.max(volume, 0.001), 1.0f,
                Random.create(), pos.x, pos.y, pos.z));

        // 【作用】爆炸粒子：500 个普通烟雾（±0.7 速度抖动）+ 100 个球面分布大烟雾
        var particleManager = MinecraftClient.getInstance().particleManager;
        Random random = Random.create();
        for (int i = 0; i < 500; i++) {
            particleManager.addParticle(ParticleTypes.SMOKE,
                    pos.x, pos.y, pos.z,
                    random.nextDouble() * 1.4 - 0.7,
                    random.nextDouble() * 1.4 - 0.7,
                    random.nextDouble() * 1.4 - 0.7);
        }
        for (int i = 0; i < 100; i++) {
            Vec3d location = GrenadeClientHooksImpl.randomInSphere(pos, 4.0);
            particleManager.addParticle(ParticleTypes.LARGE_SMOKE,
                    location.x, location.y, location.z, 0.0, 0.0, 0.0);
        }
    }
}

/** 音量常量转发桥（避免 import 冲突的简单别名，数值口径见 CounterStrikeGrenadeEntity.SoundVolume）。 */
final class CounterStrikeGrenadeEntityBridge {
    static final double HE_EXPLODE_VOLUME = cn.woshiikun_1145.mcmod.choco.cstmm.entity.CounterStrikeGrenadeEntity.SoundVolume.HE_EXPLODE_VOLUME;
    static final double HE_EXPLODE_RANGE = cn.woshiikun_1145.mcmod.choco.cstmm.entity.CounterStrikeGrenadeEntity.SoundVolume.HE_EXPLODE_RANGE;
    static final double HE_EXPLODE_DISTANT_VOLUME = cn.woshiikun_1145.mcmod.choco.cstmm.entity.CounterStrikeGrenadeEntity.SoundVolume.HE_EXPLODE_DISTANT_VOLUME;
    static final double HE_EXPLODE_DISTANT_RANGE = cn.woshiikun_1145.mcmod.choco.cstmm.entity.CounterStrikeGrenadeEntity.SoundVolume.HE_EXPLODE_DISTANT_RANGE;
}
