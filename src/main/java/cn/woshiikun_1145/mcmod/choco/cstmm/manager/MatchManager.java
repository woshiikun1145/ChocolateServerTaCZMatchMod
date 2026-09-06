package cn.woshiikun_1145.mcmod.choco.cstmm.manager;

import cn.woshiikun_1145.mcmod.choco.cstmm.Cstmm;
import cn.woshiikun_1145.mcmod.choco.cstmm.api.MatchApi;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.config.MapConfig;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.MatchSession;
import cn.woshiikun_1145.mcmod.choco.cstmm.network.NetworkHandler;
import cn.woshiikun_1145.mcmod.choco.cstmm.network.payload.HudDataPayload;
import cn.woshiikun_1145.mcmod.choco.cstmm.network.payload.MatchStatusPayload;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class MatchManager implements MatchApi {
    private static MatchManager instance;

    /** 已警告过非法/缺失的维度 ID，避免重复刷 warn 日志 */
    private static final Set<String> warnedDimensions = ConcurrentHashMap.newKeySet();

    private final Map<String, MatchSession> activeSessions;
    private final Map<UUID, String> playerSessionMap;
    private final Map<UUID, GameMode> playerOriginalGameMode;
    private final Map<String, Long> mapEndTime;

    private MatchManager() {
        this.activeSessions = new ConcurrentHashMap<>();
        this.playerSessionMap = new ConcurrentHashMap<>();
        this.playerOriginalGameMode = new ConcurrentHashMap<>();
        this.mapEndTime = new ConcurrentHashMap<>();
    }

    public static MatchManager getInstance() {
        if (instance == null) {
            instance = new MatchManager();
        }
        return instance;
    }

    // ==================== API 实现 ====================

    @Override
    public MatchStatus getCurrentMatchStatus(UUID playerUuid) {
        MatchSession session = getPlayerSession(playerUuid);
        if (session == null) {
            return new MatchStatus("", 0, 0, 0, false, false);
        }
        return new MatchStatus(
                session.getMapName(),
                session.getRemainingSeconds(),
                session.getRedKills(),
                session.getBlueKills(),
                session.getPhase() == MatchSession.GamePhase.PREPARING,
                session.getPhase() == MatchSession.GamePhase.FIGHTING
        );
    }

    @Override
    public boolean isInGame(UUID playerUuid) {
        return playerSessionMap.containsKey(playerUuid);
    }

    @Override
    public KillsData getKillsData(UUID playerUuid) {
        MatchSession session = getPlayerSession(playerUuid);
        if (session == null) {
            return new KillsData(0, 0, 0, 0);
        }
        int playerKills = session.getPlayerTeam(playerUuid) == 1 ?
                session.getRedKills() : session.getBlueKills();
        int playerDeaths = session.getPlayerTeam(playerUuid) == 1 ?
                session.getBlueKills() : session.getRedKills();
        return new KillsData(
                session.getRedKills(),
                session.getBlueKills(),
                playerKills,
                playerDeaths
        );
    }

    // ==================== 玩家映射操作 ====================

    public void addPlayerToSession(UUID playerUuid, String sessionId) {
        playerSessionMap.put(playerUuid, sessionId);
    }

    public void removePlayerFromSession(UUID playerUuid) {
        playerSessionMap.remove(playerUuid);
    }

    public Map<UUID, String> getPlayerSessionMapView() {
        return Collections.unmodifiableMap(playerSessionMap);
    }

    // ==================== 对局生命周期 ====================

    /** @return 开局是否成功（失败时调用方应保留玩家在队列中） */
    public boolean startMatch(String mapId, List<ServerPlayerEntity> redPlayers, List<ServerPlayerEntity> bluePlayers) {
        // 兼容旧签名（命令/API 调用方）：固定按竞技模式开局
        return startMatch(mapId, true, redPlayers, bluePlayers);
    }

    /**
     * 按指定模式开局：模式由玩家加入队列时选择，随队列传递（不再取自地图配置）。
     * @param competitive true=竞技模式（存/清背包、发默认装备、可用商店、允许友伤），false=休闲
     * @return 开局是否成功（失败时调用方应保留玩家在队列中）
     */
    public boolean startMatch(String mapId, boolean competitive, List<ServerPlayerEntity> redPlayers, List<ServerPlayerEntity> bluePlayers) {
        MapConfig config = ConfigManager.getInstance().getMap(mapId);
        if (config == null || !config.isEnabled()) {
            Cstmm.LOGGER.warn("[CSTMM - MatchManager] Map {} is not enabled or not found", mapId);
            return false;
        }

        Long endTime = mapEndTime.get(mapId);
        if (endTime != null) {
            long cooldown = config.getCooldownSeconds() * 1000L;
            if (System.currentTimeMillis() - endTime < cooldown) {
                Cstmm.LOGGER.debug("[CSTMM - MatchManager] Map {} is in cooldown", mapId);
                return false;
            }
        }

        for (MatchSession session : activeSessions.values()) {
            if (session.getMapName().equals(mapId) && session.getPhase() != MatchSession.GamePhase.ENDED) {
                Cstmm.LOGGER.warn("[CSTMM - MatchManager] Map {} already has an active session", mapId);
                return false;
            }
        }

        for (ServerPlayerEntity p : redPlayers) {
            if (playerSessionMap.containsKey(p.getUuid())) {
                Cstmm.LOGGER.warn("[CSTMM - MatchManager] Player {} is already in a match", p.getName());
                return false;
            }
        }
        for (ServerPlayerEntity p : bluePlayers) {
            if (playerSessionMap.containsKey(p.getUuid())) {
                Cstmm.LOGGER.warn("[CSTMM - MatchManager] Player {} is already in a match", p.getName());
                return false;
            }
        }

        MatchSession session = new MatchSession(mapId);
        // 模式来自匹配队列选择
        session.setCompetitive(competitive);

        for (ServerPlayerEntity player : redPlayers) {
            session.getRedPlayers().add(player.getUuid());
            playerSessionMap.put(player.getUuid(), session.getSessionId());
            OriginManager.saveOrigin(player);
            playerOriginalGameMode.put(player.getUuid(), player.interactionManager.getGameMode());
            // 仅竞技模式保存背包（休闲模式不动玩家背包）
            if (competitive) {
                InventoryManager.getInstance().setCompetitive(player.getUuid(), true);
                InventoryManager.getInstance().saveInventory(player);
            }
        }
        for (ServerPlayerEntity player : bluePlayers) {
            session.getBluePlayers().add(player.getUuid());
            playerSessionMap.put(player.getUuid(), session.getSessionId());
            OriginManager.saveOrigin(player);
            playerOriginalGameMode.put(player.getUuid(), player.interactionManager.getGameMode());
            if (competitive) {
                InventoryManager.getInstance().setCompetitive(player.getUuid(), true);
                InventoryManager.getInstance().saveInventory(player);
            }
        }

        if (config.getWinCondition() == MapConfig.WinCondition.TIMER) {
            session.setRemainingSeconds(config.getMaxDuration());
        }

        session.setPhase(MatchSession.GamePhase.PREPARING);

        int prepareTime = config.getPrepareTime();
        // 钳制非法配置：prepareTime <= 0 会让 tickPreparing 永远无法进入 FIGHTING，对局卡死
        if (prepareTime < 1) prepareTime = 1;
        session.setPrepCounter(prepareTime);

        activeSessions.put(session.getSessionId(), session);

        broadcastMatchStatus(session, MatchStatusPayload.StatusType.MATCH_STARTING,
                "§6=== " + config.getDisplayName() + " 即将开始！准备倒计时 " + prepareTime + " 秒 ===");

        prepareMatch(session);

        Cstmm.LOGGER.info("[CSTMM - MatchManager] Started match on {} with {} players", mapId, session.getTotalPlayers());
        return true;
    }

    /** 地图是否处于对局结束后的冷却期（冷却未过期的地图不可用于快速匹配） */
    public boolean isMapInCooldown(String mapId) {
        Long endTime = mapEndTime.get(mapId);
        if (endTime == null) return false;
        MapConfig config = ConfigManager.getInstance().getMap(mapId);
        long cooldown = config != null ? config.getCooldownSeconds() * 1000L : 0L;
        return System.currentTimeMillis() - endTime < cooldown;
    }

    /** 按地图配置解析对局维度；解析失败或世界不存在时回退主世界（每个非法维度仅 warn 一次） */
    private ServerWorld resolveMatchWorld(MinecraftServer server, MapConfig config) {
        String dimensionId = config.getDimension();
        try {
            ServerWorld world = server.getWorld(RegistryKey.of(RegistryKeys.WORLD, Identifier.of(dimensionId)));
            if (world != null) {
                return world;
            }
        } catch (Exception e) {
            // 无效 ID，走回退并警告
        }
        if (warnedDimensions.add(dimensionId)) {
            Cstmm.LOGGER.warn("[CSTMM - MatchManager] Dimension '{}' of map {} is invalid or missing, falling back to overworld",
                    dimensionId, config.getId());
        }
        return server.getWorld(ServerWorld.OVERWORLD);
    }

    /**
     * 公共方法：为单个玩家设置对局初始化（传送、清包、装备、游戏模式）
     * 供正常开局和快速补位复用
     */
    public void setupPlayer(MatchSession session, ServerPlayerEntity player) {
        MapConfig config = ConfigManager.getInstance().getMap(session.getMapName());
        if (config == null) return;

        int team = session.getPlayerTeam(player.getUuid());
        BlockPos spawnPos;
        if (team == 1) {
            spawnPos = config.getRandomRedSpawn();
        } else if (team == 2) {
            spawnPos = config.getRandomBlueSpawn();
        } else {
            // 未分配队伍，忽略
            return;
        }

        MinecraftServer server = Cstmm.getServer();
        ServerWorld world = server != null ? resolveMatchWorld(server, config) : player.getServerWorld();
        // 传送
        player.teleport(world, spawnPos.getX() + 0.5, spawnPos.getY() + 1, spawnPos.getZ() + 0.5,
                player.getYaw(), player.getPitch());

        // 设置游戏模式（冒险模式，防止破坏）
        player.changeGameMode(GameMode.ADVENTURE);

        // 清空背包并发放竞技装备
        if (session.isCompetitive()) {
            EquipmentManager.getInstance().setupCompetitiveGear(player);
        }

        Cstmm.LOGGER.debug("[CSTMM - MatchManager] Setup player {} for match {}", player.getName(), session.getSessionId());
    }

    /**
     * 公共方法：登记补位玩家并完成对局初始化。
     * 与 startMatch 中对首发玩家的处理一致：登记会话、保存原位置/游戏模式（竞技模式另保存背包），再执行 setupPlayer。
     * 供快速匹配补位路径复用。
     */
    public void registerAndSetupPlayer(MatchSession session, ServerPlayerEntity player) {
        UUID uuid = player.getUuid();
        playerSessionMap.put(uuid, session.getSessionId());
        OriginManager.saveOrigin(player);
        playerOriginalGameMode.put(uuid, player.interactionManager.getGameMode());
        // 仅竞技模式保存背包（休闲模式不动玩家背包）
        if (session.isCompetitive()) {
            InventoryManager.getInstance().setCompetitive(uuid, true);
            InventoryManager.getInstance().saveInventory(player);
        }
        setupPlayer(session, player);
    }

    /**
     * 玩家在对局中死亡重生后调用：对局未结束时传送回其队伍的配置出生点（含维度），
     * 并重置冒险模式与竞技装备，直到对局结束为止。
     */
    public void handleRespawn(ServerPlayerEntity player) {
        MatchSession session = getPlayerSession(player.getUuid());
        if (session == null || session.getPhase() == MatchSession.GamePhase.ENDED) {
            return;
        }
        setupPlayer(session, player);
    }

    private void prepareMatch(MatchSession session) {
        for (UUID uuid : session.getAllPlayers()) {
            ServerPlayerEntity player = getPlayer(uuid);
            if (player != null) {
                setupPlayer(session, player);
            } else {
                Cstmm.LOGGER.debug("[CSTMM - MatchManager] Player {} is offline during prepare, skipping", uuid);
            }
        }
        Cstmm.LOGGER.debug("[CSTMM - MatchManager] Prepared match {}", session.getSessionId());
    }

    private ServerPlayerEntity getPlayer(UUID uuid) {
        MinecraftServer server = Cstmm.getServer();
        if (server == null) return null;
        return server.getPlayerManager().getPlayer(uuid);
    }

    // ==================== Tick 逻辑 ====================

    public void tick() {
        for (Map.Entry<String, MatchSession> entry : new HashMap<>(activeSessions).entrySet()) {
            MatchSession session = entry.getValue();
            if (session.getPhase() == MatchSession.GamePhase.ENDED) {
                if (session.getElapsedMillis() > 30000) {
                    activeSessions.remove(entry.getKey());
                }
                continue;
            }

            switch (session.getPhase()) {
                case PREPARING -> tickPreparing(session);
                case FIGHTING -> tickFighting(session);
                default -> {}
            }

            broadcastHudData(session);
        }
    }

    private void tickPreparing(MatchSession session) {
        int prep = session.getPrepCounter() - 1;
        session.setPrepCounter(prep);

        if (prep > 0) {
            broadcastMatchStatus(session, MatchStatusPayload.StatusType.COUNTDOWN,
                    "§e准备 " + prep + " 秒...");
        } else if (prep == 0) {
            session.setPhase(MatchSession.GamePhase.FIGHTING);
            broadcastMatchStatus(session, MatchStatusPayload.StatusType.MATCH_STARTING,
                    "§a=== 游戏开始！ ===");
            Cstmm.LOGGER.info("[CSTMM - MatchManager] Match {} entered FIGHTING phase", session.getSessionId());
        }
    }

    private void tickFighting(MatchSession session) {
        MapConfig config = ConfigManager.getInstance().getMap(session.getMapName());
        if (config == null) return;

        checkWinCondition(session, config);

        // KILLS 制结算在本 tick 已结束对局时，不再对已恢复原点的玩家执行边界判定，
        // 避免出生点处决与惩罚击杀
        if (session.getPhase() == MatchSession.GamePhase.ENDED) return;

        if (config.getWinCondition() == MapConfig.WinCondition.TIMER) {
            int remaining = session.getRemainingSeconds() - 1;
            session.setRemainingSeconds(Math.max(0, remaining));

            if (remaining <= 0) {
                // 加时投票进行中时等待投票结果，避免每秒重复触发 handleTimerEnd
                if (VoteManager.getInstance().hasActiveVote(session.getSessionId())) {
                    return;
                }
                handleTimerEnd(session, config);
                return;
            }
        }

        BoundaryChecker.checkBoundaries(session, config);
    }

    // ==================== 胜利检测 ====================

    private void checkWinCondition(MatchSession session, MapConfig config) {
        if (config.getWinCondition() == MapConfig.WinCondition.KILLS) {
            int target = config.getTargetKills();
            if (session.getRedKills() >= target) {
                endMatch(session, "§c红队击杀了足够的玩家数，获得胜利！", 1);
                return;
            }
            if (session.getBlueKills() >= target) {
                endMatch(session, "§9蓝队击杀了足够的玩家数，获得胜利！", 2);
            }
        }
    }

    private void handleTimerEnd(MatchSession session, MapConfig config) {
        int redKills = session.getRedKills();
        int blueKills = session.getBlueKills();

        if (redKills > blueKills) {
            endMatch(session, "§c时间截止，红队以更多击杀钳制了比赛！", 1);
        } else if (blueKills > redKills) {
            endMatch(session, "§9时间截止，蓝队以更多击杀钳制了比赛！", 2);
        } else {
            // 按地图配置的平局规则处理：DRAW 直接判平局，OVERTIME 走加时投票
            if (config.getTieRule() == MapConfig.TieRule.DRAW) {
                endMatch(session, "§e时间截止，双方平局！", 0);
            } else if (session.getOvertimeCount() < 5) {
                session.setOvertimeCount(session.getOvertimeCount() + 1);
                VoteManager.getInstance().startOvertimeVote(session);
            } else {
                endMatch(session, "§e多次加时仍未分胜负，平局！", 0);
            }
        }
    }

    // ==================== 结束对局 ====================

    public void endMatch(MatchSession session, String message, int winnerTeam) {
        if (session.getPhase() == MatchSession.GamePhase.ENDED) return;

        Cstmm.LOGGER.info("[CSTMM - MatchManager] Ending match {}: {}", session.getSessionId(), message);

        mapEndTime.put(session.getMapName(), System.currentTimeMillis());

        broadcastMatchStatus(session, MatchStatusPayload.StatusType.MATCH_ENDED, message);

        updatePlayerProfiles(session, winnerTeam);
        restoreAllPlayers(session);

        session.setPhase(MatchSession.GamePhase.ENDED);
        session.setStartTime(System.currentTimeMillis());

        for (UUID uuid : session.getAllPlayers()) {
            playerSessionMap.remove(uuid);
            playerOriginalGameMode.remove(uuid);
            InventoryManager.getInstance().setCompetitive(uuid, false);
        }

        // 发送 HUD 清除数据包
        MinecraftServer server = Cstmm.getServer();
        if (server != null) {
            HudDataPayload clearPayload = new HudDataPayload("", 0, 0, 0, false);
            for (UUID uuid : session.getAllPlayers()) {
                ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
                if (player != null) {
                    NetworkHandler.sendHudData(player, clearPayload);
                }
            }
        }

        Cstmm.LOGGER.info("[CSTMM - MatchManager] Match {} ended", session.getSessionId());
    }

    private void restoreAllPlayers(MatchSession session) {
        MinecraftServer server = Cstmm.getServer();
        if (server == null) return;

        for (UUID uuid : session.getAllPlayers()) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
            if (player == null) {
                continue;
            }

            OriginManager.restoreOrigin(player);

            if (session.isCompetitive()) {
                InventoryManager.getInstance().restoreInventory(player);
            }

            GameMode originalMode = playerOriginalGameMode.getOrDefault(uuid, GameMode.SURVIVAL);
            player.changeGameMode(originalMode);

            Objects.requireNonNull(player.getServer()).getScoreboard().clearTeam(player.getName().getString());
        }
    }

    private void updatePlayerProfiles(MatchSession session, int winnerTeam) {
        PlayerDataManager dataManager = PlayerDataManager.getInstance();

        // 击杀/死亡已在 EventListener 的死亡监听中实时计入个人战绩，
        // 结算时只记录场次与胜负，避免把队伍总分再次叠加导致战绩翻倍
        for (UUID uuid : session.getAllPlayers()) {
            boolean won = session.getPlayerTeam(uuid) == winnerTeam && winnerTeam != 0;
            dataManager.recordMatchEnd(uuid, won);
        }
    }

    // ==================== 离线处理 ====================

    /**
     * 被投票踢出的玩家：在断开连接前恢复原点/背包/游戏模式。
     * 否则玩家以冒险模式+竞技装备状态被踢出，重连后永久滞留冒险模式且错位。
     */
    public void restoreKickedPlayer(UUID targetUuid) {
        MinecraftServer server = Cstmm.getServer();
        ServerPlayerEntity player = server != null ? server.getPlayerManager().getPlayer(targetUuid) : null;
        if (player != null) {
            OriginManager.restoreOrigin(player);
            InventoryManager.getInstance().restoreInventory(player);
            GameMode mode = playerOriginalGameMode.remove(targetUuid);
            player.changeGameMode(mode != null ? mode : GameMode.SURVIVAL);
        } else {
            playerOriginalGameMode.remove(targetUuid);
        }
    }

    public void onPlayerDisconnect(UUID playerUuid) {
        String sessionId = playerSessionMap.get(playerUuid);
        if (sessionId == null) return;

        MatchSession session = activeSessions.get(sessionId);
        if (session == null || session.getPhase() == MatchSession.GamePhase.ENDED) {
            playerSessionMap.remove(playerUuid);
            MinecraftServer server = Cstmm.getServer();
            if (server != null) {
                ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerUuid);
                if (player != null) {
                    InventoryManager.getInstance().restoreInventory(player);
                    OriginManager.restoreOrigin(player);
                }
            }
            return;
        }

        boolean redOnline = false;
        boolean blueOnline = false;

        MinecraftServer server = Cstmm.getServer();
        if (server != null) {
            for (UUID uuid : session.getRedPlayers()) {
                ServerPlayerEntity p = server.getPlayerManager().getPlayer(uuid);
                if (p != null && !uuid.equals(playerUuid)) {
                    redOnline = true;
                    break;
                }
            }
            for (UUID uuid : session.getBluePlayers()) {
                ServerPlayerEntity p = server.getPlayerManager().getPlayer(uuid);
                if (p != null && !uuid.equals(playerUuid)) {
                    blueOnline = true;
                    break;
                }
            }
        }

        if (!redOnline && blueOnline) {
            endMatch(session, "§9红队已无人在线，蓝队获胜！", 2);
        } else if (redOnline && !blueOnline) {
            endMatch(session, "§c蓝队已无人在线，红队获胜！", 1);
        } else if (!redOnline && !blueOnline) {
            endMatch(session, "§e双方均无人在线，对局终止！", 0);
        }
    }

    // ==================== 查询方法 ====================

    public MatchSession getSession(String sessionId) {
        return activeSessions.get(sessionId);
    }

    public MatchSession getPlayerSession(UUID playerUuid) {
        String sessionId = playerSessionMap.get(playerUuid);
        if (sessionId == null) return null;
        return activeSessions.get(sessionId);
    }

    public List<MatchSession> getAllActiveSessions() {
        return new ArrayList<>(activeSessions.values());
    }

    // ==================== 广播方法 ====================

    private void broadcastHudData(MatchSession session) {
        MinecraftServer server = Cstmm.getServer();
        if (server == null) return;

        HudDataPayload payload = new HudDataPayload(
                session.getMapName(),
                session.getRedKills(),
                session.getBlueKills(),
                session.getRemainingSeconds(),
                session.getPhase() != MatchSession.GamePhase.ENDED
        );

        for (UUID uuid : session.getAllPlayers()) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
            if (player != null) {
                NetworkHandler.sendHudData(player, payload);
            }
        }
    }

    private void broadcastMatchStatus(MatchSession session, MatchStatusPayload.StatusType type, String message) {
        MinecraftServer server = Cstmm.getServer();
        if (server == null) return;

        MatchStatusPayload payload = new MatchStatusPayload(type, message, session.getRedKills(), session.getBlueKills());

        for (UUID uuid : session.getAllPlayers()) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
            if (player != null) {
                // 仅通过 payload 发送，客户端收到后统一展示；服务端不再 sendMessage，避免消息显示两遍
                NetworkHandler.sendMatchStatus(player, payload);
            }
        }
    }
}