package cn.woshiikun_1145.mcmod.choco.cstmm.manager;

import cn.woshiikun_1145.mcmod.choco.cstmm.Cstmm;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.MatchSession;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

/**
 * 【作用】模组全局异常保护（保护模式）中枢：模组的任意服务端入口（每秒调度、事件监听、
 *         网络接收器、命令执行器、生命周期回调）发生会击穿到服务器崩溃的未捕获异常时，
 *         由 {@link #engage} 捕获并进入保护模式——完整错误堆栈打印到服务器控制台、
 *         向全体在线玩家广播警告、尽力强制结算所有活跃对局（恢复玩家原点/背包/游戏模式），
 *         此后模组全部功能停用（各入口经 {@link #isDisabled} 或 {@link #run} 直接跳过），
 *         服务器本体继续运行不受影响。保护模式持续到服务器重启。
 * 【被谁使用】MatchScheduler（每秒调度整体包装）、EventListener（五个事件监听器）、
 *           NetworkHandler（全部 C2S 接收器与 JOIN/DISCONNECT 回调）、ModCommands（guard 包装全部命令执行器）、
 *           Cstmm（初始化注册步骤与生命周期回调）。仅服务端。
 */
public final class ModGuardian {

    /** 保护模式是否已启用（volatile：网络/事件线程可见；启用动作实际都在服务端主线程发生） */
    private static volatile boolean protectionActive = false;

    private ModGuardian() {}

    /** 保护模式是否已启用（模组功能是否已全部停用）。 */
    public static boolean isDisabled() {
        return protectionActive;
    }

    /**
     * 【作用】在保护上下文中运行任务：保护已启用时直接跳过（模组停用语义）；
     *         任务抛出任意 Throwable（含 Error）时调用 {@link #engage} 启用保护模式，不向调用方抛出。
     * @param where 任务位置描述（仅用于控制台日志定位）
     */
    public static void run(String where, Runnable task) {
        if (protectionActive) return;
        try {
            task.run();
        } catch (Throwable t) {
            engage(where, t);
        }
    }

    /**
     * 【作用】启用保护模式（幂等：重复启用只追加日志）：
     *         ① 完整异常堆栈打印到服务器控制台；② 向全体在线玩家广播警告；
     *         ③ 尽力强制结算所有活跃对局（endMatch 会恢复玩家原点/背包/游戏模式，
     *         避免保护模式停用调度后玩家被永久滞留在对局地图）。
     *         本方法自身绝不允许抛出——所有副作用均单独兜底。
     */
    public static void engage(String where, Throwable t) {
        if (protectionActive) {
            // 保护已启用后各入口本应被跳过；再次到达说明有未包装路径，记录但不重复启用
            Cstmm.LOGGER.error("[CSTMM - 保护模式] {} 再次发生未捕获异常（保护已启用，仅记录）：", where, t);
            return;
        }
        protectionActive = true;

        // ① 完整错误信息与堆栈打印到控制台（用户要求的核心动作）
        Cstmm.LOGGER.error("============================================================");
        Cstmm.LOGGER.error("[CSTMM] 保护模式已启用：{} 发生任意不可恢复异常，模组全部功能已停用", where);
        Cstmm.LOGGER.error("[CSTMM] 服务器本体将继续运行；重启服务器以恢复模组功能", t);
        Cstmm.LOGGER.error("[CSTMM] 异常完整堆栈如下：");
        Cstmm.LOGGER.error("------------------------------------------------------------", t);
        Cstmm.LOGGER.error("============================================================");

        broadcastWarning(where);
        forceEndAllMatches();
    }

    /** 向全体在线玩家广播保护模式警告（自身兜底，绝不抛出）。 */
    private static void broadcastWarning(String where) {
        try {
            MinecraftServer server = Cstmm.getServer();
            if (server == null) return;
            String message = "§c[CSTMM] 模组内部发生任意不可恢复异常（" + where + "），已进入保护模式并停用全部功能，详情请查看服务器控制台日志";
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                player.sendMessage(Text.literal(message), false);
            }
        } catch (Throwable ignored) {
            // 广播失败不影响保护流程
        }
    }

    /** 尽力强制结算所有活跃对局（endMatch 恢复玩家原点/背包/游戏模式），失败仅记录。 */
    private static void forceEndAllMatches() {
        try {
            for (MatchSession session : MatchManager.getInstance().getAllActiveSessions()) {
                if (session.getPhase() != MatchSession.GamePhase.ENDED) {
                    MatchManager.getInstance().endMatch(session, "§cCSTMM发生任意不可恢复异常，已进入保护模式，对局被强制结束", 0);
                }
            }
        } catch (Throwable t) {
            Cstmm.LOGGER.error("[CSTMM - 保护模式] 强制结算活跃对局失败（保护流程继续）：", t);
        }
    }
}
