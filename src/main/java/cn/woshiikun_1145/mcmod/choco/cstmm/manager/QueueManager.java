package cn.woshiikun_1145.mcmod.choco.cstmm.manager;

import cn.woshiikun_1145.mcmod.choco.cstmm.Cstmm;
import cn.woshiikun_1145.mcmod.choco.cstmm.api.QueueApi;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.config.MapConfig;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.MatchSession;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class QueueManager implements QueueApi {
    private static QueueManager instance;

    /** 匹配模式常量：由玩家加入队列时主动选择（空/非法值按 COMPETITIVE 兜底） */
    public static final String MODE_COMPETITIVE = "COMPETITIVE";
    public static final String MODE_CASUAL = "CASUAL";
    static final String[] MODES = {MODE_COMPETITIVE, MODE_CASUAL};
    static final String QUICK_MAP_ID = "quick";

    /** key: queueKey(mapId, mode)，即同一张地图按竞技/休闲各一条队列 */
    final Map<String, Map<Integer, List<UUID>>> queues;
    final Map<UUID, QueueEntry> playerQueueMap;
    // key: queueKey(mapId, mode), value: 上次开局失败提示时间（毫秒），10 秒内不重复发送
    private final Map<String, Long> queueFailNotifyTime;
    /** 快速匹配引擎：持有快速队列状态与匹配/合并/补位管线（自本类提取） */
    private final QuickMatchEngine engine;

    private QueueManager() {
        this.queues = new ConcurrentHashMap<>();
        this.playerQueueMap = new ConcurrentHashMap<>();
        this.queueFailNotifyTime = new ConcurrentHashMap<>();
        this.engine = new QuickMatchEngine(this);
    }

    public static QueueManager getInstance() {
        if (instance == null) {
            instance = new QueueManager();
        }
        return instance;
    }

    // ==================== 模式与复合键辅助 ====================

    /** 队列复合键：地图 ID + "@" + 模式 */
    static String queueKey(String mapId, String mode) {
        return mapId + "@" + mode;
    }

    static String mapIdOf(String key) {
        int idx = key.lastIndexOf('@');
        return idx < 0 ? key : key.substring(0, idx);
    }

    static String modeOf(String key) {
        int idx = key.lastIndexOf('@');
        return idx < 0 ? MODE_COMPETITIVE : key.substring(idx + 1);
    }

    /** 规范化模式：CASUAL（忽略大小写）按休闲处理，空/非法值一律按竞技兜底 */
    static String normalizeMode(String mode) {
        return MODE_CASUAL.equalsIgnoreCase(mode) ? MODE_CASUAL : MODE_COMPETITIVE;
    }

    static boolean isCompetitiveMode(String mode) {
        return !MODE_CASUAL.equals(mode);
    }

    static String modeDisplayName(String mode) {
        return isCompetitiveMode(mode) ? "竞技模式" : "休闲模式";
    }

    private ServerPlayerEntity getPlayer(UUID uuid) {
        MinecraftServer server = Cstmm.getServer();
        return server != null ? server.getPlayerManager().getPlayer(uuid) : null;
    }

    // ==================== API 实现 ====================

    @Override
    public QueueStatus getQueueStatus(String mapName) {
        // 复合键下按 mapName 聚合两种模式的人数合计，保持 API 语义不变
        int redCount = 0;
        int blueCount = 0;
        for (String mode : MODES) {
            Map<Integer, List<UUID>> teamMap = queues.get(queueKey(mapName, mode));
            if (teamMap == null) continue;
            redCount += teamMap.getOrDefault(1, Collections.emptyList()).size();
            blueCount += teamMap.getOrDefault(2, Collections.emptyList()).size();
        }
        MapConfig config = ConfigManager.getInstance().getMap(mapName);
        return new QueueStatus(mapName, redCount, blueCount, config != null && config.isEnabled());
    }

    @Override
    public List<QueueStatus> getAllQueueStatus() {
        List<QueueStatus> result = new ArrayList<>();
        for (MapConfig config : ConfigManager.getInstance().getMaps()) {
            result.add(getQueueStatus(config.getId()));
        }
        result.add(new QueueStatus(QUICK_MAP_ID, getQuickQueueSize(), 0, true));
        return result;
    }

    @Override
    public void joinQueue(UUID playerUuid, String mapName, int team) {
        // API/命令入口：固定按竞技模式加入
        joinQueue(playerUuid, mapName, team, MODE_COMPETITIVE);
    }

    private void joinQueue(UUID playerUuid, String mapName, int team, String mode) {
        ServerWorld world = getWorld();
        if (world == null) return;
        ServerPlayerEntity player = world.getServer().getPlayerManager().getPlayer(playerUuid);
        if (player == null) return;
        joinQueue(player, mapName, team, mode);
    }

    @Override
    public void leaveQueue(UUID playerUuid) {
        ServerWorld world = getWorld();
        if (world == null) return;
        ServerPlayerEntity player = world.getServer().getPlayerManager().getPlayer(playerUuid);
        if (player == null) return;
        leaveQueue(player);
    }

    @Override
    public boolean isInQueue(UUID playerUuid) {
        return playerQueueMap.containsKey(playerUuid) || engine.containsInQuickQueues(playerUuid);
    }

    // ==================== 队列操作 ====================

    public void joinQueue(ServerPlayerEntity player, String mapId, int preferredTeam) {
        joinQueue(player, mapId, preferredTeam, MODE_COMPETITIVE);
    }

    /** mode 为客户端 JOIN_QUEUE 传入的选择模式，空/非法值按竞技兜底 */
    public void joinQueue(ServerPlayerEntity player, String mapId, int preferredTeam, String mode) {
        UUID uuid = player.getUuid();
        String queueMode = normalizeMode(mode);

        if (playerQueueMap.containsKey(uuid)) {
            player.sendMessage(Text.literal("§c你已在队列或游戏中！使用 /cstmm match leave 离开"), false);
            return;
        }
        if (MatchManager.getInstance().isInGame(uuid)) {
            player.sendMessage(Text.literal("§c你正在游戏中，无法加入队列！"), false);
            return;
        }

        // 快速匹配是伪地图入口，必须在地图配置校验之前处理
        if (QUICK_MAP_ID.equals(mapId)) {
            joinQuickQueue(player, queueMode);
            return;
        }

        MapConfig config = ConfigManager.getInstance().getMap(mapId);
        if (config == null || !config.isEnabled()) {
            player.sendMessage(Text.literal("§c该地图未启用或不存在！"), false);
            return;
        }

        String key = queueKey(mapId, queueMode);
        queues.computeIfAbsent(key, k -> {
            Map<Integer, List<UUID>> teamMap = new HashMap<>();
            teamMap.put(1, new ArrayList<>());
            teamMap.put(2, new ArrayList<>());
            return teamMap;
        });

        // 修复：当红蓝人数相等时，随机分配，不再要求玩家手动选择
        int team = assignTeam(key, preferredTeam);
        if (team == 0) {
            player.sendMessage(Text.literal("§c该地图两队均已满员！"), false);
            return;
        }

        queues.get(key).get(team).add(uuid);
        playerQueueMap.put(uuid, new QueueEntry(mapId, queueMode, team));

        String teamName = team == 1 ? "红队" : "蓝队";
        player.sendMessage(Text.literal("§a你已加入 " + config.getDisplayName() + "（"
                + modeDisplayName(queueMode) + "） " + teamName + "！等待匹配中..."), false);
        Cstmm.LOGGER.debug("[CSTMM - QueueManager] {} joined {} {} queue as team {}", player.getName(), mapId, queueMode, team);
    }

    public void leaveQueue(ServerPlayerEntity player) {
        UUID uuid = player.getUuid();
        QueueEntry entry = playerQueueMap.remove(uuid);

        if (entry == null) {
            if (engine.removeFromQuickQueues(uuid)) {
                player.sendMessage(Text.literal("§e你已离开快速匹配队列！"), false);
                return;
            }
            player.sendMessage(Text.literal("§c你不在任何队列中！"), false);
            return;
        }

        // 快速匹配条目同时存在于快速队列和 playerQueueMap，必须一并清理，
        // 否则快速队列中会残留幽灵条目，断线/退出后仍被匹配拉走
        if (QUICK_MAP_ID.equals(entry.mapId)) {
            engine.removeFromQuickQueues(uuid);
            player.sendMessage(Text.literal("§e你已离开快速匹配队列！"), false);
            return;
        }

        Map<Integer, List<UUID>> teamMap = queues.get(queueKey(entry.mapId, entry.mode));
        if (teamMap != null) {
            teamMap.get(entry.team).remove(uuid);
        }

        player.sendMessage(Text.literal("§e你已离开 " + entry.mapId + " 队列！"), false);
        Cstmm.LOGGER.debug("[CSTMM - QueueManager] {} left queue", player.getName());
    }

    private void joinQuickQueue(ServerPlayerEntity player, String mode) {
        UUID uuid = player.getUuid();

        if (engine.quickQueueContains(mode, uuid)) {
            player.sendMessage(Text.literal("§c你已在快速匹配队列中！"), false);
            return;
        }

        engine.addQuickQueueMember(mode, uuid);
        // 修复：快速匹配不需要存储team，用特殊标识即可
        playerQueueMap.put(uuid, new QueueEntry(QUICK_MAP_ID, mode, -1));  // -1 表示快速匹配

        player.sendMessage(Text.literal("§a✅ 你已加入快速匹配队列，系统将自动分配地图。"), false);
        Cstmm.LOGGER.debug("[CSTMM - QueueManager] {} joined quick queue ({})", player.getName(), mode);
    }

    /**
     * 分配队伍 - 当红蓝人数相等时随机分配，不再要求玩家手动选择。
     * 若某队已达地图配置的最高人数（maxRedPlayers/maxBluePlayers，0 = 无上限），强制分配到另一队。
     */
    private int assignTeam(String key, int preferredTeam) {
        Map<Integer, List<UUID>> teamMap = queues.get(key);
        if (teamMap == null) return 0;

        int redSize = teamMap.get(1).size();
        int blueSize = teamMap.get(2).size();

        MapConfig config = ConfigManager.getInstance().getMap(mapIdOf(key));
        int redMax = config != null && config.getMaxRedPlayers() > 0 ? config.getMaxRedPlayers() : Integer.MAX_VALUE;
        int blueMax = config != null && config.getMaxBluePlayers() > 0 ? config.getMaxBluePlayers() : Integer.MAX_VALUE;

        // 某队已满员时强制进另一队；两队均满员返回 0（入队方拒绝）
        boolean redFull = redSize >= redMax;
        boolean blueFull = blueSize >= blueMax;
        if (redFull && blueFull) return 0;
        if (redFull) return 2;
        if (blueFull) return 1;

        // 有偏好且该队人数不多于对方，优先分配
        if (preferredTeam == 1 && redSize <= blueSize) return 1;
        if (preferredTeam == 2 && blueSize <= redSize) return 2;

        // 人数不相等时，分配到人少的一队
        if (redSize < blueSize) return 1;
        if (blueSize < redSize) return 2;

        // 红蓝人数相等 → 随机分配（修复点）
        return new Random().nextBoolean() ? 1 : 2;
    }

    // ==================== 匹配检测 ====================

    public void tryMatch() {
        engine.tryQuickMatch();

        for (String key : new ArrayList<>(queues.keySet())) {
            Map<Integer, List<UUID>> teamMap = queues.get(key);
            if (teamMap == null) continue;

            String mapId = mapIdOf(key);
            String mode = modeOf(key);

            List<UUID> redQueue = new ArrayList<>(teamMap.get(1));
            List<UUID> blueQueue = new ArrayList<>(teamMap.get(2));

            MapConfig config = ConfigManager.getInstance().getMap(mapId);
            if (config == null) continue;

            // 开局条件：红/蓝队列各自达到该队的最低人数（总人数即达两队最低人数之和）
            int minRed = config.getMinRedPlayers();
            int minBlue = config.getMinBluePlayers();

            if (redQueue.size() >= minRed && blueQueue.size() >= minBlue) {
                boolean mapInUse = MatchManager.getInstance().getAllActiveSessions().stream()
                        .anyMatch(s -> s.getMapName().equals(mapId) && s.getPhase() != MatchSession.GamePhase.ENDED);

                if (!mapInUse) {
                    startMatchFromQueue(mapId, mode, redQueue, blueQueue);
                }
            }
        }
    }

    private void startMatchFromQueue(String mapId, String mode, List<UUID> redQueue, List<UUID> blueQueue) {
        ServerWorld world = getWorld();
        if (world == null) return;

        MapConfig queueMapConfig = ConfigManager.getInstance().getMap(mapId);
        int minRed = queueMapConfig != null ? queueMapConfig.getMinRedPlayers() : 1;
        int minBlue = queueMapConfig != null ? queueMapConfig.getMinBluePlayers() : 1;
        int redMax = queueMapConfig != null && queueMapConfig.getMaxRedPlayers() > 0
                ? queueMapConfig.getMaxRedPlayers() : Integer.MAX_VALUE;
        int blueMax = queueMapConfig != null && queueMapConfig.getMaxBluePlayers() > 0
                ? queueMapConfig.getMaxBluePlayers() : Integer.MAX_VALUE;
        List<ServerPlayerEntity> redPlayers = new ArrayList<>();
        List<ServerPlayerEntity> bluePlayers = new ArrayList<>();

        // 先解析在线玩家，开局成功后才移出队列，避免开局失败导致玩家凭空脱离队列
        for (int i = 0; i < Math.min(minRed, redQueue.size()) && redPlayers.size() < redMax; i++) {
            ServerPlayerEntity player = world.getServer().getPlayerManager().getPlayer(redQueue.get(i));
            if (player != null) {
                redPlayers.add(player);
            }
        }
        for (int i = 0; i < Math.min(minBlue, blueQueue.size()) && bluePlayers.size() < blueMax; i++) {
            ServerPlayerEntity player = world.getServer().getPlayerManager().getPlayer(blueQueue.get(i));
            if (player != null) {
                bluePlayers.add(player);
            }
        }

        if (redPlayers.size() < minRed || bluePlayers.size() < minBlue) {
            Cstmm.LOGGER.warn("[CSTMM - QueueManager] Failed to start match: insufficient players");
            return;
        }

        if (!MatchManager.getInstance().startMatch(mapId, isCompetitiveMode(mode), redPlayers, bluePlayers)) {
            // 节流：同一（地图, 模式）队列 10 秒内不重复发送失败提示，避免刷屏
            String failKey = queueKey(mapId, mode);
            long now = System.currentTimeMillis();
            Long lastNotify = queueFailNotifyTime.get(failKey);
            if (lastNotify == null || now - lastNotify >= 10000) {
                queueFailNotifyTime.put(failKey, now);
                for (ServerPlayerEntity p : redPlayers) {
                    p.sendMessage(Text.literal("§c匹配失败（地图冷却中或不可用），你仍在队列中等待..."), false);
                }
                for (ServerPlayerEntity p : bluePlayers) {
                    p.sendMessage(Text.literal("§c匹配失败（地图冷却中或不可用），你仍在队列中等待..."), false);
                }
            }
            return;
        }

        String key = queueKey(mapId, mode);
        for (ServerPlayerEntity p : redPlayers) {
            queues.get(key).get(1).remove(p.getUuid());
            playerQueueMap.remove(p.getUuid());
        }
        for (ServerPlayerEntity p : bluePlayers) {
            queues.get(key).get(2).remove(p.getUuid());
            playerQueueMap.remove(p.getUuid());
        }

        // 先到先得：本图已被占用，解散该图所有队列（含另一模式），剩余玩家移出并提示
        dissolveQueuesForMap(mapId, mode);

        Cstmm.LOGGER.info("[CSTMM - QueueManager] Started {} match from queue: {}", mode, mapId);
    }

    /**
     * 地图成功开局后（先到先得）解散该图所有队列（两种模式，含同模式残余队列）：
     * 剩余玩家移出 queues/playerQueueMap 并收到模式开局提示。
     */
    void dissolveQueuesForMap(String mapId, String startedMode) {
        MapConfig config = ConfigManager.getInstance().getMap(mapId);
        String displayName = config != null ? config.getDisplayName() : mapId;
        String message = "§e地图「" + displayName + "」已以" + modeDisplayName(startedMode)
                + "开局，你已退出匹配队列";

        for (String mode : MODES) {
            Map<Integer, List<UUID>> teamMap = queues.remove(queueKey(mapId, mode));
            if (teamMap == null) continue;

            int dissolved = 0;
            for (List<UUID> teamList : teamMap.values()) {
                for (UUID uuid : teamList) {
                    // 已被本局带走的玩家（尚未从列表清理）不重复提示
                    if (playerQueueMap.remove(uuid) == null) continue;
                    dissolved++;
                    ServerPlayerEntity player = getPlayer(uuid);
                    if (player != null) {
                        player.sendMessage(Text.literal(message), false);
                    }
                }
            }
            Cstmm.LOGGER.info("[CSTMM - QueueManager] Dissolved {} queue for map {} ({} players, match started as {})",
                    mode, mapId, dissolved, startedMode);
        }
    }

    /** 两条快速队列人数合计 */
    public int getQuickQueueSize() {
        return engine.getQuickQueueSize();
    }

    static ServerWorld getWorld() {
        MinecraftServer server = Cstmm.getServer();
        if (server != null) {
            return server.getWorld(ServerWorld.OVERWORLD);
        }
        return null;
    }

    // ==================== 内部类 ====================

    private static class QueueEntry {
        String mapId;
        String mode;  // 匹配模式（快速匹配为所在快速队列的模式）
        int team;     // -1 表示快速匹配

        QueueEntry(String mapId, String mode, int team) {
            this.mapId = mapId;
            this.mode = mode;
            this.team = team;
        }
    }
}
