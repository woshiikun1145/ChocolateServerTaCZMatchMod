package cn.woshiikun_1145.mcmod.choco.cstmm.manager;

import cn.woshiikun_1145.mcmod.choco.cstmm.Cstmm;
import cn.woshiikun_1145.mcmod.choco.cstmm.network.NetworkHandler;
import net.minecraft.server.MinecraftServer;

public class MatchScheduler {
    private static MatchScheduler instance;
    private int tickCounter = 0;

    private MatchScheduler() {}

    public static MatchScheduler getInstance() {
        if (instance == null) {
            instance = new MatchScheduler();
        }
        return instance;
    }

    public void onTick(MinecraftServer server) {
        tickCounter++;
        if (tickCounter >= 20) {
            tickCounter = 0;
            onSecondTick(server);
        }
    }

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