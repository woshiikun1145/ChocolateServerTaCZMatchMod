package cn.woshiikun_1145.mcmod.choco.cstmm.manager;

import cn.woshiikun_1145.mcmod.choco.cstmm.Cstmm;
import cn.woshiikun_1145.mcmod.choco.cstmm.api.MatchApi;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.Clan;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.PlayerProfile;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.config.MapConfig;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.MatchSession;
import cn.woshiikun_1145.mcmod.choco.cstmm.network.NetworkHandler;
import cn.woshiikun_1145.mcmod.choco.cstmm.network.payload.HudDataPayload;
import cn.woshiikun_1145.mcmod.choco.cstmm.network.payload.MatchStatusPayload;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
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

/**
 * 【作用】服务端对局核心管理器：创建/推进/结束对局会话（MatchSession），维护"玩家-会话"映射与对局内玩家状态（原点、游戏模式、竞技背包），负责胜负判定、结算恢复与 HUD/状态广播。
 * 【被谁使用】MatchScheduler#onSecondTick（每秒 tick）、QueueManager#startMatchFromQueue 与 QuickMatchEngine#startQuickMatch/#reinforceInto（开局/补位）、EventListener（重生/断线/击杀判定）、VoteManager（加时/踢人/无人结算）、NetworkHandler（购买/HUD/队列状态）、ModCommands（管理命令）；同时作为 MatchApi 的实现供外部 API 调用。均为服务端。
 */
public class MatchManager implements MatchApi {
    private static MatchManager instance;

    /** 已警告过非法/缺失的维度 ID，避免重复刷 warn 日志 */
    private static final Set<String> warnedDimensions = ConcurrentHashMap.newKeySet();

    // ==================== 调试：比赛信息栏强制显示 ====================
    /** 调试开关（/cstmm debug match_info_hud <t|f>）：开启后仅目标玩家（执行命令的管理员）
     *  收到首个活跃对局的 HUD，不影响其他玩家的客户端显示 */
    private volatile boolean matchHudForced = false;
    /** 强制显示的目标玩家 UUID：开启时为执行命令的玩家，关闭时置 null */
    private volatile UUID matchHudForcedPlayer = null;
    /** 清空 HUD 包（inGame=false，mapName 空、花名册空）：强制显示关闭时复位客户端 HUD */
    private static final HudDataPayload IDLE_HUD_PAYLOAD = new HudDataPayload("", 0, 0, 0, false, 0, "[]");
    /** 无活跃对局时的调试预览 HUD（inGame=true 仅中央计分板：地图名"HUD 调试预览"+ 0:0 + 90 秒倒计时，
     *  保证强制显示在任何时刻都有可见反馈；花名册为空 → 不渲染两侧队伍卡片） */
    private static final HudDataPayload DEMO_HUD_PAYLOAD = new HudDataPayload("HUD 调试预览", 0, 0, 90, true, 2, "[]");

    // 会话 ID -> 对局会话（含已结束、等待清理的会话）
    private final Map<String, MatchSession> activeSessions;
    // 玩家 UUID -> 所在对局会话 ID（判断玩家是否在对局的核心索引）
    private final Map<UUID, String> playerSessionMap;
    // 玩家 UUID -> 进入对局前的游戏模式（结算/被踢时恢复）
    private final Map<UUID, GameMode> playerOriginalGameMode;
    // 地图 ID -> 上次对局结束时间戳（用于地图冷却判定）
    private final Map<String, Long> mapEndTime;

    private MatchManager() {
        this.activeSessions = new ConcurrentHashMap<>();
        this.playerSessionMap = new ConcurrentHashMap<>();
        this.playerOriginalGameMode = new ConcurrentHashMap<>();
        this.mapEndTime = new ConcurrentHashMap<>();
    }

    // 单例获取（服务端调度器/队列/引擎/监听器/网络层/命令共用）
    public static MatchManager getInstance() {
        if (instance == null) {
            instance = new MatchManager();
        }
        return instance;
    }

    // ==================== API 实现 ====================

    /**
     * 【作用】查询玩家当前对局状态快照（地图名、剩余秒数、双方击杀数、所处阶段），不在对局时返回全零默认值。
     * 【被谁使用】MatchApi 接口实现（外部 API 入口，项目内暂无直接调用方）；服务端。
     */
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

    /**
     * 【作用】判断玩家是否正处于某场对局中。
     * 【被谁使用】QueueManager#joinQueue/#joinQuickQueue（入队拦截）、ModCommands queue 命令（状态提示）；MatchApi 接口实现（外部 API 入口）；服务端。
     */
    @Override
    public boolean isInGame(UUID playerUuid) {
        return playerSessionMap.containsKey(playerUuid);
    }

    /**
     * 【作用】查询玩家所在对局的击杀数据（双方队伍总击杀，并按玩家阵营换算己方/敌方击杀）。
     * 【被谁使用】MatchApi 接口实现（外部 API 入口，项目内暂无直接调用方）；服务端。
     */
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

    // 将玩家移出会话映射（VoteManager 踢人投票通过后调用）
    public void removePlayerFromSession(UUID playerUuid) {
        playerSessionMap.remove(playerUuid);
    }

    // 返回玩家-会话映射的只读视图（项目内暂无调用方）
    public Map<UUID, String> getPlayerSessionMapView() {
        return Collections.unmodifiableMap(playerSessionMap);
    }

    // ==================== 对局生命周期 ====================

    /** @return 开局是否成功（失败时调用方应保留玩家在队列中）
     * 【被谁使用】兼容旧签名的重载，项目内当前无调用方（实际开局均走下方 4 参版本）；服务端。
     */
    public boolean startMatch(String mapId, List<ServerPlayerEntity> redPlayers, List<ServerPlayerEntity> bluePlayers) {
        // 兼容旧签名（命令/API 调用方）：固定按竞技模式开局
        return startMatch(mapId, true, redPlayers, bluePlayers);
    }

    /**
     * 按指定模式开局：模式由玩家加入队列时选择，随队列传递（不再取自地图配置）。
     * 【被谁使用】QueueManager#startMatchFromQueue（地图队列开局）、QuickMatchEngine#startQuickMatch（快速匹配开局）；服务端。
     * @param competitive true=竞技模式（存/清背包、发默认装备、可用商店、允许友伤），false=休闲
     * @return 开局是否成功（失败时调用方应保留玩家在队列中）
     */
    public boolean startMatch(String mapId, boolean competitive, List<ServerPlayerEntity> redPlayers, List<ServerPlayerEntity> bluePlayers) {
        // 【作用】校验地图配置存在且已启用，否则拒绝开局
        MapConfig config = ConfigManager.getInstance().getMap(mapId);
        if (config == null || !config.isEnabled()) {
            Cstmm.LOGGER.warn("[CSTMM - MatchManager] Map {} is not enabled or not found", mapId);
            return false;
        }

        // 【作用】校验双方出生点非空：手改 maps.json 可绕过保存校验产生空/缺失出生点的地图，
        // 否则 setupPlayer 会把玩家传送到世界原点（getRandomSpawn 对空列表返回 BlockPos.ORIGIN）
        if (config.getRedSpawns() == null || config.getRedSpawns().isEmpty()
                || config.getBlueSpawns() == null || config.getBlueSpawns().isEmpty()) {
            Cstmm.LOGGER.warn("[CSTMM - MatchManager] Map {} has empty red/blue spawns, refusing to start match", mapId);
            return false;
        }

        // 【作用】检查地图冷却期：上一局结束未满配置冷却秒数时拒绝开局
        Long endTime = mapEndTime.get(mapId);
        if (endTime != null) {
            long cooldown = config.getCooldownSeconds() * 1000L;
            if (System.currentTimeMillis() - endTime < cooldown) {
                Cstmm.LOGGER.debug("[CSTMM - MatchManager] Map {} is in cooldown", mapId);
                return false;
            }
        }

        // 【作用】同一地图已有未结束的对局时拒绝开局（先到先得）
        for (MatchSession session : activeSessions.values()) {
            if (session.getMapName().equals(mapId) && session.getPhase() != MatchSession.GamePhase.ENDED) {
                Cstmm.LOGGER.warn("[CSTMM - MatchManager] Map {} already has an active session", mapId);
                return false;
            }
        }

        // 【作用】双方名单中任一玩家已在其他对局时拒绝，防止重复入局
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

        // 【作用】创建会话并登记双方玩家：入会话名单、建立玩家映射、保存原点/游戏模式（竞技模式另存背包）
        MatchSession session = new MatchSession(mapId);
        // 模式来自匹配队列选择
        session.setCompetitive(competitive);

        for (ServerPlayerEntity player : redPlayers) {
            session.getRedPlayers().add(player.getUuid());
            playerSessionMap.put(player.getUuid(), session.getSessionId());
            OriginManager.saveOrigin(player);
            playerOriginalGameMode.putIfAbsent(player.getUuid(), player.interactionManager.getGameMode());
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
            playerOriginalGameMode.putIfAbsent(player.getUuid(), player.interactionManager.getGameMode());
            if (competitive) {
                InventoryManager.getInstance().setCompetitive(player.getUuid(), true);
                InventoryManager.getInstance().saveInventory(player);
            }
        }

        // 【作用】计时制地图：按配置初始化剩余秒数
        if (config.getWinCondition() == MapConfig.WinCondition.TIMER) {
            session.setRemainingSeconds(config.getMaxDuration());
        }

        // 【作用】进入准备阶段并注册会话，此后由 tick 按秒推进
        session.setPhase(MatchSession.GamePhase.PREPARING);

        int prepareTime = config.getPrepareTime();
        // 钳制非法配置：prepareTime <= 0 会让 tickPreparing 永远无法进入 FIGHTING，对局卡死
        if (prepareTime < 1) prepareTime = 1;
        session.setPrepCounter(prepareTime);

        activeSessions.put(session.getSessionId(), session);

        // 【作用】广播开局预告并立即执行全体玩家的对局初始化
        broadcastMatchStatus(session, MatchStatusPayload.StatusType.MATCH_STARTING,
                "§6=== " + config.getDisplayName() + " 即将开始！准备倒计时 " + prepareTime + " 秒 ===");

        prepareMatch(session);

        Cstmm.LOGGER.info("[CSTMM - MatchManager] Started match on {} with {} players", mapId, session.getTotalPlayers());
        return true;
    }

    /** 地图是否处于对局结束后的冷却期（冷却未过期的地图不可用于快速匹配）
     * 【被谁使用】QuickMatchEngine#getAvailableMaps/#tryStartWithOtherQueue（筛选候选地图）、QueueManager#buildQueueStatusJson（队列页状态）；服务端。
     */
    public boolean isMapInCooldown(String mapId) {
        Long endTime = mapEndTime.get(mapId);
        if (endTime == null) return false;
        MapConfig config = ConfigManager.getInstance().getMap(mapId);
        long cooldown = config != null ? config.getCooldownSeconds() * 1000L : 0L;
        return System.currentTimeMillis() - endTime < cooldown;
    }

    /** 按地图配置解析对局维度；解析失败或世界不存在时回退主世界（每个非法维度仅 warn 一次）
     * 【被谁使用】setupPlayer（服务端内部，确定玩家传送的目标维度）。
     */
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
     * 【被谁使用】prepareMatch（开局初始化）、registerAndSetupPlayer（补位登记后初始化）、handleRespawn（重生复位）；均服务端内部调用。
     */
    public void setupPlayer(MatchSession session, ServerPlayerEntity player) {
        MapConfig config = ConfigManager.getInstance().getMap(session.getMapName());
        if (config == null) return;

        // 【作用】缓存玩家名（HUD 花名册在玩家离线后仍能显示其卡片名）
        session.recordPlayerName(player.getUuid(), player.getName().getString());

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
     * 【被谁使用】QuickMatchEngine#reinforceInto（快速匹配补位入口）；服务端。
     */
    public void registerAndSetupPlayer(MatchSession session, ServerPlayerEntity player) {
        UUID uuid = player.getUuid();
        playerSessionMap.put(uuid, session.getSessionId());
        OriginManager.saveOrigin(player);
        playerOriginalGameMode.putIfAbsent(uuid, player.interactionManager.getGameMode());
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
     * 对局已结束/已不在对局时：补做结算瞬间因死亡被跳过的恢复（原点/背包/原游戏模式，
     * 见 restoreAllPlayers）；无任何待恢复状态（无快照且无原模式映射）时为无操作，不影响普通重生。
     * 【被谁使用】EventListener 玩家重生事件（服务端）。
     */
    public void handleRespawn(ServerPlayerEntity player) {
        UUID uuid = player.getUuid();
        MatchSession session = getPlayerSession(uuid);
        if (session != null && session.getPhase() != MatchSession.GamePhase.ENDED) {
            setupPlayer(session, player);
            return;
        }

        // 结算时因死亡跳过恢复的玩家在此补做（休闲对局无快照，仅恢复原点与原游戏模式）
        if (InventoryManager.getInstance().hasSavedInventory(uuid)
                || playerOriginalGameMode.containsKey(uuid)) {
            OriginManager.restoreOrigin(player);
            InventoryManager.getInstance().restoreInventory(player); // 无快照时内部直接返回 false
            GameMode originalMode = playerOriginalGameMode.remove(uuid);
            if (originalMode != null) {
                player.changeGameMode(originalMode);
            }
        }
    }

    /**
     * 【作用】开局准备：对会话全体在线玩家执行初始化（传送/游戏模式/装备），离线玩家跳过。
     * 【被谁使用】startMatch（服务端内部，会话创建后调用）。
     */
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

    // 按 UUID 查找在线玩家实体（服务端内部工具方法）
    private ServerPlayerEntity getPlayer(UUID uuid) {
        MinecraftServer server = Cstmm.getServer();
        if (server == null) return null;
        return server.getPlayerManager().getPlayer(uuid);
    }

    // ==================== Tick 逻辑 ====================

    /**
     * 【作用】对局主循环：按秒推进所有活跃会话（准备倒计时/战斗阶段判定），清理超时的已结束会话并广播 HUD。
     * 【被谁使用】MatchScheduler#onSecondTick（服务端每秒调用一次）。
     */
    public void tick() {
        // 【作用】遍历会话快照，避免遍历期间移除会话引发并发修改
        for (Map.Entry<String, MatchSession> entry : new HashMap<>(activeSessions).entrySet()) {
            MatchSession session = entry.getValue();
            // 【作用】已结束会话保留 30 秒供查询，超时后从活跃表移除
            if (session.getPhase() == MatchSession.GamePhase.ENDED) {
                if (session.getElapsedMillis() > 30000) {
                    activeSessions.remove(entry.getKey());
                }
                continue;
            }

            // 【作用】按阶段推进对局，并向全体玩家推送最新 HUD 快照
            switch (session.getPhase()) {
                case PREPARING -> tickPreparing(session);
                case FIGHTING -> tickFighting(session);
                default -> {}
            }

            broadcastHudData(session);
        }

        // 【作用】调试强制显示比赛信息栏：向未在对局中的在线玩家广播首个活跃对局的 HUD；
        //        全部对局结束时发一次清空包复位其 HUD
        if (matchHudForced) {
            broadcastForcedHudData();
        }
    }

    /**
     * 【作用】准备阶段每秒倒计时，归零后切入战斗阶段并广播开局消息。
     * 【被谁使用】tick（服务端内部，PREPARING 阶段每秒调用）。
     */
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

    /**
     * 【作用】战斗阶段每秒判定：胜利条件检查、计时制倒计时与平局/加时处理、对局边界检查。
     * 【被谁使用】tick（服务端内部，FIGHTING 阶段每秒调用）。
     */
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

    /**
     * 【作用】KILLS 制胜利检测：任一队击杀数达到目标即结束对局并判胜。
     * 【被谁使用】tickFighting（服务端内部，每秒调用）。
     */
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

    /**
     * 【作用】计时制时间截止结算：按击杀数判胜，平局时按地图规则（直接判平或加时投票）处理。
     * 【被谁使用】tickFighting（服务端内部，TIMER 图剩余秒数归零时调用）。
     */
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

    /**
     * 【作用】结束对局：记录地图冷却起点、广播结算消息、按胜负更新玩家战绩、恢复全员原点/背包/游戏模式并清理会话映射，最后下发 HUD 清除包。
     * 【被谁使用】checkWinCondition/#handleTimerEnd/#onPlayerDisconnect（服务端内部）；VoteManager（加时投票未通过、踢人后单侧无人等结算）、ModCommands（管理员强制结束）；服务端。
     */
    public void endMatch(MatchSession session, String message, int winnerTeam) {
        if (session.getPhase() == MatchSession.GamePhase.ENDED) return;

        Cstmm.LOGGER.info("[CSTMM - MatchManager] Ending match {}: {}", session.getSessionId(), message);

        mapEndTime.put(session.getMapName(), System.currentTimeMillis());

        broadcastMatchStatus(session, MatchStatusPayload.StatusType.MATCH_ENDED, message);

        updatePlayerProfiles(session, winnerTeam);
        // 结算时因死亡被跳过恢复的玩家：快照/原点/原游戏模式保留，待 AFTER_RESPAWN 补恢复
        Set<UUID> pendingRespawnRestore = restoreAllPlayers(session);

        session.setPhase(MatchSession.GamePhase.ENDED);
        session.setStartTime(System.currentTimeMillis());

        for (UUID uuid : session.getAllPlayers()) {
            playerSessionMap.remove(uuid);
            // 死亡未重生的玩家保留原游戏模式映射（重生补恢复时才移除），
            // 防止其在重生前重新排队进新对局时被当前（冒险）模式覆盖真实原模式
            if (!pendingRespawnRestore.contains(uuid)) {
                playerOriginalGameMode.remove(uuid);
            }
            InventoryManager.getInstance().setCompetitive(uuid, false);
        }

        // 发送 HUD 清除数据包（空快照与对局内快照必然不同，经 sendHudData 去重后必然实际发出）
        MinecraftServer server = Cstmm.getServer();
        if (server != null) {
            HudDataPayload clearPayload = new HudDataPayload("", 0, 0, 0, false, 0, "[]");
            for (UUID uuid : session.getAllPlayers()) {
                ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
                if (player != null) {
                    NetworkHandler.sendHudData(player, clearPayload);
                }
            }
        }

        Cstmm.LOGGER.info("[CSTMM - MatchManager] Match {} ended", session.getSessionId());
    }

    /**
     * 【作用】对局结束时恢复全体在线玩家：回原点、还原背包（竞技模式）、恢复原游戏模式并清空计分板队伍。
     *         处于死亡状态（死亡界面未重生）的玩家跳过恢复：向重生后即被丢弃的旧实体恢复快照
     *         会导致快照被消费而新实体为空背包（原物品永久丢失），故保留全部待恢复状态，
     *         由 handleRespawn 在其重生时补做。
     * @return 因死亡跳过、待重生补恢复的玩家 UUID 集合
     * 【被谁使用】endMatch（服务端内部）。
     */
    private Set<UUID> restoreAllPlayers(MatchSession session) {
        Set<UUID> pendingRespawn = new HashSet<>();
        MinecraftServer server = Cstmm.getServer();
        if (server == null) return pendingRespawn;

        for (UUID uuid : session.getAllPlayers()) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
            if (player == null) {
                continue;
            }

            // 死亡界面未重生的玩家：跳过原点/背包/游戏模式恢复（旧实体重生后即被丢弃），
            // 仅清除计分板队伍（队伍按玩家名记录、跨重生保留，必须清）；其余由重生补恢复
            if (player.isDead()) {
                Objects.requireNonNull(player.getServer()).getScoreboard().clearTeam(player.getName().getString());
                pendingRespawn.add(uuid);
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
        return pendingRespawn;
    }

    /**
     * 【作用】结算玩家战绩：按所属队伍与胜方记录场次与胜负（击杀/死亡已由事件监听实时计入，不在此重复累计）。
     * 【被谁使用】endMatch（服务端内部）。
     */
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
     * 目标处于死亡界面（重生会丢弃当前实体）时：不能向其恢复背包/原点——快照会被
     * 消费进即将丢弃的实体导致原物品永久丢失。仅恢复游戏模式（随实体存档持久化，
     * 重生后即生效）；快照与原点保留，重连重生时由 {@link #handleRespawn} 补恢复。
     * 【被谁使用】VoteManager 踢人投票通过后（服务端）。
     */
    public void restoreKickedPlayer(UUID targetUuid) {
        MinecraftServer server = Cstmm.getServer();
        ServerPlayerEntity player = server != null ? server.getPlayerManager().getPlayer(targetUuid) : null;
        if (player == null) {
            playerOriginalGameMode.remove(targetUuid);
            return;
        }
        if (player.isDead()) {
            GameMode mode = playerOriginalGameMode.remove(targetUuid);
            if (mode != null) {
                player.changeGameMode(mode);
            }
            return;
        }
        OriginManager.restoreOrigin(player);
        InventoryManager.getInstance().restoreInventory(player);
        GameMode mode = playerOriginalGameMode.remove(targetUuid);
        player.changeGameMode(mode != null ? mode : GameMode.SURVIVAL);
    }

    /**
     * 【作用】玩家断线处理：不在对局（或对局已结束）时仅清理映射并恢复状态；在对局中则统计双方在线人数，单侧无人判负结束，双侧无人终止对局。
     * 【被谁使用】EventListener 玩家断开连接事件（服务端）。
     */
    public void onPlayerDisconnect(UUID playerUuid) {
        String sessionId = playerSessionMap.get(playerUuid);
        if (sessionId == null) return;

        // 【作用】玩家不在对局或对局已结束：仅清理映射并恢复背包/原点
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

        // 【作用】统计除断线玩家外双方仍在线的玩家
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

        // 【作用】按在线情况结算：单侧无人判负，双侧无人终止
        if (!redOnline && blueOnline) {
            endMatch(session, "§9红队已无人在线，蓝队获胜！", 2);
        } else if (redOnline && !blueOnline) {
            endMatch(session, "§c蓝队已无人在线，红队获胜！", 1);
        } else if (!redOnline && !blueOnline) {
            endMatch(session, "§e双方均无人在线，对局终止！", 0);
        }
    }

    // ==================== 查询方法 ====================

    /**
     * 【作用】按会话 ID 查找对局会话（含已结束、等待清理的会话）。
     * 【被谁使用】ModCommands（管理命令）、VoteManager（投票会话解析）；服务端。
     */
    public MatchSession getSession(String sessionId) {
        return activeSessions.get(sessionId);
    }

    /**
     * 【作用】按玩家 UUID 解析其所在对局会话，不在对局时返回 null。
     * 【被谁使用】EventListener（死亡/友伤/断线/击杀）、VoteManager（投票/踢人）、NetworkHandler（购买/商店）、EquipmentManager、ModCommands 及本类状态查询；服务端。
     */
    public MatchSession getPlayerSession(UUID playerUuid) {
        String sessionId = playerSessionMap.get(playerUuid);
        if (sessionId == null) return null;
        return activeSessions.get(sessionId);
    }

    /**
     * 【作用】返回全部活跃会话的快照列表（含已结束、等待清理的会话）。
     * 【被谁使用】QueueManager（开局前地图占用检查）、QuickMatchEngine（候选地图/补位筛选）、NetworkHandler、ModCommands；服务端。
     */
    public List<MatchSession> getAllActiveSessions() {
        return new ArrayList<>(activeSessions.values());
    }

    // ==================== 广播方法 ====================

    /**
     * 每秒对对局内全体玩家广播 HUD 快照（订阅式推送：NetworkHandler.sendHudData 按玩家
     * 去重，内容与上次一致时跳过发包）。TIMER 图每秒 remainingSeconds 变化必然发包，
     * KILLS 图仅在击杀/死亡/花名册变化后发包——对局内多数秒为 0 流量。
     * 快照含 CS2 风格花名册 JSON（玩家名/队伍/个人击杀/死亡/上一条命击杀/战队缩写/徽标/头像绑定），
     * 引用的战队徽标经 ensureHudBadge 保证分片先行下发。
     */
    private void broadcastHudData(MatchSession session) {
        MinecraftServer server = Cstmm.getServer();
        if (server == null) return;

        // 【作用】构建本局花名册 JSON（全快照统一一份，内容不变时经 sendHudData 去重零流量）
        String rosterJson = buildRosterJson(session);
        Set<String> rosterBadgeIds = collectRosterBadgeIds(rosterJson);

        HudDataPayload payload = new HudDataPayload(
                session.getMapName(),
                session.getRedKills(),
                session.getBlueKills(),
                // 准备阶段下发准备倒计时（remainingSeconds 此时是对局总时长/0，不是准备秒数）
                session.getPhase() == MatchSession.GamePhase.PREPARING
                        ? session.getPrepCounter() : session.getRemainingSeconds(),
                session.getPhase() != MatchSession.GamePhase.ENDED,
                session.getPhase().ordinal(),
                rosterJson
        );

        for (UUID uuid : session.getAllPlayers()) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
            if (player != null) {
                // 【作用】花名册引用的战队徽标分片先行下发（内容寻址去重，仅首次实际发包）
                for (String badgeId : rosterBadgeIds) {
                    NetworkHandler.ensureHudBadge(player, badgeId);
                }
                NetworkHandler.sendHudData(player, payload);
            }
        }
    }

    /**
     * 【作用】设置比赛信息栏强制显示开关（/cstmm debug match_info_hud <t|f> 调试用）：
     *         开启后仅执行命令的玩家（目标玩家）每秒收到首个活跃对局的 HUD
     *         （无活跃对局时收到调试预览计分板），并立即推送一次（不等下一秒 tick）；
     *         关闭或更换目标时向原目标发清空包复位其客户端 HUD。
     * 【被谁使用】ModCommands（debug match_info_hud 子命令）。仅服务端。
     */
    public void setMatchHudForced(boolean forced, UUID playerUuid) {
        UUID previousTarget = this.matchHudForced ? this.matchHudForcedPlayer : null;
        if (forced == this.matchHudForced && Objects.equals(previousTarget, playerUuid)) return;
        this.matchHudForced = forced;
        this.matchHudForcedPlayer = forced ? playerUuid : null;
        Cstmm.LOGGER.info("[CSTMM - MatchManager] Match HUD force display {} (target {})",
                forced ? "ENABLED" : "DISABLED", forced ? playerUuid : "-");
        // 原目标失去强制显示（被更换或关闭）：立即发清空包复位，避免其客户端 HUD 滞留旧数据
        if (previousTarget != null && (!forced || !previousTarget.equals(playerUuid))) {
            sendIdleHudTo(previousTarget);
        }
        if (forced) {
            // 立即向执行者推送一次，给管理员即时反馈（不必等下一秒 tick）
            broadcastForcedHudData();
        }
    }

    /** 【作用】查询比赛信息栏是否处于强制显示（调试开关状态）。 */
    public boolean isMatchHudForced() {
        return matchHudForced;
    }

    /**
     * 【作用】调试强制显示的每秒推送：仅发给目标玩家（执行命令的管理员）。
     *         取首个活跃（非 ENDED）对局作为展示源；无活跃对局时发调试预览计分板
     *         （DEMO_HUD_PAYLOAD，保证可见反馈）；目标离线时跳过，回线后下一秒恢复。
     * 【被谁使用】tick（matchHudForced 开启时每秒调用）、setMatchHudForced（开启时立即推送一次）。仅服务端。
     */
    private void broadcastForcedHudData() {
        if (!matchHudForced) return;
        UUID target = matchHudForcedPlayer;
        if (target == null) return;
        MinecraftServer server = Cstmm.getServer();
        if (server == null) return;
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(target);
        if (player == null) return;
        // 目标已进入活跃对局：其 HUD 由常规每秒广播负责，跳过镜像推送
        // （否则自己对局数据与镜像数据每秒交替发包，客户端信息栏会闪烁）
        if (isInActiveSession(target)) return;

        // 【作用】取首个活跃对局作为强制展示源（ENDED 会话不计入，其玩家 HUD 已复位）
        MatchSession forced = null;
        for (MatchSession s : activeSessions.values()) {
            if (s.getPhase() != MatchSession.GamePhase.ENDED) {
                forced = s;
                break;
            }
        }
        if (forced == null) {
            // 无活跃对局：发调试预览计分板（inGame=true，验证强制显示链路是否生效）
            NetworkHandler.sendHudData(player, DEMO_HUD_PAYLOAD);
            return;
        }

        // 【作用】构建展示源的 HUD 数据包与徽标分片（与常规 broadcastHudData 同源同构）
        String rosterJson = buildRosterJson(forced);
        Set<String> rosterBadgeIds = collectRosterBadgeIds(rosterJson);
        HudDataPayload payload = new HudDataPayload(
                forced.getMapName(),
                forced.getRedKills(),
                forced.getBlueKills(),
                // 准备阶段下发准备倒计时（与常规广播一致）
                forced.getPhase() == MatchSession.GamePhase.PREPARING
                        ? forced.getPrepCounter() : forced.getRemainingSeconds(),
                forced.getPhase() != MatchSession.GamePhase.ENDED,
                forced.getPhase().ordinal(),
                rosterJson
        );

        // 【作用】花名册引用的战队徽标分片先行下发（内容寻址去重，仅首次实际发包）
        for (String badgeId : rosterBadgeIds) {
            NetworkHandler.ensureHudBadge(player, badgeId);
        }
        NetworkHandler.sendHudData(player, payload);
    }

    /** 【作用】向指定玩家发清空 HUD 包（inGame=false，客户端淡出复位）；
     *  目标在活跃对局中时跳过——其 HUD 由常规广播负责，发清空包会误抹掉对局内信息栏一秒。 */
    private void sendIdleHudTo(UUID playerUuid) {
        MinecraftServer server = Cstmm.getServer();
        if (server == null) return;
        if (isInActiveSession(playerUuid)) return;
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerUuid);
        if (player != null) {
            NetworkHandler.sendHudData(player, IDLE_HUD_PAYLOAD);
        }
    }

    /** 【作用】判断玩家是否处于任一活跃（非 ENDED）对局中。 */
    private boolean isInActiveSession(UUID uuid) {
        for (MatchSession s : activeSessions.values()) {
            if (s.getPhase() != MatchSession.GamePhase.ENDED && s.isPlayerInGame(uuid)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 【作用】构建本局花名册紧凑 JSON 数组（CS2 风格 HUD 数据源）。
     * 每个元素字段：u=uuid、n=名字、t=队伍(1红/2蓝)、k=本局个人击杀、d=本局死亡数、
     * lk=上一条命击杀、c=战队缩写（无战队省略）、b=徽标引用（base64 徽标为内容寻址 id，
     * URL 徽标为完整 URL，客户端自行下载；无徽标省略）、at/ai=头像绑定平台/账号（未设置省略）。
     * 离线玩家用名字缓存兜底；血量/存活状态由客户端本地读取，不入包。
     * 【被谁使用】broadcastHudData（每秒构建）。仅服务端。
     */
    private String buildRosterJson(MatchSession session) {
        JsonArray array = new JsonArray();
        ClanManager clanManager = ClanManager.getInstance();
        // 红队在前蓝队在后，队内按开局名单顺序（集合迭代序稳定，内容不变时 JSON 逐字节一致，供去重）
        appendTeamToRoster(session, session.getRedPlayers(), 1, clanManager, array);
        appendTeamToRoster(session, session.getBluePlayers(), 2, clanManager, array);
        return array.toString();
    }

    /** 【作用】把一支队伍的玩家追加进花名册 JSON（buildRosterJson 辅助）。仅服务端。 */
    private void appendTeamToRoster(MatchSession session, Set<UUID> players, int team,
                                    ClanManager clanManager, JsonArray array) {
        for (UUID uuid : players) {
            ServerPlayerEntity online = null;
            MinecraftServer server = Cstmm.getServer();
            if (server != null) {
                online = server.getPlayerManager().getPlayer(uuid);
            }
            // 在线优先用实时名，离线回退名字缓存，再兜底占位
            String name = online != null ? online.getName().getString() : session.getPlayerName(uuid);
            if (name == null) name = "未知玩家";
            if (online != null) {
                // 【作用】在线时刻持续刷新名字缓存（改名/首帧补位均覆盖）
                session.recordPlayerName(uuid, name);
            }

            JsonObject o = new JsonObject();
            o.addProperty("u", uuid.toString());
            o.addProperty("n", name);
            o.addProperty("t", team);
            o.addProperty("k", session.getPlayerKills(uuid));
            o.addProperty("d", session.getPlayerDeaths(uuid));
            o.addProperty("lk", session.getLastLifeKills(uuid));

            // 【作用】战队信息：缩写 + 徽标引用（URL 徽标下发完整 URL，base64 徽标下发内容寻址 id）
            Clan clan = clanManager.getClanByPlayer(uuid);
            if (clan != null) {
                o.addProperty("c", clan.getAbbreviation());
                String badge = clan.getBadgeBase64();
                if (badge != null && !badge.isEmpty()) {
                    o.addProperty("b", ClanManager.isBadgeUrl(badge) ? badge : clan.getBadgeId());
                }
            }

            // 【作用】头像绑定（档案字段，未设置省略；图片由各客户端按绑定自行获取）
            PlayerProfile profile = PlayerDataManager.getInstance().getProfile(uuid);
            if (profile != null) {
                String avatarType = profile.getAvatarType();
                String avatarId = profile.getAvatarId();
                if (!avatarType.isEmpty() && !avatarId.isEmpty()) {
                    o.addProperty("at", avatarType);
                    o.addProperty("ai", avatarId);
                }
            }
            array.add(o);
        }
    }

    /**
     * 【作用】从花名册 JSON 中收集全部徽标引用（去重），供逐玩家补发徽标分片。
     * 简易解析：按 "b":" 值定位（JSON 由本类构建，字段值不含转义引号）。
     * 【被谁使用】broadcastHudData（每秒调用）。仅服务端。
     */
    private Set<String> collectRosterBadgeIds(String rosterJson) {
        Set<String> ids = new LinkedHashSet<>();
        if (rosterJson == null) return ids;
        String marker = "\"b\":\"";
        int idx = 0;
        while ((idx = rosterJson.indexOf(marker, idx)) >= 0) {
            int start = idx + marker.length();
            int end = rosterJson.indexOf('"', start);
            if (end < 0) break;
            ids.add(rosterJson.substring(start, end));
            idx = end;
        }
        return ids;
    }

    /**
     * 【作用】向会话全体在线玩家广播对局状态消息（开局预告/倒计时/结算），经网络包统一下发。
     * 【被谁使用】startMatch、tickPreparing、endMatch（服务端内部）。
     */
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