package cn.woshiikun_1145.mcmod.choco.cstmm.client.grenade.sound;

import cn.woshiikun_1145.mcmod.choco.cstmm.Cstmm;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;

/**
 * 【作用】诱饵弹枪声播放控制器（移植自 CounterStrikeGrenades 的 DecoySoundController）：
 *         按"自定义音效 > TACZ 枪械音效 > 原版动物声 fallback"优先级播放一枪，
 *         全部走 PLAYERS 声道（音量 1.0 / 音高 1.0，与原版一致）。
 * 【被谁使用】GrenadeClientPayloads 的 DecoyShotPayload 接收器（客户端主线程）。
 */
public final class DecoySoundController {

    private DecoySoundController() {
    }

    /**
     * 【作用】按优先级播放一枪：customSound 注册表音效 → TACZ 枪声 → 动物声随机 fallback。
     * 【被谁使用】GrenadeClientPayloads（DecoyShotPayload 接收器）。
     */
    public static void playShotSound(int entityId, String gunId, String customSound) {
        ClientWorld world = MinecraftClient.getInstance().world;
        if (world == null) {
            return;
        }
        Entity entity = world.getEntityById(entityId);
        if (entity == null) {
            return;
        }
        if (customSound != null && !customSound.isBlank()) {
            playCustomSound(entity, customSound);
        } else if (FabricLoader.getInstance().isModLoaded("tacz") && !gunId.isBlank()) {
            TaczApiHandler.playGunSound(entity, Identifier.of(gunId));
        } else {
            playFallbackSound(entity);
        }
    }

    // 【作用】自定义音效：查注册表 SoundEvent，查不到退回 fallback；ID 非法时不崩服仅警告（原版无守卫，此处加固）
    private static void playCustomSound(Entity entity, String soundId) {
        try {
            SoundEvent soundEvent = Registries.SOUND_EVENT.get(Identifier.of(soundId));
            if (soundEvent == null) {
                playFallbackSound(entity);
                return;
            }
            entity.getWorld().playSound(null, entity.getBlockPos(), soundEvent, SoundCategory.PLAYERS, 1.0f, 1.0f);
        } catch (Exception e) {
            Cstmm.LOGGER.warn("[CSTMM - DecoySoundController] 自定义音效 ID 非法，退回 fallback: " + soundId, e);
            playFallbackSound(entity);
        }
    }

    // 【作用】fallback 动物声（苦力怕受伤/死亡/点燃嘶声 + 鸡受伤/咕咕随机），PLAYERS 声道 1.0/1.0（原版参数）
    private static void playFallbackSound(Entity entity) {
        SoundEvent[] sounds = {
                SoundEvents.ENTITY_CREEPER_HURT,
                SoundEvents.ENTITY_CREEPER_DEATH,
                SoundEvents.ENTITY_CREEPER_PRIMED,
                SoundEvents.ENTITY_CHICKEN_HURT,
                SoundEvents.ENTITY_CHICKEN_AMBIENT,
        };
        SoundEvent sound = sounds[entity.getWorld().random.nextInt(sounds.length)];
        entity.getWorld().playSound(null, entity.getBlockPos(), sound, SoundCategory.PLAYERS, 1.0f, 1.0f);
    }
}
