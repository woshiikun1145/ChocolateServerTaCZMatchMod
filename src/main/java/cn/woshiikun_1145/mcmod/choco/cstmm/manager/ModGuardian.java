package cn.woshiikun_1145.mcmod.choco.cstmm.manager;

import cn.woshiikun_1145.mcmod.choco.cstmm.Cstmm;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.MatchSession;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

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

    /** 不可恢复异常日志文件：config/cstmm/debug/logs/fatalerr.log（保护模式触发时追加写入） */
    private static final DateTimeFormatter FATAL_LOG_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /** 公屏广播的堆栈行上限（避免刷屏；完整堆栈写入 fatalerr.log） */
    private static final int BROADCAST_STACK_LINES = 8;

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
            appendFatalLog(where, t);
            return;
        }
        protectionActive = true;

        // ① 完整错误信息与堆栈打印到控制台（用户要求的核心动作）
        Cstmm.LOGGER.error("============================================================");
        Cstmm.LOGGER.error("[CSTMM] 保护模式已启用：{} 发生任意不可恢复异常，模组全部功能已停用", where);
        Cstmm.LOGGER.error("[CSTMM] 服务器本体将继续运行；重启服务器或重启模组以恢复模组功能", t);
        Cstmm.LOGGER.error("[CSTMM] 异常完整堆栈如下：");
        Cstmm.LOGGER.error("------------------------------------------------------------", t);
        Cstmm.LOGGER.error("============================================================");

        appendFatalLog(where, t);
        broadcastWarning(where, t);
        forceEndAllMatches();
    }

    /** fatalerr.log 的绝对路径（get_logs 下载命令复用）。 */
    public static Path fatalErrorLogPath() {
        return FabricLoader.getInstance().getConfigDir()
                .resolve("cstmm").resolve("debug").resolve("logs").resolve("fatalerr.log");
    }

    /**
     * 【作用】把不可恢复异常的完整信息追加写入 config/cstmm/debug/logs/fatalerr.log。
     *         自身绝不允许抛出：写盘失败只记录到控制台。
     */
    private static void appendFatalLog(String where, Throwable t) {
        try {
            Path path = fatalErrorLogPath();
            Files.createDirectories(path.getParent());
            StringBuilder sb = new StringBuilder();
            sb.append("==================== CSTMM FATAL ERROR ====================\n");
            sb.append("time: ").append(LocalDateTime.now().format(FATAL_LOG_TIME)).append('\n');
            sb.append("where: ").append(where).append('\n');
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            sb.append(sw);
            if (!sb.toString().endsWith("\n")) sb.append('\n');
            Files.writeString(path, sb.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Throwable logFailure) {
            Cstmm.LOGGER.error("[CSTMM - 保护模式] 写入 fatalerr.log 失败：", logFailure);
        }
    }

    /**
     * 向全体在线玩家（公屏）广播保护模式与错误信息（自身兜底，绝不抛出）。
     * 完整堆栈见 fatalerr.log；公屏仅发摘要 + 前若干行堆栈，避免刷屏。
     */
    private static void broadcastWarning(String where, Throwable t) {
        try {
            MinecraftServer server = Cstmm.getServer();
            if (server == null) return;
            java.util.List<String> lines = new java.util.ArrayList<>();
            lines.add("§c[CSTMM] 模组内部发生不可恢复异常，已进入保护模式并停用全部功能");
            lines.add("§e位置: §f" + where);
            lines.add("§c错误: §f" + t);
            StackTraceElement[] stack = t.getStackTrace();
            for (int i = 0; i < stack.length && i < BROADCAST_STACK_LINES; i++) {
                lines.add("§7  at " + stack[i]);
            }
            if (stack.length > BROADCAST_STACK_LINES) {
                lines.add("§7  ... 其余 " + (stack.length - BROADCAST_STACK_LINES) + " 行见日志");
            }
            lines.add("§e完整堆栈已写入 config/cstmm/debug/logs/fatalerr.log（/cstmm debug get_logs fatalerr 可下载）");
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                for (String line : lines) {
                    player.sendMessage(Text.literal(truncateForChat(line)), false);
                }
            }
        } catch (Throwable ignored) {
            // 广播失败不影响保护流程
        }
    }

    /** 聊天单行长度保护（原版客户端单条聊天存在长度上限，超长会被截断/丢弃）。 */
    private static String truncateForChat(String line) {
        return line.length() <= 240 ? line : line.substring(0, 237) + "...";
    }

    /** 尽力强制结算所有活跃对局（endMatch 恢复玩家原点/背包/游戏模式）：逐会话隔离，
     *  单个会话结算失败只记日志（对局保留结算失败标记，重启模组服务时补结算），不中断其余会话。 */
    private static void forceEndAllMatches() {
        for (MatchSession session : MatchManager.getInstance().getAllActiveSessions()) {
            if (session.getPhase() == MatchSession.GamePhase.ENDED) continue;
            try {
                MatchManager.getInstance().endMatch(session, "§cCSTMM发生任意不可恢复异常，已进入保护模式，对局被强制结束", 0);
            } catch (Throwable t) {
                Cstmm.LOGGER.error("[CSTMM - 保护模式] 强制结算对局 {} 失败（继续结算其余对局）：",
                        session.getSessionId(), t);
            }
        }
    }

    /**
     * 【作用】保护模式下强制重启模组服务（/cstmm debug restart_cstmm）：
     *         ① 清空调度器挂起的延迟任务（对局已强制结算，残留任务目标已失效）；
     *         ② 解除保护模式（各入口恢复执行——事件监听恢复响应后对局结算/恢复才完整）；
     *         ③ 兜底再结算一遍残留活跃对局；④ 补结算"整场结算失败"的已结束对局
     *         （engage 时 endMatch 异常置 ENDED 的对局，其玩家从未被恢复原点/背包/模式）；
     *         ⑤ 与 SERVER_STARTED 同源重载配置/玩家数据/背包存储；⑥ 清理残留的头顶名字前缀队伍。
     *         各步骤独立兜底：任何一步抛异常经 run 重新进入保护模式（幂等，已完成的步骤保留）。
     * 【被谁使用】ModCommands#debugRestartCstmm。仅服务端主线程。
     */
    public static void restartServices() {
        Cstmm.LOGGER.info("[CSTMM - 保护模式] 正在重启模组服务...");
        MatchScheduler.getInstance().clearPendingTasks();
        // 先解除保护再结算/重载：结算过程中的玩家重生等事件监听须正常响应
        protectionActive = false;
        Cstmm.LOGGER.info("[CSTMM - 保护模式] 保护模式已解除，开始重载配置与数据");
        forceEndAllMatches();
        run("重启补结算失败的对局", () -> MatchManager.getInstance().retryFailedSettlements());
        run("重启重载配置", () -> ConfigManager.getInstance().load());
        run("重启重载玩家数据", () -> PlayerDataManager.getInstance().loadAll());
        run("重启重载背包存储", () -> {
            MinecraftServer server = Cstmm.getServer();
            InventoryManager.initialize(server != null ? server.getRegistryManager() : null);
        });
        run("重启清理残留前缀队伍", MatchManager::clearAllNameTagTeams);
        Cstmm.LOGGER.info("[CSTMM - 保护模式] 模组服务重启完成（protectActive={}）", protectionActive);
    }
}
