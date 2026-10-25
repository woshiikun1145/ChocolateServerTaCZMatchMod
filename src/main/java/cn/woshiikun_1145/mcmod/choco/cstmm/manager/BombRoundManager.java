package cn.woshiikun_1145.mcmod.choco.cstmm.manager;

import cn.woshiikun_1145.mcmod.choco.cstmm.Cstmm;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.MatchSession;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.config.MapConfig;
import cn.woshiikun_1145.mcmod.choco.cstmm.item.ModItems;
import cn.woshiikun_1145.mcmod.choco.cstmm.network.NetworkHandler;
import cn.woshiikun_1145.mcmod.choco.cstmm.network.payload.MatchStatusPayload;
import cn.woshiikun_1145.mcmod.choco.cstmm.sound.MatchSoundTimings;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.ClientStatusC2SPacket;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 【作用】爆破模式回合状态机（仅 gameType=BOMB 的地图对局）：多回合赛制（休闲先到 8 分；
 *         竞技 MR12——先到 13 胜、12:12 进加时 BO6 先到 4 胜、3:3 投票决定继续或平局）、
 *         攻防转换（竞技第 13 回合、加时第 4 回合）、回合重置（复活/传送/装备/冻结）、
 *         回合胜负判定（全歼/C4 爆炸/拆除/超时，含"安放后全歼 T 不结束回合"的 CS 规则）、
 *         旁观者死亡处理与 3 分钟重连宽限（整队离线挂起等待、回合结束清算超时者）。
 *         C4 实体/安放拆除交互（阶段③）与爆炸伤害计算（阶段④）挂接在本类的炸弹状态字段上。
 * 【被谁使用】MatchManager（startMatch 爆破分支、tick 爆破分支、handleRespawn/onPlayerDisconnect
 *           委托、endMatch 清理）、EventListener（死亡/断线/重连事件）、VoteManager（加时继续投票）。
 *           仅服务端。
 */
public class BombRoundManager {

    private static BombRoundManager instance;

    // 回合结束后的间歇秒数（展示回合结果与经济结算后开下一回合或结算整场）
    // （回合间歇时长已改为每图可配：MapConfig.BombSettings.roundIntermissionSeconds，默认 5 秒）
    /** 重连宽限时长（毫秒）：3 分钟 */
    private static final long RECONNECT_GRACE_MILLIS = 3 * 60 * 1000L;

    private BombRoundManager() {}

    public static BombRoundManager getInstance() {
        if (instance == null) {
            instance = new BombRoundManager();
        }
        return instance;
    }

    /** 已定整场胜负、待间歇结束结算的对局：sessionId → 胜方队伍（0=平局） */
    private final Map<String, Integer> pendingSeriesWinner = new ConcurrentHashMap<>();

    /**
     * C4 引信已点燃（倒计时归零，c4_detonation 引信音播放中，MatchSoundTimings.DETONATION_LEAD_MILLIS
     * 毫秒后真正爆炸）的对局：
     * 引信音期间不可拆除（拆除进度直接取消），任意 endRound（全歼等）即取消起爆，
     * 延迟任务执行时以本表判定是否仍需爆炸。
     */
    private final Set<String> pendingDetonation = ConcurrentHashMap.newKeySet();

    // ==================== 对局入口 ====================

    /**
     * 【作用】爆破对局入口：初始化回合状态并开启第 1 回合（由 MatchManager.startMatch 爆破分支调用，
     *         替代普通图的 prepareMatch 全员初始化）。
     * 【被谁使用】MatchManager#startMatch（地图 gameType=BOMB 时）。仅服务端。
     */
    public void startFirstRound(MatchSession session, MapConfig config) {
        session.setBombMode(true);
        // 攻防初始：红队（队伍1）先当 T；竞技在常规赛第 13 回合、加时第 4 回合转换
        session.setTTeam(1);
        session.setRoundNumber(0);
        session.setInOvertime(false);
        session.resetOvertimeWins();
        pendingSeriesWinner.remove(session.getSessionId());
        pendingDetonation.remove(session.getSessionId());
        // 经济初始化：全员发起始金额（CS 标准 $800，全局可配）
        EconomyManager.getInstance().initSession(session);
        startRound(session, config);
    }

    // ==================== 回合开始 ====================

    /**
     * 【作用】开始一个新回合：回合号/加时推进与攻防转换 → 全员复活/治疗/回默认装备（存活者保留装备）
     *         → 按当前阵营传送出生点 → 随机指派 C4 携带者（阶段③发物品）→ 进入冻结阶段。
     * 【被谁使用】startFirstRound / proceedAfterIntermission（服务端内部）。
     */
    private void startRound(MatchSession session, MapConfig config) {
        MinecraftServer server = Cstmm.getServer();
        if (server == null) return;

        // ---- 回合计数与攻防转换（先推进计数，再按转换节点翻转阵营）----
        session.incrementRoundNumber();
        if (session.isInOvertime()) {
            session.setOvertimeRoundInSet(session.getOvertimeRoundInSet() + 1);
            // 加时赛第 4 回合攻防转换
            if (session.getOvertimeRoundInSet() == 4) {
                session.setTTeam(session.getOppositeTeam(session.getTTeam()));
                broadcastBomb(session, "§6=== 攻防转换！ ===");
            }
        } else if (session.isCompetitive() && session.getRoundNumber() == 13) {
            // 常规赛第 13 回合（上半场 12 回合打完）攻防转换：阵营翻转 + 经济重置（CS 规则）
            session.setTTeam(session.getOppositeTeam(session.getTTeam()));
            EconomyManager.getInstance().resetForPhaseChange(session);
            broadcastBomb(session, "§6=== 第 13 回合：攻防转换！ ===");
        }

        // ---- 回合状态重置（清空死亡名单/死亡位置/C4/拆弹状态）----
        // 上一回合的死亡名单先取出来，用于"阵亡玩家回默认装备、存活者保留装备"的判定
        Set<UUID> previousDead = new HashSet<>(session.getDeadThisRound());
        session.resetRoundState();
        session.setRedKills(0);
        session.setBlueKills(0);

        // 清理上一回合遗留的 C4 实体（按 matchId 过滤，不影响其他对局）
        BombInteractionManager.getInstance().discardBombs(server, session);

        // ---- 全员回合初始化：复活阵亡者 → 治疗 → 装备 → 传送 ----
        ServerWorld world = QueueManager.getWorld();
        if (world == null) return;

        // 清理地上遗留的 C4 物品掉落物（携带者死亡掉落未被拾取的，防跨回合残留捡到旧炸弹）
        for (Entity entity : world.iterateEntities()) {
            if (entity instanceof ItemEntity item && item.getStack().isOf(ModItems.C4)) {
                entity.discard();
            }
        }

        for (UUID uuid : session.getAllPlayers()) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
            if (player == null) continue;

            boolean wasDead = previousDead.contains(uuid);
            if (wasDead && player.isDead()) {
                // 服务端主动复活：走原版 onClientStatus(PERFORM_RESPAWN) 路径（同步触发
                // AFTER_RESPAWN → handleBombRespawn PREPARING 分支仅恢复冒险模式）
                player.networkHandler.onClientStatus(new ClientStatusC2SPacket(ClientStatusC2SPacket.Mode.PERFORM_RESPAWN));
            }
            if (player.isDead()) continue; // 复活失败（异常兜底）：跳过本回合

            // 治疗：所有玩家每回合满血开局（存活者装备保留，死者装备在下方重发）
            player.setHealth(player.getMaxHealth());
            player.extinguish();
            player.clearStatusEffects();

            // 装备：上一回合死亡（或新加入）的玩家清空背包发本图默认装备；存活者保留当前装备
            if (wasDead) {
                player.getInventory().clear();
                player.getInventory().armor.clear();
                player.getInventory().offHand.clear();
                EquipmentManager.getInstance().giveDefaultGear(player, config);
            }

            // 拆弹装备持有（CT 专属，tag = cstmm_defuse_kit，非物品）：
            // 休闲每回合自动打给所有 CT；竞技阵亡清除、存活保留（tag 一直在身上）
            boolean isCT = !session.isTTeam(session.getPlayerTeam(uuid));
            if (isCT) {
                if (!session.isCompetitive()) {
                    // 休闲爆破：每个 CT 不管要不要都自动获得拆弹装备（用户规格）
                    player.removeCommandTag(BombInteractionManager.DEFUSE_KIT_TAG);
                    player.addCommandTag(BombInteractionManager.DEFUSE_KIT_TAG);
                } else if (wasDead) {
                    // 竞技爆破：阵亡 CT 拆弹装备丢失
                    player.removeCommandTag(BombInteractionManager.DEFUSE_KIT_TAG);
                }
                // 竞技存活 CT：tag 保留
            } else {
                // T 方（含换边后的原 CT）：持有必然失效
                player.removeCommandTag(BombInteractionManager.DEFUSE_KIT_TAG);
            }

            // 按当前阵营传送出生点（爆破图约定：redSpawns=T 出生点，blueSpawns=CT 出生点）
            BlockPos spawn = session.isTTeam(session.getPlayerTeam(uuid))
                    ? config.getRandomRedSpawn() : config.getRandomBlueSpawn();
            player.teleport(world, spawn.getX() + 0.5, spawn.getY() + 1, spawn.getZ() + 0.5,
                    player.getYaw(), player.getPitch());
            player.changeGameMode(GameMode.ADVENTURE);
        }

        // ---- C4 携带者：随机一名在线 T，发放 cstmm:c4 物品（手持长按右键安放）----
        List<UUID> onlineT = new ArrayList<>();
        for (UUID uuid : teamMembers(session, session.getTTeam())) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(uuid);
            if (p != null && !p.isDead()) onlineT.add(uuid);
        }
        if (!onlineT.isEmpty()) {
            UUID carrier = onlineT.get(ThreadLocalRandom.current().nextInt(onlineT.size()));
            session.setBombCarrier(carrier);
            // 存活 T 背包可能残留上一回合未安放的 C4：先全员清空再发新（CS 规则：每回合重新指派）
            for (UUID uuid : onlineT) {
                ServerPlayerEntity p = server.getPlayerManager().getPlayer(uuid);
                if (p != null) removeC4Items(p);
            }
            ServerPlayerEntity carrierPlayer = server.getPlayerManager().getPlayer(carrier);
            if (carrierPlayer != null) {
                carrierPlayer.getInventory().offerOrDrop(new ItemStack(ModItems.C4));
                carrierPlayer.sendMessage(Text.literal("§e你是本回合 C4 携带者：进入包点后手持 C4 长按右键安放"), false);
            }
        }

        // ---- 进入冻结阶段 ----
        session.setRemainingSeconds(config.getBombSettings().getRoundTimeSeconds());
        session.setPhase(MatchSession.GamePhase.PREPARING);
        session.setPrepCounter(Math.max(1, config.getPrepareTime()));

        String tName = teamDisplayName(session, session.getTTeam());
        broadcastBomb(session, "§6=== 第 " + session.getRoundNumber() + " 回合 === §e" + tName + " 进攻（T），"
                + teamDisplayName(session, session.getOppositeTeam(session.getTTeam())) + " 防守（CT）");
    }

    /** 清空玩家背包主背包与副手中的 C4 物品（回合开始重新指派前清理，防存活 T 残留多枚）。 */
    private void removeC4Items(ServerPlayerEntity player) {
        for (int i = 0; i < player.getInventory().main.size(); i++) {
            if (player.getInventory().main.get(i).isOf(ModItems.C4)) {
                player.getInventory().main.set(i, ItemStack.EMPTY);
            }
        }
        for (int i = 0; i < player.getInventory().offHand.size(); i++) {
            if (player.getInventory().offHand.get(i).isOf(ModItems.C4)) {
                player.getInventory().offHand.set(i, ItemStack.EMPTY);
            }
        }
    }

    // ==================== 每秒推进 ====================

    /**
     * 【作用】爆破对局每秒推进（MatchManager.tick 爆破分支委托）：
     *         整队离线挂起时不推进；回合间歇倒计时结束后开下一回合或结算整场；
     *         冻结阶段倒计时（施加缓慢/防跳冻结效果）；战斗阶段做回合胜负与计时判定。
     * 【被谁使用】MatchManager#tick（session.isBombMode() 时）。仅服务端。
     */
    public void tick(MatchSession session, MapConfig config) {
        // 整队无人在线的等待重连状态：仅推进重连超时清算（3 分钟无人重连 → 结束对局判负）
        if (session.isWaitingReconnect()) {
            tickReconnectDeadlines(session, config);
            return;
        }

        // 回合间歇：本回合已分胜负，倒计时结束后开下一回合或结算整场
        if (session.getRoundEndCounter() > 0) {
            session.setRoundEndCounter(session.getRoundEndCounter() - 1);
            if (session.getRoundEndCounter() <= 0) {
                proceedAfterIntermission(session, config);
            }
            return;
        }

        switch (session.getPhase()) {
            case PREPARING -> tickPreparing(session, config);
            case FIGHTING -> tickFighting(session, config);
            default -> {}
        }
    }

    /** 冻结阶段每秒：倒计时广播 + 冻结效果续期；归零切入战斗并解冻。 */
    private void tickPreparing(MatchSession session, MapConfig config) {
        MinecraftServer server = Cstmm.getServer();
        int prep = session.getPrepCounter() - 1;
        session.setPrepCounter(prep);

        // 冻结：缓慢 255 + 跳跃抑制（40 tick 续期），购买窗口在阶段②接入
        for (UUID uuid : session.getAllPlayers()) {
            ServerPlayerEntity p = server != null ? server.getPlayerManager().getPlayer(uuid) : null;
            if (p == null || p.isDead()) continue;
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 40, 250, true, false));
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.JUMP_BOOST, 40, 250, true, false));
        }

        if (prep > 0 && prep <= 5) {
            broadcastBomb(session, "§e回合 " + session.getRoundNumber() + " 将在 " + prep + " 秒后开始...");
        } else if (prep <= 0) {
            session.setPhase(MatchSession.GamePhase.FIGHTING);
            clearFreeze(session, server);
            broadcastBomb(session, "§a=== 回合开始！T 方突袭包点，CT 方严防死守 ===");
        }
    }

    /** 解除冻结效果（回合开始时调用）。 */
    private void clearFreeze(MatchSession session, MinecraftServer server) {
        if (server == null) return;
        for (UUID uuid : session.getAllPlayers()) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(uuid);
            if (p == null) continue;
            p.removeStatusEffect(StatusEffects.SLOWNESS);
            p.removeStatusEffect(StatusEffects.JUMP_BOOST);
        }
    }

    /**
     * 战斗阶段每秒：全歼判定（CS 规则）→ 回合/C4 计时 → 边界检查。
     */
    private void tickFighting(MatchSession session, MapConfig config) {
        // ---- 全歼判定（安放炸弹后 T 方被全歼不结束回合，必须拆弹或等待爆炸）----
        if (session.getRoundEndCounter() <= 0) {
            int elimWinner = checkElimination(session);
            if (elimWinner != 0) {
                endRound(session, config, elimWinner, elimWinner == session.getTTeam()
                        ? "CT 方被全歼" : "T 方被全歼");
                return;
            }
            // 炸弹已安放且 CT 全灭：T 立即胜（无人可拆）
            if (session.isBombPlanted()) {
                int ctTeam = session.getOppositeTeam(session.getTTeam());
                if (isTeamWiped(session, ctTeam)) {
                    endRound(session, config, session.getTTeam(), "炸弹已安放且 CT 全灭");
                    return;
                }
            }
        }

        // ---- 计时 ----
        if (session.isBombPlanted()) {
            // C4 计时：归零点燃引信（先播 1.5 秒 c4_detonation 引信音，播完才真正爆炸）
            int bombTimer = session.getBombTimerSeconds() - 1;
            session.setBombTimerSeconds(Math.max(0, bombTimer));
            if (bombTimer <= 0) {
                if (pendingDetonation.add(session.getSessionId())) {
                    beginDetonationSequence(session, config);
                }
                return;
            }
        } else {
            int remaining = session.getRemainingSeconds() - 1;
            session.setRemainingSeconds(Math.max(0, remaining));
            if (remaining <= 0) {
                // 回合限时内未安放 → CT 胜本回合
                endRound(session, config, session.getOppositeTeam(session.getTTeam()), "回合时间耗尽");
                return;
            }
        }

        BoundaryChecker.checkBoundaries(session, config);
    }

    /** 全歼判定（未安放阶段）：任一队全灭即对方胜；返回 0=未分胜负。 */
    private int checkElimination(MatchSession session) {
        int tTeam = session.getTTeam();
        int ctTeam = session.getOppositeTeam(tTeam);
        if (isTeamWiped(session, ctTeam)) return tTeam;
        if (isTeamWiped(session, tTeam)) return ctTeam;
        return 0;
    }

    /** 队伍是否全灭（所有成员都已标记死亡——含离线宽限期玩家）。 */
    private boolean isTeamWiped(MatchSession session, int team) {
        for (UUID uuid : teamMembers(session, team)) {
            if (!session.isDeadThisRound(uuid)) return false;
        }
        // 空队伍（极端情况）视为被歼灭，避免回合卡死
        return true;
    }

    // ==================== 回合结束 ====================

    /**
     * 【作用】结束当前回合：判胜计分 → 广播回合结果 → 进入间歇（5 秒后开下一回合或结算整场）。
     * 【被谁使用】tickFighting（全歼/爆炸/超时）、阶段③（拆除成功）。仅服务端。
     */
    public void endRound(MatchSession session, MapConfig config, int winnerTeam, String reason) {
        if (session.getPhase() == MatchSession.GamePhase.ENDED || session.getRoundEndCounter() > 0) return;

        // 回合经本方法结束（全歼/超时等）→ 取消尚未结算的 C4 起爆（引信音播放中回合已被他途结束）
        pendingDetonation.remove(session.getSessionId());

        // 回合胜负播报音效（占位，仅对局内单播）：
        // T 胜——全歼类立刻播放；C4 爆炸在爆炸音效响起 EXPLOSION_WIN_DELAY_MILLIS 后播放
        // CT 胜——全歼/超时类立刻播放；拆除时待拆除语音播报响起 DEFUSED_WIN_DELAY_MILLIS 后播放（播报本体在 defuseBomb）
        if (winnerTeam == session.getTTeam()) {
            if (reason.contains("C4 爆炸")) {
                cn.woshiikun_1145.mcmod.choco.cstmm.sound.MatchSoundPlayer.scheduleToMatch(
                        session, cn.woshiikun_1145.mcmod.choco.cstmm.sound.ModSounds.T_WIN.value(),
                        1.0f, 1.0f, MatchSoundTimings.EXPLOSION_WIN_DELAY_MILLIS);
            } else {
                cn.woshiikun_1145.mcmod.choco.cstmm.sound.MatchSoundPlayer.playToMatch(
                        session, cn.woshiikun_1145.mcmod.choco.cstmm.sound.ModSounds.T_WIN.value(), 1.0f, 1.0f);
            }
        } else if (winnerTeam != 0) {
            if (reason.contains("拆除")) {
                cn.woshiikun_1145.mcmod.choco.cstmm.sound.MatchSoundPlayer.scheduleToMatch(
                        session, cn.woshiikun_1145.mcmod.choco.cstmm.sound.ModSounds.CT_WIN.value(),
                        1.0f, 1.0f, MatchSoundTimings.DEFUSED_WIN_DELAY_MILLIS);
            } else {
                cn.woshiikun_1145.mcmod.choco.cstmm.sound.MatchSoundPlayer.playToMatch(
                        session, cn.woshiikun_1145.mcmod.choco.cstmm.sound.ModSounds.CT_WIN.value(), 1.0f, 1.0f);
            }
        }

        // 立即进入回合间歇（防重复结算），间歇结束后由 proceedAfterIntermission 开下一回合/结算整场
        // 回合间歇（每图可配 roundIntermissionSeconds，默认 5 秒）
        int intermission = config.getBombSettings().getRoundIntermissionSeconds();
        session.setRoundEndCounter(intermission);

        session.addRoundWin(winnerTeam);
        if (session.isInOvertime()) {
            session.addOtWin(winnerTeam);
        }

        String tName = teamDisplayName(session, session.getTTeam());
        String ctName = teamDisplayName(session, session.getOppositeTeam(session.getTTeam()));
        String winnerName = session.isTTeam(winnerTeam) ? "T 方（" + tName + "）" : "CT 方（" + ctName + "）";
        broadcastBomb(session, "§6=== " + winnerName + "赢得第 " + session.getRoundNumber()
                + " 回合（" + reason + "）=== §7比分 §e" + teamRoundWinsOf(session, session.getTTeam())
                + " : " + teamRoundWinsOf(session, session.getOppositeTeam(session.getTTeam())) + "§7 CT"
                + " §8| §7" + intermission + " 秒后开始下一回合");

        // 回合经济结算：胜方胜利金（爆炸/拆除 3500 档，全歼/超时 3250 档）、败方连败金、
        // T 方存活未下包 0 败金（CS 规则）；休闲模式奖励按倍率缩放
        EconomyManager.WinKind winKind = reason.contains("C4 爆炸") || reason.contains("拆除")
                ? EconomyManager.WinKind.OBJECTIVE : EconomyManager.WinKind.ELIMINATION;
        EconomyManager.getInstance().settleRound(session, winnerTeam, winKind);
        // 订阅制商店推送：回合结算改钱（胜败金/连败金），全员余额刷新
        cn.woshiikun_1145.mcmod.choco.cstmm.network.NetworkHandler.sendShopDataToTeam(session);

        decideSeriesState(session, config);
    }

    /**
     * 【作用】整场胜负判定（回合结束后调用）：达标记分即记入 pendingSeriesWinner（间歇结束结算）；
     *         竞技 12:12 进加时；加时先到 4 胜；加时 3:3 发起继续投票。
     * 【被谁使用】endRound（服务端内部）。
     */
    private void decideSeriesState(MatchSession session, MapConfig config) {
        int t1 = session.getTeam1RoundWins();
        int t2 = session.getTeam2RoundWins();

        if (!session.isInOvertime()) {
            if (!session.isCompetitive()) {
                // 休闲爆破：先到 8 分获胜
                if (t1 >= 8) { markSeriesWinner(session, 1); return; }
                if (t2 >= 8) { markSeriesWinner(session, 2); return; }
            } else {
                // 竞技 MR12：先到 13 胜；24 回合打完 12:12 进加时
                if (t1 >= 13) { markSeriesWinner(session, 1); return; }
                if (t2 >= 13) { markSeriesWinner(session, 2); return; }
                if (session.getRoundNumber() >= 24) {
                    if (t1 > t2) { markSeriesWinner(session, 1); return; }
                    if (t2 > t1) { markSeriesWinner(session, 2); return; }
                    // 12:12 → 加时赛（BO6：6 回合先到 4 胜，3:3 投票决定继续或平局）
                    session.setInOvertime(true);
                    session.resetOvertimeWins();
                    // 进入加时：经济重置回起始金额（CS 规则）
                    EconomyManager.getInstance().resetForPhaseChange(session);
                    broadcastBomb(session, "§6=== 12:12 平！进入加时赛（先到 4 分获胜）===");
                }
            }
            session.setRoundPending(true);
        } else {
            // 加时赛：先到 4 胜
            int ot1 = session.getOtTeam1Wins();
            int ot2 = session.getOtTeam2Wins();
            if (ot1 >= 4) { markSeriesWinner(session, 1); return; }
            if (ot2 >= 4) { markSeriesWinner(session, 2); return; }
            if (session.getOvertimeRoundInSet() >= 6) {
                // 3:3 → 全队投票：继续下一场加时或平局结束
                VoteManager.getInstance().startBombContinueVote(session);
                session.setRoundPending(false);
                return;
            }
            session.setRoundPending(true);
        }
    }

    /** 记录"整场已分胜负"（间歇结束结算整场）。 */
    private void markSeriesWinner(MatchSession session, int winnerTeam) {
        pendingSeriesWinner.put(session.getSessionId(), winnerTeam);
        session.setRoundPending(false);
    }

    /** 回合间歇结束：结算整场或开下一回合（加时继续投票进行中时等待投票结果）。 */
    private void proceedAfterIntermission(MatchSession session, MapConfig config) {
        // 先清算断线超时玩家（规格：回合结束后判定 3 分钟超时；减员可能清空队伍）
        tickReconnectDeadlines(session, config);
        if (session.getPhase() == MatchSession.GamePhase.ENDED) return;

        Integer winner = pendingSeriesWinner.remove(session.getSessionId());
        if (winner != null) {
            MapConfig cfg = ConfigManager.getInstance().getMap(session.getMapName());
            String mapName = cfg != null ? cfg.getDisplayName() : session.getMapName();
            if (winner == 0) {
                MatchManager.getInstance().endMatch(session, "§e" + mapName + " 比赛结束：双方平局！", 0);
            } else {
                MatchManager.getInstance().endMatch(session,
                        "§6" + mapName + " 比赛结束：" + teamDisplayName(session, winner) + " 获得胜利！", winner);
            }
            return;
        }

        // 加时继续投票进行中：等待投票结果（1 秒后重试）
        if (session.isInOvertime() && !session.isRoundPending()
                && VoteManager.getInstance().hasActiveVote(session.getSessionId())) {
            session.setRoundEndCounter(1);
            return;
        }
        // 等待重连挂起：不推进（下一 tick 直接 return）
        if (session.isWaitingReconnect()) return;

        if (session.isRoundPending()) {
            startRound(session, config);
        }
    }

    /** 加时继续投票结果回调（VoteManager BOMB_OT 分支委托）：通过 → 新一场加时；拒绝 → 平局结束。 */
    public void onOvertimeVoteResult(MatchSession session, boolean passed) {
        if (session.getPhase() == MatchSession.GamePhase.ENDED) return;
        if (passed) {
            session.resetOvertimeWins();
            session.setRoundPending(true);
            // 下一场加时：经济再次重置回起始金额（CS 规则）
            EconomyManager.getInstance().resetForPhaseChange(session);
            broadcastBomb(session, "§a投票通过！开始下一场加时赛（再次先到 4 分获胜）");
            Cstmm.LOGGER.info("[CSTMM - BombRoundManager] OT continue vote passed for {}", session.getSessionId());
        } else {
            // 投票决定平局：先播平局音效，比赛以平局结束
            markSeriesWinner(session, 0);
            cn.woshiikun_1145.mcmod.choco.cstmm.sound.MatchSoundPlayer.playToMatch(
                    session, cn.woshiikun_1145.mcmod.choco.cstmm.sound.ModSounds.ROUND_DRAW.value(), 1.0f, 1.0f);
            broadcastBomb(session, "§e投票未通过，比赛以平局结束");
            Cstmm.LOGGER.info("[CSTMM - BombRoundManager] OT continue vote rejected for {}", session.getSessionId());
        }
        if (session.getRoundEndCounter() <= 0) {
            session.setRoundEndCounter(1);
        }
    }

    /**
     * 【作用】查询对局 C4 引信是否已点燃（引信音播放中）：引信期间拆除请求一律拒绝，
     *         进行中的拆除已被取消。
     * 【被谁使用】BombInteractionManager#defuseRejectReason（拆除 start/逐 tick 校验）。仅服务端。
     */
    public boolean isDetonationPending(String sessionId) {
        return pendingDetonation.contains(sessionId);
    }

    /**
     * 【作用】C4 引信点燃（倒计时归零）：向对局全员单播 1.5 秒 c4_detonation 引信音
     *         （在安放位置，可闻半径同爆炸声），引信音播完后才真正爆炸结算；
     *         引信期间不可拆除——正在进行的拆除立即取消，新拆除请求一律拒绝。
     * 【被谁使用】tickFighting（bombTimer 归零，首次触发）。仅服务端。
     */
    private void beginDetonationSequence(MatchSession session, MapConfig config) {
        // 引信点燃即取消正在进行的拆除（引信期间不可抢拆）
        UUID defusing = session.getDefusingPlayer();
        if (defusing != null) {
            session.setDefusingPlayer(null);
            session.setDefuseProgress(0);
            MinecraftServer server = Cstmm.getServer();
            ServerPlayerEntity defuser = server != null ? server.getPlayerManager().getPlayer(defusing) : null;
            if (defuser != null) {
                defuser.sendMessage(Text.literal("§c你已经没有任何机会了！"), true);
            }
        }

        ServerWorld world = QueueManager.getWorld();
        int[] pos = session.getPlantedPos();
        if (world != null && pos != null) {
            // 引信音单播包（对局隔离，与爆炸声同口径：安放位置 + 爆炸可闻半径）
            net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket fusePacket =
                    new net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket(
                            net.minecraft.registry.entry.RegistryEntry.of(
                                    cn.woshiikun_1145.mcmod.choco.cstmm.sound.ModSounds.C4_DETONATION.value()),
                            net.minecraft.sound.SoundCategory.BLOCKS,
                            pos[0] + 0.5, pos[1] + 0.5, pos[2] + 0.5,
                            config.getBombSettings().getExplosionSoundRange() / 16.0f, 1.0f,
                            world.getRandom().nextLong());
            MinecraftServer server = Cstmm.getServer();
            for (UUID uuid : session.getAllPlayers()) {
                ServerPlayerEntity player = server != null ? server.getPlayerManager().getPlayer(uuid) : null;
                if (player != null) {
                    player.networkHandler.sendPacket(fusePacket);
                }
            }
        }
        MatchScheduler.scheduleDelayed(MatchSoundTimings.DETONATION_LEAD_MILLIS, () -> {
            // 引信音播完：仅当本次起爆未被取消（回合已结束/对局已结束）时才真正爆炸
            if (!pendingDetonation.remove(session.getSessionId())) return;
            MatchSession current = MatchManager.getInstance().getSession(session.getSessionId());
            if (current == null || current.getPhase() == MatchSession.GamePhase.ENDED) return;
            MapConfig cfg = ConfigManager.getInstance().getMap(current.getMapName());
            if (cfg == null) return;
            detonateBomb(current, cfg);
            endRound(current, cfg, current.getTTeam(), "C4 爆炸");
        });
        Cstmm.LOGGER.info("[CSTMM - BombRoundManager] C4 fuse lit at match {} site {} ({}ms to detonation)",
                session.getSessionId(), session.getPlantedSite(), MatchSoundTimings.DETONATION_LEAD_MILLIS);
    }

    /** C4 到时爆炸：C4DamageCalculator 按文档模型结算伤害（多采样点/开口穿透/背对减伤）+ 无破坏爆炸视觉。 */
    private void detonateBomb(MatchSession session, MapConfig config) {
        // 爆炸即解除安放状态（间歇期间停止 HUD 红色倒计时与哔声）
        session.setBombPlanted(false);
        // 伤害结算与爆炸视觉/音效（阶段④）；内部广播爆炸提示
        ServerWorld world = QueueManager.getWorld();
        if (world != null) {
            cn.woshiikun_1145.mcmod.choco.cstmm.util.C4DamageCalculator
                    .detonate(world, session, config);
        }
        // 清理已安放的 C4 实体
        MinecraftServer server = Cstmm.getServer();
        if (server != null) {
            BombInteractionManager.getInstance().discardBombs(server, session);
        }
        Cstmm.LOGGER.info("[CSTMM - BombRoundManager] C4 detonated at match {} site {}",
                session.getSessionId(), session.getPlantedSite());
    }

    // ==================== 死亡 / 旁观者 ====================

    /**
     * 【作用】爆破模式玩家死亡处理（EventListener AFTER_DEATH 委托）：标记本回合死亡并记录死亡位置，
     *         随后立即做全歼判定（防止下一秒才结算的空窗）。
     * 【被谁使用】EventListener 的 AFTER_DEATH（对局内玩家死亡）。仅服务端。
     */
    public void onPlayerDeath(MatchSession session, ServerPlayerEntity killed) {
        if (session.getPhase() == MatchSession.GamePhase.ENDED || session.getRoundEndCounter() > 0) return;
        Vec3d pos = killed.getPos();
        session.markDeadThisRound(killed.getUuid(), pos.x, pos.y, pos.z);
        // 拆弹装备随死亡丢失（tag 清除；HUD 拆弹器标记随 roster kit 字段消失）
        killed.removeCommandTag(BombInteractionManager.DEFUSE_KIT_TAG);
        // 拆弹中的玩家死亡 → 拆弹中断
        if (killed.getUuid().equals(session.getDefusingPlayer())) {
            session.setDefusingPlayer(null);
            session.setDefuseProgress(0);
        }
        // 安放中的玩家死亡 → 安放中断
        if (killed.getUuid().equals(session.getPlantingPlayer())) {
            session.setPlantingPlayer(null);
            session.setPlantProgress(0);
        }
        // C4 携带者死亡：背包 C4 按原版死亡掉落，其他 T 可拾取（安放只认手持，不依赖携带者标记）
        if (killed.getUuid().equals(session.getBombCarrier())) {
            session.setBombCarrier(null);
            broadcastBomb(session, "§eC4 已掉落！T 方捡起后即可安放");
        }

        MapConfig config = ConfigManager.getInstance().getMap(session.getMapName());
        if (config == null) return;
        int elimWinner = checkElimination(session);
        if (elimWinner != 0) {
            endRound(session, config, elimWinner, elimWinner == session.getTTeam()
                    ? "CT 方被全歼" : "T 方被全歼");
            return;
        }
        if (session.isBombPlanted() && isTeamWiped(session, session.getOppositeTeam(session.getTTeam()))) {
            endRound(session, config, session.getTTeam(), "炸弹已安放且 CT 全灭");
        }
    }

    /**
     * 【作用】爆破模式重生事件分支（MatchManager.handleRespawn 委托）：
     *         回合战斗中/间歇时重生 = 变旁观者（SPECTATOR，传送回死亡位置附近）；
     *         冻结阶段重生（回合重置由 startRound 主动复活触发）= 仅恢复冒险模式（传送/装备由 startRound 处理）。
     * 【被谁使用】MatchManager#handleRespawn（session.isBombMode() 时）。仅服务端。
     */
    public void handleRespawn(ServerPlayerEntity player, MatchSession session) {
        if (session.getPhase() == MatchSession.GamePhase.PREPARING) {
            // 回合重置流程中的主动复活：只恢复冒险模式，出生点传送与装备由 startRound 统一处理
            player.changeGameMode(GameMode.ADVENTURE);
            return;
        }
        // 战斗阶段/回合间歇：死亡玩家变旁观者（自由视角），锚定死亡位置
        player.changeGameMode(GameMode.SPECTATOR);
        int[] deathPos = session.getDeathPosition(player.getUuid());
        ServerWorld world = QueueManager.getWorld();
        if (world != null) {
            if (deathPos != null) {
                player.teleport(world, deathPos[0] + 0.5, deathPos[1] + 1.0, deathPos[2] + 0.5,
                        player.getYaw(), player.getPitch());
            } else {
                // 无死亡位置记录（回合开始前即死亡等）：传送到本队出生点
                MapConfig config = ConfigManager.getInstance().getMap(session.getMapName());
                BlockPos spawn = config != null && session.isTTeam(session.getPlayerTeam(player.getUuid()))
                        ? config.getRandomRedSpawn() : (config != null ? config.getRandomBlueSpawn() : null);
                if (spawn != null) {
                    player.teleport(world, spawn.getX() + 0.5, spawn.getY() + 1, spawn.getZ() + 0.5,
                            player.getYaw(), player.getPitch());
                }
            }
        }
    }

    // ==================== 断线宽限与重连 ====================

    /**
     * 【作用】爆破模式玩家断线（MatchManager.onPlayerDisconnect 爆破分支委托）：
     *         记录 3 分钟重连宽限截止并按死亡处理（防回合卡死）；
     *         整队无人在线时挂起对局等待重连（有人重连即恢复；3 分钟无人重连 → 当前回合判负该队）。
     * 【被谁使用】MatchManager#onPlayerDisconnect（session.isBombMode() 时）。仅服务端。
     */
    public void onDisconnect(MatchSession session, UUID playerUuid) {
        session.getReconnectDeadlines().put(playerUuid, System.currentTimeMillis() + RECONNECT_GRACE_MILLIS);
        // 离线即按死亡处理（宽限期内参与全歼判定，防回合被无限卡住）
        session.markDeadThisRound(playerUuid, 0, -64, 0);

        boolean teamStillOnline = false;
        int team = session.getPlayerTeam(playerUuid);
        MinecraftServer server = Cstmm.getServer();
        if (server != null) {
            for (UUID member : teamMembers(session, team)) {
                if (member.equals(playerUuid)) continue;
                if (server.getPlayerManager().getPlayer(member) != null) {
                    teamStillOnline = true;
                    break;
                }
            }
        }

        if (!teamStillOnline) {
            // 整队无人在线：挂起等待重连（tick 冻结推进；重连或 3 分钟超时后恢复/结算）
            session.setWaitingReconnect(true);
            broadcastBomb(session, "§e" + playerName(session, playerUuid) + " 已断线，"
                    + teamDisplayName(session, team) + " 全队离线，等待重连（3 分钟）...");
            Cstmm.LOGGER.info("[CSTMM - BombRoundManager] Team {} fully offline in match {}, waiting for reconnect",
                    team, session.getSessionId());
        } else {
            broadcastBomb(session, "§e" + playerName(session, playerUuid) + " 已断线（3 分钟内可重连，本回合按死亡处理）");
        }

        // 死亡即时全歼判定（挂起状态除外——挂起时回合本就静止）
        if (!session.isWaitingReconnect() && session.getPhase() == MatchSession.GamePhase.FIGHTING
                && session.getRoundEndCounter() <= 0) {
            MapConfig config = ConfigManager.getInstance().getMap(session.getMapName());
            if (config != null) {
                int elimWinner = checkElimination(session);
                if (elimWinner != 0) {
                    endRound(session, config, elimWinner, elimWinner == session.getTTeam()
                            ? "CT 方被全歼" : "T 方被全歼");
                }
            }
        }
    }

    /**
     * 【作用】玩家重连回到爆破对局（EventListener JOIN 爆破分支委托）：
     *         清除宽限记录并解除整队挂起；回合已开打 → 本回合按死亡处理（旁观者）；
     *         冻结阶段/回合间歇 → 立即回阵营出生点继续（存活者身份保留）。
     * 【被谁使用】EventListener 的 JOIN（对局内重连）。仅服务端。
     */
    public void onRejoin(ServerPlayerEntity player, MatchSession session) {
        UUID uuid = player.getUuid();
        session.getReconnectDeadlines().remove(uuid);
        boolean wasWaiting = session.isWaitingReconnect();
        session.setWaitingReconnect(false);

        MapConfig config = ConfigManager.getInstance().getMap(session.getMapName());
        ServerWorld world = QueueManager.getWorld();
        if (world == null) return;

        if (session.getPhase() == MatchSession.GamePhase.FIGHTING && session.getRoundEndCounter() <= 0) {
            // 当前回合已开始（冻结结束）：本回合按死亡处理，变旁观者
            session.markDeadThisRound(uuid, 0, -64, 0);
            player.changeGameMode(GameMode.SPECTATOR);
            int[] deathPos = session.getDeathPosition(uuid);
            if (deathPos != null) {
                player.teleport(world, deathPos[0] + 0.5, deathPos[1] + 1.0, deathPos[2] + 0.5,
                        player.getYaw(), player.getPitch());
            }
            player.sendMessage(Text.literal("§e当前回合已在进行中，你在下一回合复活（本回合为旁观者）"), false);
        } else {
            // 冻结阶段或回合间歇：立即以存活身份回归（装备沿用断线前的持久化背包）
            session.getDeadThisRound().remove(uuid);
            player.changeGameMode(GameMode.ADVENTURE);
            if (config != null) {
                BlockPos spawn = session.isTTeam(session.getPlayerTeam(uuid))
                        ? config.getRandomRedSpawn() : config.getRandomBlueSpawn();
                player.teleport(world, spawn.getX() + 0.5, spawn.getY() + 1, spawn.getZ() + 0.5,
                        player.getYaw(), player.getPitch());
            }
            player.setHealth(player.getMaxHealth());
            player.sendMessage(Text.literal("§a你已重新加入对局！"), false);
        }

        if (wasWaiting) {
            broadcastBomb(session, "§a" + playerName(session, uuid) + " 已重连，对局继续！");
        }
    }

    /**
     * 【作用】回合结束（间歇）时清算断线超时玩家：超过 3 分钟未重连 → 移出对局（队伍减员继续）；
     *         等待重连挂起状态超时 → 结束对局并判当前回合负给该队。
     * 【被谁使用】proceedAfterIntermission 前与 tick 挂起状态检查（服务端内部；阶段①先挂在
     *           proceedAfterIntermission 与 tick 入口）。
     */
    public void tickReconnectDeadlines(MatchSession session, MapConfig config) {
        if (session.getReconnectDeadlines().isEmpty()) return;
        long now = System.currentTimeMillis();
        List<UUID> timedOut = new ArrayList<>();
        for (Map.Entry<UUID, Long> e : session.getReconnectDeadlines().entrySet()) {
            if (now >= e.getValue()) timedOut.add(e.getKey());
        }
        if (timedOut.isEmpty()) return;

        for (UUID uuid : timedOut) {
            session.getReconnectDeadlines().remove(uuid);
        }

        if (session.isWaitingReconnect()) {
            // 整队挂起超时：结束对局，判当前回合负给该队（取第一个超时者的队伍）
            int team = session.getPlayerTeam(timedOut.get(0));
            session.setWaitingReconnect(false);
            MatchManager.getInstance().endMatch(session,
                    "§c" + teamDisplayName(session, team) + " 超时未重连，判负！", session.getOppositeTeam(team));
            return;
        }

        // 队伍还有其他人在线：超时玩家移出对局（减员继续）
        for (UUID uuid : timedOut) {
            session.getRedPlayers().remove(uuid);
            session.getBluePlayers().remove(uuid);
            MatchManager.getInstance().removePlayerFromSession(uuid);
            broadcastBomb(session, "§c" + playerName(session, uuid) + " 超时未重连，已移出对局");
            Cstmm.LOGGER.info("[CSTMM - BombRoundManager] Player {} timed out and removed from match {}",
                    uuid, session.getSessionId());
            if (session.getRedPlayers().isEmpty() || session.getBluePlayers().isEmpty()) {
                int winner = session.getRedPlayers().isEmpty()
                        ? session.getOppositeTeam(1) : session.getOppositeTeam(2);
                MatchManager.getInstance().endMatch(session, "§c一方队伍已无人，比赛结束！", winner);
                return;
            }
            // 队伍被减员清空时立即做全歼判定（当前回合该队已无人可打）
            if (session.getPhase() == MatchSession.GamePhase.FIGHTING && session.getRoundEndCounter() <= 0) {
                int elimWinner = checkElimination(session);
                if (elimWinner != 0) {
                    endRound(session, config, elimWinner, elimWinner == session.getTTeam()
                            ? "CT 方被全歼" : "T 方被全歼");
                    return;
                }
            }
        }
    }

    // ==================== 工具方法 ====================

    /** 取某队伍的全部成员（1=红队 2=蓝队）。 */
    public Set<UUID> teamMembers(MatchSession session, int team) {
        return team == 1 ? session.getRedPlayers() : session.getBluePlayers();
    }

    /** 队伍回合胜场（按队伍编号）。 */
    public int teamRoundWinsOf(MatchSession session, int team) {
        return team == 1 ? session.getTeam1RoundWins() : session.getTeam2RoundWins();
    }

    /** 队伍显示名（消息用）：1=红队 2=蓝队。 */
    public String teamDisplayName(MatchSession session, int team) {
        return team == 1 ? "红队" : "蓝队";
    }

    /** 玩家显示名（离线回退名字缓存，再兜底 UUID 前 8 位）。 */
    public String playerName(MatchSession session, UUID uuid) {
        ServerPlayerEntity p = null;
        MinecraftServer server = Cstmm.getServer();
        if (server != null) p = server.getPlayerManager().getPlayer(uuid);
        if (p != null) return p.getName().getString();
        String cached = session.getPlayerName(uuid);
        return cached != null ? cached : uuid.toString().substring(0, 8);
    }

    /** 向对局全员广播消息（走 MatchStatusPayload，与现有消息通道一致）。 */
    private void broadcastBomb(MatchSession session, String message) {
        MinecraftServer server = Cstmm.getServer();
        if (server == null) return;
        MatchStatusPayload payload = new MatchStatusPayload(
                MatchStatusPayload.StatusType.MATCH_STARTING, message,
                session.getRedKills(), session.getBlueKills());
        for (UUID uuid : session.getAllPlayers()) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
            if (player != null) {
                NetworkHandler.sendMatchStatus(player, payload);
            }
        }
    }

    /**
     * 【作用】对局结束清理（MatchManager.endMatch 委托）：清除挂起的整场结算标记，
     *         并清掉全体对局玩家身上的拆弹装备 tag（防跨局残留——竞技存活 CT 的 tag
     *         只有回合重置/死亡才清，对局结束时必须统一回收）。
     */
    public void onMatchEnd(MatchSession session) {
        pendingSeriesWinner.remove(session.getSessionId());
        pendingDetonation.remove(session.getSessionId());
        MinecraftServer server = Cstmm.getServer();
        if (server == null) return;
        for (UUID uuid : session.getAllPlayers()) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
            if (player != null) {
                player.removeCommandTag(BombInteractionManager.DEFUSE_KIT_TAG);
            }
        }
    }
}
