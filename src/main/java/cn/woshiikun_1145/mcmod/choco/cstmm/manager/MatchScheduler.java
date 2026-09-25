package cn.woshiikun_1145.mcmod.choco.cstmm.manager;

import cn.woshiikun_1145.mcmod.choco.cstmm.Cstmm;
import cn.woshiikun_1145.mcmod.choco.cstmm.network.NetworkHandler;
import net.minecraft.server.MinecraftServer;

/**
 * 【作用】服务端秒级调度器：累计服务端 tick，每满 20 tick（1 秒）触发一次秒级调度，统一驱动匹配检测、对局推进、投票计时、背包自动保存与网络握手重试，各任务相互隔离。
 * 【被谁使用】Cstmm#registerTickListener 注册的服务端 tick 事件回调（服务端主线程）。
 */
public class MatchScheduler {
    private static MatchScheduler instance;
    // tick 计数器：累计到 20（1 秒）触发一次秒级调度
    private int tickCounter = 0;

    private MatchScheduler() {}

    // 单例获取（Cstmm 在服务端 tick 事件中调用）
    public static MatchScheduler getInstance() {
        if (instance == null) {
            instance = new MatchScheduler();
        }
        return instance;
    }

    /**
     * 【作用】服务端每 tick 回调入口：累计 tick 计数，每满 20 tick（1 秒）触发一次秒级调度。
     * 【被谁使用】Cstmm 注册的 ServerTickEvents.END_SERVER_TICK 回调（服务端主线程）。
     */
    public void onTick(MinecraftServer server) {
        tickCounter++;
        if (tickCounter >= 20) {
            tickCounter = 0;
            onSecondTick(server);
        }
    }

    /**
     * 【作用】每秒执行一次的调度体：依次驱动队列匹配、对局推进、投票计时、背包自动保存与网络握手重试，单个任务异常不影响其他任务。
     * 【被谁使用】onTick（服务端内部，每秒一次）。
     */
    private void onSecondTick(MinecraftServer server) {
        // 每个管理器独立隔离，互不影响
        try {
            QueueManager.getInstance().tryMatch();
        } catch (Exception e) {
            Cstmm.LOGGER.error("[CSTMM - MatchScheduler] Error in QueueManager.tryMatch", e);
        }
        try {
            MatchManager.getInstance().tick();
        } catch (Exception e) {
            Cstmm.LOGGER.error("[CSTMM - MatchScheduler] Error in MatchManager.tick", e);
        }
        try {
            VoteManager.getInstance().tick(server);      // 修复P0-2：每秒只减1秒
        } catch (Exception e) {
            Cstmm.LOGGER.error("[CSTMM - MatchScheduler] Error in VoteManager.tick", e);
        }
        try {
            InventoryManager.getInstance().autoSave();
        } catch (Exception e) {
            Cstmm.LOGGER.error("[CSTMM - MatchScheduler] Error in InventoryManager.autoSave", e);
        }
        try {
            NetworkHandler.tickHandshake();              // 握手重试（最多 2 次）
        } catch (Exception e) {
            Cstmm.LOGGER.error("[CSTMM - MatchScheduler] Error in NetworkHandler.tickHandshake", e);
        }
    }
}