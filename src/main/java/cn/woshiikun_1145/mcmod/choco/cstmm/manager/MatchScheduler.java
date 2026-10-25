package cn.woshiikun_1145.mcmod.choco.cstmm.manager;

import cn.woshiikun_1145.mcmod.choco.cstmm.network.NetworkHandler;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 【作用】服务端秒级调度器：累计服务端 tick，每满 20 tick（1 秒）触发一次秒级调度，统一驱动匹配检测、对局推进、投票计时、背包自动保存与网络握手重试，各任务相互隔离。
 * 【被谁使用】Cstmm#registerTickListener 注册的服务端 tick 事件回调（服务端主线程）。
 */
public class MatchScheduler {
    private static MatchScheduler instance;
    // tick 计数器：累计到 20（1 秒）触发一次秒级调度
    private int tickCounter = 0;

    /** 延迟任务（毫秒精度，仅服务端主线程访问）：到期时间戳 + 动作 */
    private record DelayedTask(long dueAtMillis, Runnable action) {}

    /** 延迟任务队列（C4 起爆引信等短延时结算；须在服务端主线程调度） */
    private final List<DelayedTask> delayedTasks = new ArrayList<>();

    private MatchScheduler() {}

    public static MatchScheduler getInstance() {
        if (instance == null) {
            instance = new MatchScheduler();
        }
        return instance;
    }

    /**
     * 【作用】注册一个延迟任务：delayMillis 毫秒后在服务端主线程执行 action
     *         （逐 tick 检查到期，误差 ≤1 tick）。仅用于短延时（秒级）结算，如 C4 引信音播完再爆炸。
     * 【被谁使用】BombRoundManager#beginDetonationSequence（C4 起爆延迟结算）。仅服务端主线程。
     */
    public static void scheduleDelayed(long delayMillis, Runnable action) {
        getInstance().delayedTasks.add(new DelayedTask(System.currentTimeMillis() + delayMillis, action));
    }

    /**
     * 【作用】服务端每 tick 回调入口：累计 tick 计数，每满 20 tick（1 秒）触发一次秒级调度。
     *         整体经 ModGuardian.run 包装——保护模式启用时跳过；秒级调度内任意子系统
     *         抛出未捕获异常时进入保护模式（禁用模组）而非击穿到服务器崩溃。
     * 【被谁使用】Cstmm 注册的 ServerTickEvents.END_SERVER_TICK 回调（服务端主线程）。
     */
    public void onTick(MinecraftServer server) {
        ModGuardian.run("每秒调度（MatchScheduler）", () -> {
            // 爆破交互每 tick 推进（安放/拆除进度累计；无爆破对局时近零开销）
            BombInteractionManager.getInstance().tick(server);
            // 对局播报音效延迟任务推进（爆炸后 1 秒 T 胜利音等）
            cn.woshiikun_1145.mcmod.choco.cstmm.sound.MatchSoundPlayer.tick(server);
            // 延迟任务推进（C4 引信音播完 1.5 秒后真正爆炸结算等）
            tickDelayedTasks();
            tickCounter++;
            if (tickCounter >= 20) {
                tickCounter = 0;
                onSecondTick(server);
            }
        });
    }

    /** 逐 tick 推进延迟任务：到期的任务出队并在服务端主线程执行（异常由 ModGuardian 统一保护）。 */
    private void tickDelayedTasks() {
        if (delayedTasks.isEmpty()) return;
        long now = System.currentTimeMillis();
        Iterator<DelayedTask> it = delayedTasks.iterator();
        while (it.hasNext()) {
            DelayedTask task = it.next();
            if (now < task.dueAtMillis()) continue;
            it.remove();
            task.action().run();
        }
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