package cn.woshiikun_1145.mcmod.choco.cstmm.util;

import cn.woshiikun_1145.mcmod.choco.cstmm.Cstmm;
import cn.woshiikun_1145.mcmod.choco.cstmm.entity.DecoyGrenadeEntity.DecoyGunData;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * 【作用】TACZ 枪械数据提供器（移植自 CounterStrikeGrenades 的 TaczGunDataProvider，
 *         Forge ModList → FabricLoader、直接 API 调用 → 反射调用，避免编译期依赖 TACZ jar）：
 *         从投掷者主物品栏中找出第一把 TACZ 枪械，读取枪械 ID / 射击模式 / RPM / 射击间隔毫秒，
 *         供诱饵弹按真实射速模拟枪声。TACZ 未安装或解析失败时返回 null（诱饵弹退回 fallback 模式）。
 * 【被谁使用】DecoyGrenadeEntity.findAndSetTaczGunIdOnThrow（服务端投掷时）。
 */
public final class TaczGunDataProvider {

    // ===== 反射元数据：首次成功解析后缓存（volatile 双重检查；竞争时写入相同值无害） =====
    private static volatile boolean initialized = false;
    private static volatile boolean initFailed = false;
    private static Class<?> igunClass;
    private static Method mGetGunId;
    private static Method mGetFireMode;
    private static Method mGetRpm;
    private static Method mGetCommonGunIndex;
    /** CommonGunIndex#getGunData 与 GunData#getShootInterval 依赖运行时对象类型，首次命中后缓存 */
    private static volatile Method mGetGunData;
    private static volatile Method mGetShootInterval;

    private TaczGunDataProvider() {
    }

    /**
     * 【作用】从玩家主物品栏查找第一把 TACZ 枪械并构造 DecoyGunData
     *         （原版 firstNotNullOfOrNull 语义：枪械不在 TACZ 注册表中则继续找下一把）。
     * 【被谁使用】DecoyGrenadeEntity.findAndSetTaczGunIdOnThrow。
     */
    public static DecoyGunData findGunDataFromPlayer(PlayerEntity player) {
        if (!FabricLoader.getInstance().isModLoaded("tacz") || !ensureInitialized()) {
            return null;
        }
        try {
            for (ItemStack stack : player.getInventory().main) {
                if (!igunClass.isInstance(stack.getItem())) {
                    continue;
                }
                Identifier gunId = (Identifier) mGetGunId.invoke(stack.getItem(), stack);
                Object fireMode = mGetFireMode.invoke(stack.getItem(), stack);
                int rpm = (Integer) mGetRpm.invoke(stack.getItem(), stack);

                Object optionalObj = mGetCommonGunIndex.invoke(null, gunId);
                if (!(optionalObj instanceof Optional<?> optional) || !optional.isPresent()) {
                    continue;
                }
                Object commonGunIndex = optional.get();
                if (mGetGunData == null) {
                    mGetGunData = commonGunIndex.getClass().getMethod("getGunData");
                }
                Object gunData = mGetGunData.invoke(commonGunIndex);
                if (mGetShootInterval == null) {
                    mGetShootInterval = gunData.getClass().getMethod("getShootInterval",
                            LivingEntity.class, fireMode.getClass(), ItemStack.class);
                }
                long shootIntervalMs = (Long) mGetShootInterval.invoke(gunData, player, fireMode, stack);
                return new DecoyGunData(gunId.toString(), String.valueOf(fireMode), rpm,
                        (int) shootIntervalMs);
            }
        } catch (Exception e) {
            Cstmm.LOGGER.warn("[CSTMM - TaczGunDataProvider] TACZ 枪械数据读取失败，诱饵弹退回 fallback 模式", e);
            return null;
        }
        return null;
    }

    // 【作用】解析 TACZ API 类与方法引用（一次失败即永久短路，避免每次投掷重复抛异常）；
    //         参数按运行时 Class 对象匹配（yarn/mojmap 编译名不同但运行时为同一 intermediary 类）
    private static boolean ensureInitialized() {
        if (initialized) {
            return !initFailed;
        }
        synchronized (TaczGunDataProvider.class) {
            if (initialized) {
                return !initFailed;
            }
            try {
                igunClass = Class.forName("com.tacz.guns.api.item.IGun");
                mGetGunId = igunClass.getMethod("getGunId", ItemStack.class);
                mGetFireMode = igunClass.getMethod("getFireMode", ItemStack.class);
                mGetRpm = igunClass.getMethod("getRPM", ItemStack.class);
                Class<?> timelessApi = Class.forName("com.tacz.guns.api.TimelessAPI");
                mGetCommonGunIndex = timelessApi.getMethod("getCommonGunIndex", Identifier.class);
                initialized = true;
            } catch (Exception e) {
                initFailed = true;
                Cstmm.LOGGER.warn("[CSTMM - TaczGunDataProvider] TACZ API 反射解析失败", e);
            }
            return !initFailed;
        }
    }
}
