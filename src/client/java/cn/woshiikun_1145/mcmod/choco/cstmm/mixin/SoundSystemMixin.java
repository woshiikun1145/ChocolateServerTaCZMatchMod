package cn.woshiikun_1145.mcmod.choco.cstmm.mixin;

import cn.woshiikun_1145.mcmod.choco.cstmm.client.cache.ClientConfig;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.sound.SoundSystem;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 【作用】模组音效音量控制：SoundSystem.getAdjustedVolume(SoundInstance) 是所有 MC 音效播放
 *         的最终音量来源（含服务端广播的 PlaySoundS2CPacket），在返回值上按客户端配置的
 *         模组音效音量倍率（ClientConfig.soundEffectsVolume，0~4，1=100%）缩放命名空间为
 *         cstmm 的音效；其他命名空间（原版/其他模组）不受影响。
 *         MVP 音乐不经过 MC 声音系统（JLayer 直播 PCM），音量由 ClientConfig.mvpMusicVolume
 *         在 MvpMusicPlayer 独立控制。
 * 【被谁使用】chocolateservertaczmatchmod.mixins.json（client 列表）。仅客户端。
 */
@Mixin(SoundSystem.class)
public abstract class SoundSystemMixin {

    @Inject(method = "getAdjustedVolume(Lnet/minecraft/client/sound/SoundInstance;)F",
            at = @At("RETURN"), cancellable = true)
    private void cstmm$scaleModSoundVolume(SoundInstance sound, CallbackInfoReturnable<Float> cir) {
        if (cir.getReturnValueF() <= 0f) return;
        Identifier id = sound.getId();
        if (id == null || !ClientConfig.MOD_SOUND_NAMESPACE.equals(id.getNamespace())) return;
        float mult = ClientConfig.getSoundEffectsVolume();
        if (mult != 1.0f) {
            cir.setReturnValue(cir.getReturnValueF() * mult);
        }
    }
}
