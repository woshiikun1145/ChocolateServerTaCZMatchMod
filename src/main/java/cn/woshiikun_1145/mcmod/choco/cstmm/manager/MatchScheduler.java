package cn.woshiikun_1145.mcmod.choco.cstmm.manager;

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
     *         整体经 ModGuardian.run 包装——保护模式启用时跳过；秒级调度内任意子系统
     *         抛出未捕获异常时进入保护模式（禁用模组）而非击穿到服务器崩溃。
     * 【被谁使用】Cstmm 注册的 ServerTickEvents.END_SERVER_TICK 回调（服务端主线程）。
     */
    public void onTick(MinecraftServer server) {
        ModGuardian.run("每秒调度（MatchScheduler）", () -> {
            tickCounter++;
            if (tickCounter >= 20) {
                tickCounter = 0;
                onSecondTick(server);
            }
        });
    }

    /**
     * 【作用】每秒执行一次的调度体：依次驱动队列匹配、对局推进、投票计时、背包自动保存与网络握手重试。
     *         此前各任务分别 try/catch 吞异常；现统一交由 onTick 的 ModGuardian 保护——
     *         任一子系统发生不可恢复异常即进入保护模式，避免带病运行。
     * 【被谁使用】onTick（服务端内部，每秒一次）。
     */
    private void onSecondTick(MinecraftServer server) {
        QueueManager.getInstance().tryMatch();
        MatchManager.getInstance().tick();
        VoteManager.getInstance().tick(server);
        InventoryManager.getInstance().autoSave();
        NetworkHandler.tickHandshake();
    }
}