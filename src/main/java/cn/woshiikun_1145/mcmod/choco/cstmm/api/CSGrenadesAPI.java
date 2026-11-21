package cn.woshiikun_1145.mcmod.choco.cstmm.api;

import net.minecraft.entity.player.PlayerEntity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 【作用】CSTMM 手雷公共 API（移植自 CounterStrikeGrenades 的 CSGrenadesAPI）：
 *         维护"被闪光弹致盲的玩家 UUID → 剩余致盲时长（tick）"映射，
 *         供其他模组查询玩家是否正处于闪光致盲状态。
 * 【被谁使用】FlashbangEffectData.create（服务端爆闪计算时写入）、
 *           Cstmm（服务端每 tick 驱动 onServerTick 递减）、其他模组（isPlayerFlashed 查询）。
 */
public final class CSGrenadesAPI {

    // 存储被闪光弹致盲的玩家 UUID 及其剩余的致盲时间（tick）
    private static final Map<UUID, Integer> flashedPlayers = new ConcurrentHashMap<>();

    private CSGrenadesAPI() {
    }

    /**
     * 【作用】检查玩家当前是否被 CSTMM 的闪光弹致盲。
     * 【被谁使用】其他模组（公共 API 查询入口）。
     */
    public static boolean isPlayerFlashed(PlayerEntity player) {
        Integer remaining = flashedPlayers.get(player.getUuid());
        return remaining != null && remaining > 0;
    }

    /**
     * 【作用】设置玩家的闪光致盲状态与持续时间（durationTicks > 0 写入，否则移除）。
     * 【被谁使用】FlashbangEffectData.create（服务端计算闪光效果时，原版 FlashBangExplodedMessage 同款调用点）。
     */
    public static void setPlayerFlashed(PlayerEntity player, int durationTicks) {
        if (durationTicks > 0) {
            flashedPlayers.put(player.getUuid(), durationTicks);
        } else {
            flashedPlayers.remove(player.getUuid());
        }
    }

    /**
     * 【作用】每服务端 tick 递减致盲剩余时长，归零即移除（原版 onServerTick）。
     * 【被谁使用】Cstmm 注册的 ServerTickEvents.END_SERVER_TICK 回调。
     */
    public static void onServerTick() {
        flashedPlayers.forEach((uuid, duration) -> {
            if (duration <= 1) {
                flashedPlayers.remove(uuid);
            } else {
                flashedPlayers.put(uuid, duration - 1);
            }
        });
    }
}
