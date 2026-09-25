package cn.woshiikun_1145.mcmod.choco.cstmm.manager;

import cn.woshiikun_1145.mcmod.choco.cstmm.Cstmm;
import cn.woshiikun_1145.mcmod.choco.cstmm.api.VoteApi;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.config.MapConfig;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.MatchSession;
import cn.woshiikun_1145.mcmod.choco.cstmm.network.NetworkHandler;
import cn.woshiikun_1145.mcmod.choco.cstmm.network.payload.MatchStatusPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 【作用】对局内投票管理器：加时赛投票与踢人投票的发起、计票、超时判定与结果执行。
 *         每场对局同一时间最多一个投票（activeVotes 按 sessionId 存放），投票时长 30 秒，
 *         同意票过在线人数半数即通过；也支持"剩余票不可能过半"时提前判负。
 * 【被谁使用】MatchManager（加时赛触发 startOvertimeVote、对局结束前检查 hasActiveVote）、
 *           NetworkHandler（玩家按 F7/F8 投票 handleVote）、ModCommands（/cstmm vote、/cstmm votekick 命令）、
 *           MatchScheduler（每秒 tick 推进倒计时）；其中 startOvertimeVote(UUID)/startKickVote(UUID,UUID)/
 *           handleVote(UUID,boolean)/getVoteStatus 为 VoteApi 外部 API 入口。仅服务端。
 */
public class VoteManager implements VoteApi {
    // 单例实例（getInstance 懒加载）
    private static VoteManager instance;
    /** 进行中的投票，key: 对局 sessionId（每场对局同时最多一个投票） */
    private final Map<String, VoteSession> activeVotes;
    // key: 发起者 UUID, value: 上次发起投票的时间戳（毫秒）
    private final Map<UUID, Long> kickCooldowns;

    private VoteManager() {
        this.activeVotes = new ConcurrentHashMap<>();
        this.kickCooldowns = new ConcurrentHashMap<>();
    }

    // 单例入口（懒加载）
    public static VoteManager getInstance() {
        if (instance == null) {
            instance = new VoteManager();
        }
        return instance;
    }

    // ==================== API 实现 ====================

    /**
     * 【作用】发起加时赛投票：向对局全员广播投票提示（30 秒，同意过半则加时 30 秒）；
     *         在线人数不足 2 人直接判平局结束对局。
     * 【被谁使用】VoteApi 外部 API 入口；内部重载 startOvertimeVote(MatchSession) 委托到此。
     */
    @Override
    public void startOvertimeVote(UUID matchId) {
        MatchSession session = resolveSession(matchId);
        if (session == null) return;

        String sessionId = session.getSessionId();

        if (activeVotes.containsKey(sessionId)) return;

        Set<UUID> allPlayers = session.getAllPlayers();
        int onlineCount = countOnlinePlayers(allPlayers);

        if (onlineCount < 2) {
            MatchManager.getInstance().endMatch(session, "§e人数不足，平局！", 0);
            return;
        }

        int requiredYes = (onlineCount / 2) + 1;

        VoteSession vote = new VoteSession(
                VoteType.OVERTIME,
                sessionId,
                null,
                requiredYes,
                30
        );

        activeVotes.put(sessionId, vote);

        String message = "§6=== 正在进行加时赛投票 ===\n" +
                "§e是否进行30秒加时赛？\n" +
                "§a需要 " + requiredYes + " 票同意 (过半)\n" +
                "§7按 §aF7 §7同意  |  按 §cF8 §7反对  |  剩余 30 秒";

        broadcastVoteMessage(session, message);
        Cstmm.LOGGER.info("[CSTMM - VoteManager] Started overtime vote for match {}", sessionId);
    }

    /**
     * 【作用】以 MatchSession 为参数的加时赛投票发起入口（把 session 转 UUID 后委托）。
     * 【被谁使用】MatchManager（对局计时器走完且配置了加时赛规则时触发）。
     */
    public void startOvertimeVote(MatchSession session) {
        if (session == null) return;
        startOvertimeVote(UUID.fromString(session.getSessionId()));
    }

    /**
     * 【作用】以 UUID 指定发起者与目标发起踢人投票（把 UUID 解析为在线玩家后委托）。
     * 【被谁使用】VoteApi 外部 API 入口（集成方持有 UUID 时调用）。
     */
    @Override
    public void startKickVote(UUID playerUuid, UUID targetUuid) {
        ServerWorld world = getWorld();
        if (world == null) return;

        ServerPlayerEntity initiator = world.getServer().getPlayerManager().getPlayer(playerUuid);
        ServerPlayerEntity target = world.getServer().getPlayerManager().getPlayer(targetUuid);

        if (initiator == null || target == null) {
            Cstmm.LOGGER.warn("[CSTMM - VoteManager] Initiator or target not found for kick vote");
            return;
        }

        startKickVote(initiator, target);
    }

    /**
     * 【作用】发起踢人投票：校验发起者冷却（按目标所在地图配置）、目标在对局中、
     *         无进行中投票、在线人数 ≥2，通过后记录冷却并广播投票提示。
     * 【被谁使用】ModCommands（/cstmm votekick 命令，支持控制台发起）、内部 UUID 重载委托。
     */
    public void startKickVote(ServerPlayerEntity initiator, ServerPlayerEntity target) {
        UUID targetUuid = target.getUuid();
        MatchSession session = MatchManager.getInstance().getPlayerSession(targetUuid);

        // ===== 冷却检查：针对发起者（控制台发起时 initiator 为 null，跳过冷却） =====
        // 冷却时长按目标所在地图的配置（kickCooldownSeconds）
        if (initiator != null && session != null) {
            UUID initiatorUuid = initiator.getUuid();
            MapConfig mapConfig = ConfigManager.getInstance().getMap(session.getMapName());
            long cooldownMillis = (mapConfig != null ? mapConfig.getKickCooldownSeconds() : 60) * 1000L;
            long lastVoteTime = kickCooldowns.getOrDefault(initiatorUuid, 0L);
            if (lastVoteTime != 0 && System.currentTimeMillis() - lastVoteTime < cooldownMillis) {
                long remaining = (cooldownMillis - (System.currentTimeMillis() - lastVoteTime)) / 1000;
                initiator.sendMessage(Text.literal("§c你发起踢人过于频繁。 " + remaining + " 秒"), false);
                return;
            }
        }

        // 检查目标是否可踢
        if (session == null) {
            if (initiator != null) {
                initiator.sendMessage(Text.literal("§c该玩家不在任何对局中！"), false);
            }
            return;
        }

        String sessionId = session.getSessionId();

        if (activeVotes.containsKey(sessionId)) {
            if (initiator != null) {
                initiator.sendMessage(Text.literal("§c当前对局已有投票进行中！"), false);
            }
            return;
        }

        if (initiator != null && !session.isPlayerInGame(initiator.getUuid())) {
            initiator.sendMessage(Text.literal("§c你不在该玩家的对局中！"), false);
            return;
        }

        Set<UUID> allPlayers = session.getAllPlayers();
        int onlineCount = countOnlinePlayers(allPlayers);

        if (onlineCount < 2) {
            if (initiator != null) {
                initiator.sendMessage(Text.literal("§c人数不足，无法发起投票！"), false);
            }
            return;
        }

        int requiredYes = (onlineCount / 2) + 1;

        // ===== 记录发起者的冷却（控制台不记录） =====
        if (initiator != null) {
            kickCooldowns.put(initiator.getUuid(), System.currentTimeMillis());
        }

        VoteSession vote = new VoteSession(
                VoteType.KICK,
                sessionId,
                targetUuid,
                requiredYes,
                30
        );

        activeVotes.put(sessionId, vote);

        String initiatorName = (initiator != null) ? initiator.getName().getString() : "控制台";
        String message = "§6=== 正在进行强制退场 ===\n" +
                "§e" + initiatorName + " 发起投票踢出 " + target.getName().getString() + "\n" +
                "§a需要 " + requiredYes + " 票同意 (过半)\n" +
                "§7按 §aF7 §7同意  |  按 §cF8 §7反对  |  剩余 30 秒";

        broadcastVoteMessage(session, message);
        Cstmm.LOGGER.info("[CSTMM - VoteManager] Started kick vote for {} by {}", target.getName().getString(), initiatorName);
    }

    /**
     * 【作用】以 UUID 指定投票人进行投票（解析为在线玩家后委托）。
     * 【被谁使用】VoteApi 外部 API 入口（集成方持有 UUID 时调用）。
     */
    @Override
    public void handleVote(UUID playerUuid, boolean agree) {
        ServerWorld world = getWorld();
        if (world == null) return;

        ServerPlayerEntity voter = world.getServer().getPlayerManager().getPlayer(playerUuid);
        if (voter == null) return;

        handleVote(voter, agree);
    }

    /**
     * 【作用】处理一次投票：去重校验 → 记票并广播进度 → 同意票达门槛立即通过 /
     *         剩余票已不可能过半则立即判负（提前结束投票）。
     * 【被谁使用】NetworkHandler（客户端 F7/F8 按键的 VOTE_YES/VOTE_NO payload）、
     *           ModCommands（/cstmm vote yes/no 命令）、内部 UUID 重载委托。
     */
    public void handleVote(ServerPlayerEntity voter, boolean agree) {
        UUID voterUuid = voter.getUuid();

        MatchSession session = MatchManager.getInstance().getPlayerSession(voterUuid);
        if (session == null) {
            voter.sendMessage(Text.literal("§c你不在任何对局中！"), false);
            return;
        }

        String sessionId = session.getSessionId();
        VoteSession vote = activeVotes.get(sessionId);

        if (vote == null) {
            voter.sendMessage(Text.literal("§c当前没有进行中的投票！"), false);
            return;
        }

        if (vote.voters.contains(voterUuid)) {
            voter.sendMessage(Text.literal("§c你已经投过票了！"), false);
            return;
        }

        vote.voters.add(voterUuid);
        if (agree) {
            vote.yesVotes++;
        } else {
            vote.noVotes++;
        }

        String progress = "§7投票进度: §a" + vote.yesVotes + " §7同意  |  §c" + vote.noVotes + " §7反对  |  需要 §a" + vote.requiredYes + " §7票同意";
        broadcastVoteMessage(session, progress);

        if (vote.yesVotes >= vote.requiredYes) {
            executeVoteResult(vote, session, true);
            activeVotes.remove(sessionId);
            return;
        }

        int totalVoted = vote.voters.size();
        int remainingVoters = countOnlinePlayers(session.getAllPlayers()) - totalVoted;
        if (vote.yesVotes + remainingVoters < vote.requiredYes) {
            executeVoteResult(vote, session, false);
            activeVotes.remove(sessionId);
        }
    }

    // 【作用】执行投票结果：加时票通过则重置倒计时 30 秒回 FIGHTING，未过则判平局结束对局；
    //        踢人票通过则恢复被踢者原点/背包后断开其连接，若踢空一队则直接判负
    private void executeVoteResult(VoteSession vote, MatchSession session, boolean passed) {
        String resultMessage;

        if (vote.type == VoteType.OVERTIME) {
            if (passed) {
                session.setRemainingSeconds(30);
                session.setPhase(MatchSession.GamePhase.FIGHTING);
                resultMessage = "§a加时赛投票通过！加时30秒！";
                Cstmm.LOGGER.info("[CSTMM - VoteManager] Overtime passed for match {}", session.getSessionId());
            } else {
                resultMessage = "§e加时赛投票未通过，平局！";
                MatchManager.getInstance().endMatch(session, "§e加时赛投票未通过，平局！", 0);
                Cstmm.LOGGER.info("[CSTMM - VoteManager] Overtime failed for match {}", session.getSessionId());
            }
            broadcastVoteMessage(session, resultMessage);

        } else if (vote.type == VoteType.KICK) {
            if (passed) {
                UUID targetUuid = vote.target;
                ServerWorld world = getWorld();
                if (world != null) {
                    ServerPlayerEntity target = world.getServer().getPlayerManager().getPlayer(targetUuid);
                    if (target != null) {
                        // 注意：不再设置冷却（冷却已在发起时设置）
                        // 先恢复原点/背包/游戏模式再断开，防止重连后滞留冒险模式或错位
                        MatchManager.getInstance().restoreKickedPlayer(targetUuid);
                        target.networkHandler.disconnect(Text.literal("§c你被投票踢出游戏！"));
                        session.getRedPlayers().remove(targetUuid);
                        session.getBluePlayers().remove(targetUuid);
                        MatchManager.getInstance().removePlayerFromSession(targetUuid);
                        resultMessage = "§c" + target.getName().getString() + " 被投票踢出游戏！";

                        // 检查队伍是否为空，若空则判负
                        if (session.getRedPlayers().isEmpty()) {
                            MatchManager.getInstance().endMatch(session, "§9红队已无人在线，蓝队获胜！", 2);
                        } else if (session.getBluePlayers().isEmpty()) {
                            MatchManager.getInstance().endMatch(session, "§c蓝队已无人在线，红队获胜！", 1);
                        }
                    } else {
                        resultMessage = "§e目标玩家已离线，投票取消";
                    }
                } else {
                    resultMessage = "§e无法执行投票结果 (世界不可用)";
                }
            } else {
                // 投票未通过，不执行踢出
                resultMessage = "§e踢出 " + (vote.target != null ? vote.target.toString() : "") + " 投票未通过";
                Cstmm.LOGGER.info("[CSTMM - VoteManager] Kick vote failed");
            }
            broadcastVoteMessage(session, resultMessage);
        }
    }

    /**
     * 【作用】每秒推进所有投票倒计时：到时仍未通过的按"未通过"执行并移除；
     *         对局已结束的投票直接清理（不执行结果、不发消息）。
     * 【被谁使用】MatchScheduler（服务器每秒 tick 中调用）。仅服务端。
     */
    public void tick(MinecraftServer server) {
        for (Map.Entry<String, VoteSession> entry : new HashMap<>(activeVotes).entrySet()) {
            VoteSession vote = entry.getValue();
            vote.remainingSeconds--;

            if (vote.remainingSeconds <= 0) {
                MatchSession session = MatchManager.getInstance().getSession(vote.sessionId);
                // 对局已结束时直接清理投票，不执行结果、不发消息
                if (session != null && session.getPhase() != MatchSession.GamePhase.ENDED) {
                    executeVoteResult(vote, session, false);
                }
                activeVotes.remove(entry.getKey());
                Cstmm.LOGGER.debug("[CSTMM - VoteManager] Vote timed out for session {}", entry.getKey());
            }
        }
    }

    // ==================== 辅助方法 ====================

    // 【作用】向对局全员广播投票消息（走 MatchStatusPayload，客户端统一展示避免重复）
    private void broadcastVoteMessage(MatchSession session, String message) {
        MinecraftServer server = Cstmm.getServer();
        if (server == null) return;
        for (UUID uuid : session.getAllPlayers()) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
            if (player != null) {
                // 仅通过 payload 发送，客户端收到后统一展示，避免消息显示两遍
                NetworkHandler.sendMatchStatus(player, new MatchStatusPayload(
                        MatchStatusPayload.StatusType.VOTE_STARTED,
                        message,
                        session.getRedKills(),
                        session.getBlueKills()
                ));
            }
        }
    }

    // 【作用】统计玩家集合中当前在线的人数（投票门槛按在线人数计算）
    private int countOnlinePlayers(Set<UUID> uuids) {
        MinecraftServer server = Cstmm.getServer();
        if (server == null) return 0;
        int count = 0;
        for (UUID uuid : uuids) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
            if (player != null) {
                count++;
            }
        }
        return count;
    }

    // 【作用】获取主世界引用（仅用于按 UUID 查找在线玩家，与对局所在维度无关）
    private ServerWorld getWorld() {
        MinecraftServer server = Cstmm.getServer();
        if (server != null) {
            return server.getWorld(ServerWorld.OVERWORLD);
        }
        return null;
    }

    // 查询某对局是否有进行中的投票（MatchManager 结束对局前检查用）
    public boolean hasActiveVote(String sessionId) {
        return activeVotes.containsKey(sessionId);
    }

    /**
     * 【作用】查询指定对局当前投票的状态快照（类型/目标/票数/剩余秒数）；无对局或无投票返回空状态。
     * 【被谁使用】VoteApi 外部 API 入口（供外部集成方只读查询）。
     */
    @Override
    public VoteStatus getVoteStatus(UUID matchId) {
        MatchSession session = resolveSession(matchId);
        if (session == null) {
            return new VoteStatus(null, null, 0, 0, 0, 0);
        }
        VoteSession vote = activeVotes.get(session.getSessionId());
        if (vote == null) {
            return new VoteStatus(null, null, 0, 0, 0, 0);
        }
        return new VoteStatus(
                vote.type == VoteType.OVERTIME ? VoteApi.VoteType.OVERTIME : VoteApi.VoteType.KICK,
                vote.target,
                vote.yesVotes,
                vote.noVotes,
                vote.requiredYes,
                vote.remainingSeconds
        );
    }

    /**
     * 解析对局：matchId 既可以是完整的对局 session UUID（session UUID 本身即 UUID），
     * 也可以是对局内任意玩家的 UUID，便于外部集成方调用（玩家 UUID 总是可得的）。
     */
    private MatchSession resolveSession(UUID matchId) {
        if (matchId == null) return null;
        MatchSession session = MatchManager.getInstance().getSession(matchId.toString());
        if (session != null) return session;
        return MatchManager.getInstance().getPlayerSession(matchId);
    }

    // ==================== 内部类 ====================

    /** 投票类型：加时赛 / 踢人（内部使用；对外经 VoteApi.VoteType 映射） */
    public enum VoteType {
        OVERTIME,
        KICK
    }

    /**
     * 【作用】单次投票的会话数据：类型、所属对局、踢人目标、票数门槛、当前票数、剩余秒数与已投玩家集合。
     * 【被谁使用】VoteManager 内部（发起/计票/超时逻辑的数据载体）。仅服务端。
     */
    public static class VoteSession {
        public final VoteType type;
        public final String sessionId;
        public final UUID target;
        public final int requiredYes;
        public int yesVotes;
        public int noVotes;
        public int remainingSeconds;
        public final Set<UUID> voters;

        public VoteSession(VoteType type, String sessionId, UUID target, int requiredYes, int duration) {
            this.type = type;
            this.sessionId = sessionId;
            this.target = target;
            this.requiredYes = requiredYes;
            this.yesVotes = 0;
            this.noVotes = 0;
            this.remainingSeconds = duration;
            this.voters = new HashSet<>();
        }
    }
}