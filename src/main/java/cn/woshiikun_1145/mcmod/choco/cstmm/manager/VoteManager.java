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

public class VoteManager implements VoteApi {
    private static VoteManager instance;

    private final Map<String, VoteSession> activeVotes;
    // key: 发起者 UUID, value: 上次发起投票的时间戳（毫秒）
    private final Map<UUID, Long> kickCooldowns;

    private VoteManager() {
        this.activeVotes = new ConcurrentHashMap<>();
        this.kickCooldowns = new ConcurrentHashMap<>();
    }

    public static VoteManager getInstance() {
        if (instance == null) {
            instance = new VoteManager();
        }
        return instance;
    }

    // ==================== API 实现 ====================

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

        String message = "§6=== 加时赛投票 ===\n" +
                "§e是否进行30秒加时赛？\n" +
                "§a需要 " + requiredYes + " 票同意 (过半)\n" +
                "§7按 §aF7 §7同意  |  按 §cF8 §7反对  |  剩余 30 秒";

        broadcastVoteMessage(session, message);
        Cstmm.LOGGER.info("[CSTMM - VoteManager] Started overtime vote for match {}", sessionId);
    }

    public void startOvertimeVote(MatchSession session) {
        if (session == null) return;
        startOvertimeVote(UUID.fromString(session.getSessionId()));
    }

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
                initiator.sendMessage(Text.literal("§c你发起踢人过于频繁，请等待 " + remaining + " 秒"), false);
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
        String message = "§6=== 踢人投票 ===\n" +
                "§e" + initiatorName + " 发起投票踢出 " + target.getName().getString() + "\n" +
                "§a需要 " + requiredYes + " 票同意 (过半)\n" +
                "§7按 §aF7 §7同意  |  按 §cF8 §7反对  |  剩余 30 秒";

        broadcastVoteMessage(session, message);
        Cstmm.LOGGER.info("[CSTMM - VoteManager] Started kick vote for {} by {}", target.getName().getString(), initiatorName);
    }

    @Override
    public void handleVote(UUID playerUuid, boolean agree) {
        ServerWorld world = getWorld();
        if (world == null) return;

        ServerPlayerEntity voter = world.getServer().getPlayerManager().getPlayer(playerUuid);
        if (voter == null) return;

        handleVote(voter, agree);
    }

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

    private ServerWorld getWorld() {
        MinecraftServer server = Cstmm.getServer();
        if (server != null) {
            return server.getWorld(ServerWorld.OVERWORLD);
        }
        return null;
    }

    public boolean hasActiveVote(String sessionId) {
        return activeVotes.containsKey(sessionId);
    }

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

    public enum VoteType {
        OVERTIME,
        KICK
    }

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