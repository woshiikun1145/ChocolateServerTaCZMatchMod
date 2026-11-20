package cn.woshiikun_1145.mcmod.choco.cstmm.client.grenade.render;

import cn.woshiikun_1145.mcmod.choco.cstmm.sound.ModSounds;
import net.minecraft.client.sound.MovingSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.math.random.Random;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.time.Duration;
import java.time.Instant;

/**
 * 【作用】闪光弹致盲耳鸣音（移植自 CounterStrikeGrenades 的 FlashbangRingSound）：
 *         无衰减定位的全局循环音，音量按 attack/sustain/decay（毫秒）三段包络播放，
 *         decay 结束后自动停止。闪光致盲期间被声音屏蔽逻辑豁免（保持可听）。
 *         注：yarn 1.21.1 中可 tick 声音基类为 MovingSoundInstance（= mojmap
 *         AbstractTickableSoundInstance），循环/衰减字段名为 repeat / attenuationType。
 * 【被谁使用】FlashbangBlindEffectRenderer（爆闪时播放）；
 *           SoundSystemMixin 的闪光声音屏蔽豁免判定（instanceof 本类）。
 */
@Environment(EnvType.CLIENT)
public class FlashbangRingSound extends MovingSoundInstance {

    private final int attack;
    private final int sustain;
    private final int decay;
    private final float targetVolume;
    private final Instant startTime = Instant.now();

    public FlashbangRingSound(int attack, int sustain, int decay, float targetVolume) {
        super(ModSounds.FLASHBANG_EXPLOSION_RING.value(), SoundCategory.MASTER,
                Random.create());
        this.attack = attack;
        this.sustain = sustain;
        this.decay = decay;
        this.targetVolume = targetVolume;
        this.repeat = true;
        this.pitch = 1.0f;
        this.attenuationType = SoundInstance.AttenuationType.NONE;
        this.volume = targetVolume;
    }

    // 【作用】三段包络推进音量：attack 渐入 → sustain 恒定 → decay 渐出 → stop
    @Override
    public void tick() {
        long timeDelta = Duration.between(this.startTime, Instant.now()).toMillis();
        if (timeDelta < attack) {
            this.volume = (timeDelta / (float) attack) * targetVolume;
        } else if (timeDelta < attack + sustain) {
            this.volume = targetVolume;
        } else if (timeDelta < attack + sustain + decay) {
            this.volume = (float) (targetVolume * (1 - (double) (timeDelta - attack - sustain) / decay));
        } else {
            this.setDone();
        }
    }
}
