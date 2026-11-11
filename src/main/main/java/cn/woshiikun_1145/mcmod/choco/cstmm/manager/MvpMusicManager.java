package cn.woshiikun_1145.mcmod.choco.cstmm.manager;

import cn.woshiikun_1145.mcmod.choco.cstmm.Cstmm;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.MatchSession;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.PlayerProfile;
import cn.woshiikun_1145.mcmod.choco.cstmm.network.payload.MvpMusicPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 【作用】MVP 音乐判定与广播（仅服务端）：
 *         死亡竞赛模式——对局结算时取击杀最多者（并列取死亡更少者，再并列随机）；
 *         爆破模式——每回合结束按胜负方式判定：
 *           CT 拆弹胜利 = 拆除者；CT 全歼/超时胜利 = 回合击杀最多 → 伤害最多 → 随机；
 *           T 爆炸胜利 = 回合击杀最多（并列时安放者）→ 伤害最多 → 随机；
 *           T 全歼胜利 = 回合击杀最多 → 伤害最多 → 随机。
 *         判定出 MVP 后读取其档案中的 mvpMusicName，向对局内全体在线玩家下发 MvpMusicPayload；
 *         MVP 未选择音乐时静默跳过（不播报、不占用带宽）。
 * 【被谁使用】BombRoundManager#endRound（每个爆破回合结束）、MatchManager#endMatch（死亡竞赛对局结算）。
 */
public final class MvpMusicManager {

    /** 拆除/爆炸胜利时延后播放，避开拆除确认音与 T/CT 胜利音效 */
    private static final int OBJECTIVE_DELAY_MS = 2500;
    /** 全歼/超时胜利时短暂延后，避开回合结果播报 */
    private static final int ELIMINATION_DELAY_MS = 1000;

    private MvpMusicManager() {}

    /**
     * 【作用】爆破回合结束的 MVP 音乐播报。
     * @param winnerTeam 回合胜方队伍编号（1/2）
     * @param reason     回合结束原因（"炸弹已被拆除"/"C4 爆炸"/"CT 方被全歼"/"时间耗尽" 等）
     * 【被谁使用】BombRoundManager#endRound（服务端）。
     */
    public static void onBombRoundEnd(MatchSession session, int winnerTeam, String reason) {
        if (session == null || winnerTeam != 1 && winnerTeam != 2) return;
        String reasonText = reason == null ? "" : reason;

        UUID mvp = null;
        int delay;
        if (winnerTeam != session.getTTeam()) {
            // CT 胜利
            if (reasonText.contains("拆除")) {
                mvp = session.getRoundDefuser();
                delay = OBJECTIVE_DELAY_MS;
            } else {
                mvp = pickTopKillsDamage(session, winnerTeam);
                delay = ELIMINATION_DELAY_MS;
            }
        } else {
            // T 胜利
            if (reasonText.contains("C4 爆炸")) {
                mvp = pickTopKillsWithPlanterTieBreak(session, winnerTeam);
                delay = OBJECTIVE_DELAY_MS;
            } else {
                mvp = pickTopKillsDamage(session, winnerTeam);
                delay = ELIMINATION_DELAY_MS;
            }
        }
        sendIfPresent(session, mvp, delay, true);
    }

    /**
     * 【作用】死亡竞赛模式对局结算的 MVP 音乐播报：击杀最多（并列死亡更少，再并列随机），0 杀不播。
     * 【被谁使用】MatchManager#endMatch（非爆破对局，服务端）。
     */
    public static void onNormalMatchEnd(MatchSession session) {
        if (session == null || session.isBombMode()) return;

        List<UUID> candidates = new ArrayList<>(session.getAllPlayers());
        int bestKills = 0;
        for (UUID uuid : candidates) {
            bestKills = Math.max(bestKills, session.getPlayerKills(uuid));
        }
        if (bestKills <= 0) return; // 全场无击杀：不评 MVP

        // 击杀并列 → 死亡更少者
        List<UUID> best = new ArrayList<>();
        int fewestDeaths = Integer.MAX_VALUE;
        for (UUID uuid : candidates) {
            if (session.getPlayerKills(uuid) != bestKills) continue;
            int deaths = session.getPlayerDeaths(uuid);
            if (deaths < fewestDeaths) {
                fewestDeaths = deaths;
                best.clear();
                best.add(uuid);
            } else if (deaths == fewestDeaths) {
                best.add(uuid);
            }
        }
        sendIfPresent(session, best.isEmpty() ? null : best.get(randomIndex(best.size())), 0, false);
    }

    // ==================== 判定辅助 ====================

    /**
     * 【作用】回合击杀最多 → 伤害最多 → 随机（限定指定队伍成员）。
     *         全员 0 杀且 0 伤时返回 null（无有效表现，不评 MVP）。
     */
    private static UUID pickTopKillsDamage(MatchSession session, int team) {
        List<UUID> roster = teamMembers(session, team);
        if (roster.isEmpty()) return null;

        int bestKills = Integer.MIN_VALUE;
        double bestDamage = -1;
        for (UUID uuid : roster) {
            bestKills = Math.max(bestKills, session.getRoundKills(uuid));
        }
        if (bestKills <= 0) {
            // 无击杀：仍按伤害（如仅造成伤害未击杀），伤害也为 0 则不评
            for (UUID uuid : roster) {
                bestDamage = Math.max(bestDamage, session.getRoundDamage(uuid));
            }
            if (bestDamage <= 0) return null;
            List<UUID> dmgBest = new ArrayList<>();
            for (UUID uuid : roster) {
                if (session.getRoundDamage(uuid) >= bestDamage) dmgBest.add(uuid);
            }
            return dmgBest.get(randomIndex(dmgBest.size()));
        }

        List<UUID> killBest = new ArrayList<>();
        for (UUID uuid : roster) {
            if (session.getRoundKills(uuid) == bestKills) killBest.add(uuid);
        }
        if (killBest.size() == 1) return killBest.get(0);

        // 击杀并列 → 伤害最多 → 随机
        double maxDamage = -1;
        for (UUID uuid : killBest) {
            maxDamage = Math.max(maxDamage, session.getRoundDamage(uuid));
        }
        List<UUID> dmgBest = new ArrayList<>();
        for (UUID uuid : killBest) {
            if (session.getRoundDamage(uuid) >= maxDamage) dmgBest.add(uuid);
        }
        return dmgBest.get(randomIndex(dmgBest.size()));
    }

    /**
     * 【作用】T 方 C4 爆炸胜利：回合击杀最多；击杀并列时安放者优先；安放者不在并列名单
     *         或并列中无安放者时退回伤害最多 → 随机。
     */
    private static UUID pickTopKillsWithPlanterTieBreak(MatchSession session, int team) {
        UUID planter = session.getRoundPlanter();
        List<UUID> roster = teamMembers(session, team);
        if (roster.isEmpty()) return planter;

        int bestKills = Integer.MIN_VALUE;
        for (UUID uuid : roster) {
            bestKills = Math.max(bestKills, session.getRoundKills(uuid));
        }
        if (bestKills <= 0) {
            // 无击杀：直接给安放者（若在队伍内），否则按伤害
            if (planter != null && roster.contains(planter)) return planter;
            return pickTopKillsDamage(session, team);
        }

        List<UUID> killBest = new ArrayList<>();
        for (UUID uuid : roster) {
            if (session.getRoundKills(uuid) == bestKills) killBest.add(uuid);
        }
        if (planter != null && killBest.contains(planter)) return planter;

        double maxDamage = -1;
        for (UUID uuid : killBest) {
            maxDamage = Math.max(maxDamage, session.getRoundDamage(uuid));
        }
        List<UUID> dmgBest = new ArrayList<>();
        for (UUID uuid : killBest) {
            if (session.getRoundDamage(uuid) >= maxDamage) dmgBest.add(uuid);
        }
        return dmgBest.get(randomIndex(dmgBest.size()));
    }

    /** 指定队伍成员列表（红=1、蓝=2）。 */
    private static List<UUID> teamMembers(MatchSession session, int team) {
        return team == 1 ? new ArrayList<>(session.getRedPlayers()) : new ArrayList<>(session.getBluePlayers());
    }

    private static int randomIndex(int size) {
        return size <= 1 ? 0 : ThreadLocalRandom.current().nextInt(size);
    }

    /**
     * 【作用】读取 MVP 档案中的音乐选择并广播 MvpMusicPayload；MVP 为空 / 未选音乐 / 服务端
     *         无在线玩家时静默跳过（不发送空包）。
     */
    private static void sendIfPresent(MatchSession session, UUID mvp, int delayMs, boolean bombMode) {
        if (mvp == null) return;
        PlayerProfile profile = PlayerDataManager.getInstance().getProfile(mvp);
        if (profile == null) return;
        String musicName = profile.getMvpMusicName();
        if (musicName.isEmpty()) return;

        String mvpName = session.getPlayerName(mvp);
        if (mvpName == null || mvpName.isEmpty()) mvpName = profile.getPlayerName();
        if (mvpName == null) mvpName = "?";

        MinecraftServer server = Cstmm.getServer();
        if (server == null) return;

        MvpMusicPayload payload = new MvpMusicPayload(mvpName, musicName, delayMs, bombMode);
        for (UUID uuid : session.getAllPlayers()) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
            if (player != null) {
                ServerPlayNetworking.send(player, payload);
            }
        }
        Cstmm.LOGGER.info("[CSTMM - MvpMusic] {} MVP: {} (music: {}, bomb={})",
                session.getSessionId(), mvpName, musicName, bombMode);
    }
}
