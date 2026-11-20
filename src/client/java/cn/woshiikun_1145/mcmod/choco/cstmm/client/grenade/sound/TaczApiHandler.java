package cn.woshiikun_1145.mcmod.choco.cstmm.client.grenade.sound;

import cn.woshiikun_1145.mcmod.choco.cstmm.Cstmm;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.Entity;
import net.minecraft.util.Identifier;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * 【作用】TACZ 枪声播放桥（客户端，移植自 CounterStrikeGrenades 的 TaczApiHandler.playGunSound，
 *         直接 API 调用 → 反射调用，避免编译期依赖 TACZ jar）：按枪械 ID 查 TACZ 枪械展示实例的
 *         "shoot" 音效并经 SoundPlayManager.playClientSound 播放（音量 0.8、音高 0.9~1.025、听距 48）。
 * 【被谁使用】DecoySoundController.playShotSound（诱饵弹枪声的 TACZ 分支）。
 */
public final class TaczApiHandler {

    // ===== 反射元数据：首次成功解析后缓存（volatile 双重检查；竞争时写入相同值无害） =====
    private static volatile boolean initialized = false;
    private static volatile boolean initFailed = false;
    private static Method mGetGunDisplay;
    /** GunDisplayInstance#getSounds 依赖运行时对象类型，首次命中后缓存 */
    private static volatile Method mGetSounds;
    private static Method mPlayClientSound;

    private TaczApiHandler() {
    }

    // 【作用】TACZ 是否已安装（守卫入口）
    public static boolean isLoaded() {
        return FabricLoader.getInstance().isModLoaded("tacz");
    }

    /**
     * 【作用】播放 TACZ 枪械射击音（原版 1:1：getGunDisplay(id,id) → getSounds("shoot") →
     *         playClientSound(entity, soundId, 0.8f, pitch, 48)，pitch = 0.9 + random×0.125）。
     * 【被谁使用】DecoySoundController.playShotSound。
     */
    public static void playGunSound(Entity entity, Identifier gunId) {
        if (!isLoaded() || !ensureInitialized()) {
            return;
        }
        try {
            Object optionalObj = mGetGunDisplay.invoke(null, gunId, gunId);
            if (!(optionalObj instanceof Optional<?> optional) || !optional.isPresent()) {
                return;
            }
            Object display = optional.get();
            if (mGetSounds == null) {
                mGetSounds = display.getClass().getMethod("getSounds", String.class);
            }
            Object soundId = mGetSounds.invoke(display, "shoot");
            if (soundId == null) {
                return;
            }
            float pitch = 0.9f + entity.getWorld().random.nextFloat() * 0.125f;
            mPlayClientSound.invoke(null, entity, soundId, 0.8f, pitch, 48);
        } catch (Exception e) {
            Cstmm.LOGGER.warn("[CSTMM - TaczApiHandler] TACZ 枪声播放失败", e);
        }
    }

    // 【作用】解析 TACZ API 反射元数据（一次失败即永久短路，避免每次枪声重复抛异常）；
    //         参数按运行时 Class 对象匹配（yarn/mojmap 编译名不同但运行时为同一 intermediary 类）
    private static boolean ensureInitialized() {
        if (initialized) {
            return !initFailed;
        }
        synchronized (TaczApiHandler.class) {
            if (initialized) {
                return !initFailed;
            }
            try {
                Class<?> timelessApi = Class.forName("com.tacz.guns.api.TimelessAPI");
                mGetGunDisplay = timelessApi.getMethod("getGunDisplay", Identifier.class, Identifier.class);
                Class<?> soundPlayManager = Class.forName("com.tacz.guns.client.sound.SoundPlayManager");
                mPlayClientSound = soundPlayManager.getMethod("playClientSound",
                        Entity.class, Identifier.class, float.class, float.class, int.class);
                initialized = true;
            } catch (Exception e) {
                initFailed = true;
                Cstmm.LOGGER.warn("[CSTMM - TaczApiHandler] TACZ 客户端 API 反射解析失败", e);
            }
            return !initFailed;
        }
    }
}
