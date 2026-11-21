package cn.woshiikun_1145.mcmod.choco.cstmm.manager;

import cn.woshiikun_1145.mcmod.choco.cstmm.Cstmm;
import cn.woshiikun_1145.mcmod.choco.cstmm.api.QueueApi;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.Clan;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.config.MapConfig;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.MatchSession;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.PlayerProfile;
import cn.woshiikun_1145.mcmod.choco.cstmm.network.NetworkHandler;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 【作用】服务端匹配队列管理器：维护按"地图+模式"划分的地图队列与按模式划分的快速匹配队列，处理入队/退队、每秒匹配检测、开局分队与队列解散，并向客户端推送队列状态快照 JSON。
 * 【被谁使用】MatchScheduler#onSecondTick（每秒 tryMatch）、NetworkHandler（JOIN_QUEUE/JOIN_QUICK/LEAVE_QUEUE/队列状态请求/战队页）、ModCommands（queue 命令）、EventListener（断线退队）、QuickMatchEngine（回引用访问队列与玩家映射）；同时作为 QueueApi 的实现供外部 API 调用。均为服务端。
 */
public class QueueManager implements QueueApi {
    private static QueueManager instance;

    /** 匹配模式常量：由玩家加入队列时主动选择（空/非法值按 COMPETITIVE 兜底） */
    public static final String MODE_COMPETITIVE = "COMPETITIVE";
    public static final String MODE_CASUAL = "CASUAL";
    static final String[] MODES = {MODE_COMPETITIVE, MODE_CASUAL};
    static final String QUICK_MAP_ID = "quick";

    /** key: queueKey(mapId, mode)，即同一张地图按竞技/休闲各一条队列；
     *  队列内为单一无队伍名单——队伍只在人数达到开局条件、准备开局时才分配（与快速匹配同规则） */
    final Map<String, List<UUID>> queues;
    final Map<UUID, QueueEntry> playerQueueMap;
    // key: queueKey(mapId, mode), value: 上次开局失败提示时间（毫秒），10 秒内不重复发送
    private final Map<String, Long> queueFailNotifyTime;
    /** 进行中的开局投票（key: queueKey(mapId, mode)）：人满后发起"是否立刻开局"投票 */
    private final Map<String, QueueStartVote> startVotes = new ConcurrentHashMap<>();
    /** 开局检查计数器：人满检测每 10 秒执行一次（开局投票则每秒推进） */
    private int startCheckCounter = 0;
    /** 快速匹配引擎：持有快速队列状态与匹配/合并/补位管线（自本类提取） */
    private final QuickMatchEngine engine;

    private QueueManager() {
        this.queues = new ConcurrentHashMap<>();
        this.playerQueueMap = new ConcurrentHashMap<>();
        this.queueFailNotifyTime = new ConcurrentHashMap<>();
        this.engine = new QuickMatchEngine(this);
    }

    /**
     * 开局投票状态（队列人满后发起）：投票资格 = 发起时在队的在线玩家；
     * 过半同意 → 立即开局；否决/超时 → 保持队列等待，下次 10 秒检查时重新发起。
     */
    private static final class QueueStartVote {
        final String mapId;
        final String mode;
        final Set<UUID> eligible;
        final Set<UUID> voted = new HashSet<>();
        int yesVotes = 0;
        int requiredYes;
        int remainingSeconds = 30;

        QueueStartVote(String mapId, String mode, Set<UUID> eligible) {
            this.mapId = mapId;
            this.mode = mode;
            this.eligible = eligible;
            this.requiredYes = eligible.size() / 2 + 1;
        }
    }

    // 单例获取（服务端调度器/网络层/命令/监听器与 QuickMatchEngine 共用）
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

    // 按 UUID 查找在线玩家实体（服务端内部工具方法）
    private ServerPlayerEntity getPlayer(UUID uuid) {
        MinecraftServer server = Cstmm.getServer();
        return server != null ? server.getPlayerManager().getPlayer(uuid) : null;
    }

    // ==================== API 实现 ====================

    /**
     * 【作用】查询单张地图的队列状态（两种模式排队总人数、地图是否启用）。
     * 【被谁使用】QueueApi 接口实现（外部 API 入口）；内部由 getAllQueueStatus 聚合调用；服务端。
     */
    @Override
    public QueueStatus getQueueStatus(String mapName) {
        // 队伍在开局时才分配：按 mapName 聚合两种模式的排队总人数
        int count = 0;
        for (String mode : MODES) {
            List<UUID> list = queues.get(queueKey(mapName, mode));
            if (list != null) count += list.size();
        }
        MapConfig config = ConfigManager.getInstance().getMap(mapName);
        return new QueueStatus(mapName, count, config != null && config.isEnabled());
    }

    /**
     * 【作用】汇总全部地图与快速匹配的队列状态列表。
     * 【被谁使用】QueueApi 接口实现（外部 API 入口，项目内暂无其他调用方）；服务端。
     */
    @Override
    public List<QueueStatus> getAllQueueStatus() {
        List<QueueStatus> result = new ArrayList<>();
        for (MapConfig config : ConfigManager.getInstance().getMaps()) {
            result.add(getQueueStatus(config.getId()));
        }
        result.add(new QueueStatus(QUICK_MAP_ID, getQuickQueueSize(), true));
        return result;
    }

    /**
     * 【作用】按 UUID 加入指定地图队列（QueueApi 入口，固定按竞技模式处理）。
     * 【被谁使用】QueueApi 接口实现（外部 API 入口，项目内暂无直接调用方，实际入队走 ServerPlayerEntity 重载）；服务端。
     */
    @Override
    public void joinQueue(UUID playerUuid, String mapName, int team) {
        // API/命令入口：固定按竞技模式加入
        joinQueue(playerUuid, mapName, team, MODE_COMPETITIVE);
    }

    // 重载：按 UUID 解析玩家实体后走实体入队（服务端内部）
    private void joinQueue(UUID playerUuid, String mapName, int team, String mode) {
        ServerWorld world = getWorld();
        if (world == null) return;
        ServerPlayerEntity player = world.getServer().getPlayerManager().getPlayer(playerUuid);
        if (player == null) return;
        joinQueue(player, mapName, team, mode);
    }

    /**
     * 【作用】按 UUID 将玩家移出所在队列（QueueApi 入口）。
     * 【被谁使用】QueueApi 接口实现（外部 API 入口，项目内暂无直接调用方，实际退队走 ServerPlayerEntity 重载）；服务端。
     */
    @Override
    public void leaveQueue(UUID playerUuid) {
        ServerWorld world = getWorld();
        if (world == null) return;
        ServerPlayerEntity player = world.getServer().getPlayerManager().getPlayer(playerUuid);
        if (player == null) return;
        leaveQueue(player);
    }

    /**
     * 【作用】判断玩家是否在任一队列中（地图队列或快速匹配队列）。
     * 【被谁使用】ModCommands queue 命令（状态提示）；QueueApi 接口实现（外部 API 入口）；服务端。
     */
    @Override
    public boolean isInQueue(UUID playerUuid) {
        return playerQueueMap.containsKey(playerUuid) || engine.containsInQuickQueues(playerUuid);
    }

    // ==================== 队列操作 ====================

    // 兼容旧签名：固定按竞技模式入队（项目内当前无调用方，实际入口为下方 4 参版本）
    public void joinQueue(ServerPlayerEntity player, String mapId, int preferredTeam) {
        joinQueue(player, mapId, preferredTeam, MODE_COMPETITIVE);
    }

    /** mode 为客户端 JOIN_QUEUE 传入的选择模式，空/非法值按竞技兜底；preferredTeam 已废弃（队伍在开局时自动分配）
     * 【被谁使用】NetworkHandler JOIN_QUEUE 动作、ModCommands /cstmm queue join 命令；服务端。
     */
    public void joinQueue(ServerPlayerEntity player, String mapId, int preferredTeam, String mode) {
        UUID uuid = player.getUuid();
        String queueMode = normalizeMode(mode);

        if (playerQueueMap.containsKey(uuid)) {
            // Toast 提示（不打断操作；配合客户端"已在队列时再次加入不关闭界面"）
            NetworkHandler.sendToast(player, "§c你已在队列或游戏中！使用 /cstmm queue leave 离开");
            return;
        }
        if (MatchManager.getInstance().isInGame(uuid)) {
            player.sendMessage(Text.literal("§c你正在游戏中，无法加入队列！"), false);
            return;
        }

        // 快速匹配走专用 JOIN_QUICK 动作（joinQuickQueue），
        // 此处 mapId 一律视为真实地图 ID，不再对 "quick" 字符串做特殊处理

        MapConfig config = ConfigManager.getInstance().getMap(mapId);
        if (config == null || !config.isEnabled()) {
            player.sendMessage(Text.literal("§c该地图未启用或不存在！"), false);
            return;
        }

        String key = queueKey(mapId, queueMode);
        List<UUID> list = queues.computeIfAbsent(key, k -> new ArrayList<>());

        // 容量检查（0 = 无上限）：排队人数达到两队最高人数之和后拒绝入队
        // （队伍开局时才分配，原来"按队检查满员"的口径合并为队列总容量）
        long redMax = config.getMaxRedPlayers() > 0 ? config.getMaxRedPlayers() : Long.MAX_VALUE / 4;
        long blueMax = config.getMaxBluePlayers() > 0 ? config.getMaxBluePlayers() : Long.MAX_VALUE / 4;
        if (list.size() >= redMax + blueMax) {
            player.sendMessage(Text.literal("§c该地图匹配人数已满！"), false);
            return;
        }

        list.add(uuid);
        // team=0：地图队列入队时不分配队伍，开局分队后直接进入对局
        playerQueueMap.put(uuid, new QueueEntry(mapId, queueMode, 0));

        // 队伍在人数满足开局条件时由系统自动平衡分配（战队尽量同队），入队时不定队
        player.sendMessage(Text.literal("§a你已加入 " + config.getDisplayName() + "（"
                + modeDisplayName(queueMode) + "）匹配队列！人数满足后自动开局。"), false);
        Cstmm.LOGGER.debug("[CSTMM - QueueManager] {} joined {} {} queue", player.getName(), mapId, queueMode);
        onQueueChanged();
    }

    /**
     * 【作用】将玩家退出所在队列：地图队列移除名单、快速队列清理条目，同步清理玩家-队列映射后推送队列变化。
     * 【被谁使用】NetworkHandler LEAVE_QUEUE 动作、ModCommands /cstmm queue leave 命令、EventListener 玩家断开连接；服务端。
     */
    public void leaveQueue(ServerPlayerEntity player) {
        UUID uuid = player.getUuid();
        QueueEntry entry = playerQueueMap.remove(uuid);

        if (entry == null) {
            if (engine.removeFromQuickQueues(uuid)) {
                NetworkHandler.sendToast(player, "§e你已离开快速匹配队列！");
                return;
            }
            player.sendMessage(Text.literal("§c你不在任何队列中！"), false);
            return;
        }

        // 快速匹配条目（team == -1 标记）同时存在于快速队列和 playerQueueMap，必须一并清理，
        // 否则快速队列中会残留幽灵条目，断线/退出后仍被匹配拉走。
        // 用 team 标记而非 mapId 字符串判断，真实地图 ID 恰为 "quick" 时不会被误判
        if (entry.team == -1) {
            engine.removeFromQuickQueues(uuid);
            NetworkHandler.sendToast(player, "§e你已离开快速匹配队列！");
            onQueueChanged();
            return;
        }

        List<UUID> list = queues.get(queueKey(entry.mapId, entry.mode));
        if (list != null) {
            list.remove(uuid);
        }

        NetworkHandler.sendToast(player, "§e你已离开 " + entry.mapId + " 队列！");
        Cstmm.LOGGER.debug("[CSTMM - QueueManager] {} left queue", player.getName());
        onQueueChanged();
    }

    /** 加入快速匹配队列（网络入口 JOIN_QUICK；mode 空/非法按竞技兜底）
     * 【被谁使用】NetworkHandler JOIN_QUICK 动作、ModCommands /cstmm queue quick 命令；服务端。
     */
    public void joinQuickQueue(ServerPlayerEntity player, String mode) {
        UUID uuid = player.getUuid();
        // 规范化后入队与存条目，保证 QueueEntry.mode 与实际所在快速队列一致
        // （否则状态快照/战队页按原始字符串展示的模式可能与实际队列不符）
        String queueMode = normalizeMode(mode);

        // 玩家同时只能加入一个匹配队列：已在任意队列（地图队列或快速队列）时拒绝。
        // 缺少此拦截会导致：playerQueueMap 条目被覆盖为快速匹配，而地图队列名单仍残留该玩家，
        // 同一个人被两路队列各计一次（双倍计数），甚至可能被两边先后拉进对局
        if (playerQueueMap.containsKey(uuid)) {
            // Toast 提示（不打断操作；配合客户端"已在队列时再次加入不关闭界面"）
            NetworkHandler.sendToast(player, "§c你已在队列或游戏中！使用 /cstmm queue leave 离开");
            return;
        }
        if (MatchManager.getInstance().isInGame(uuid)) {
            player.sendMessage(Text.literal("§c你正在游戏中，无法加入队列！"), false);
            return;
        }

        if (engine.quickQueueContains(queueMode, uuid)) {
            player.sendMessage(Text.literal("§c你已在快速匹配队列中！"), false);
            return;
        }

        engine.addQuickQueueMember(queueMode, uuid);
        // 修复：快速匹配不需要存储team，用特殊标识即可
        playerQueueMap.put(uuid, new QueueEntry(QUICK_MAP_ID, queueMode, -1));  // -1 表示快速匹配

        player.sendMessage(Text.literal("§a你已加入快速匹配队列，系统将自动分配地图。"), false);
        Cstmm.LOGGER.debug("[CSTMM - QueueManager] {} joined quick queue ({})", player.getName(), queueMode);
        onQueueChanged();
    }

    // ==================== 匹配检测 ====================

    /**
     * 【作用】每秒匹配检测：先推进开局投票（每秒），再每 10 秒检查一次地图队列——
     *         人数达到开局条件且地图空闲时发起"是否立刻开局"投票（不再立刻开局）。
     * 【被谁使用】MatchScheduler#onSecondTick（服务端每秒调用一次）。
     */
    public void tryMatch() {
        engine.tryQuickMatch();
        tickStartVotes();

        // 【修改】人满检测每 10 秒一次：人满后发起投票决定是否立刻开局
        if (++startCheckCounter < 10) return;
        startCheckCounter = 0;

        for (String key : new ArrayList<>(queues.keySet())) {
            List<UUID> queued = queues.get(key);
            if (queued == null || queued.isEmpty()) continue;

            String mapId = mapIdOf(key);
            String mode = modeOf(key);

            MapConfig config = ConfigManager.getInstance().getMap(mapId);
            if (config == null) continue;

            // 开局条件：该模式队列总人数达到两队最低人数之和（队伍在此时才分配）
            if (queued.size() >= config.getTotalMinPlayers()) {
                boolean mapInUse = MatchManager.getInstance().getAllActiveSessions().stream()
                        .anyMatch(s -> s.getMapName().equals(mapId) && s.getPhase() != MatchSession.GamePhase.ENDED);

                if (!mapInUse && !startVotes.containsKey(key)) {
                    startMatchVote(key, mapId, mode, config, queued);
                }
            }
        }
    }

    // ==================== 开局投票（人满后发起，F7 同意 / F8 反对） ====================

    /**
     * 【作用】发起开局投票：向队列中在线玩家广播投票提示（30 秒，同意过半则立刻开局）。
     *         投票资格 = 发起时在队的在线玩家；否决/超时后队列保留，下次 10 秒检查重新发起。
     * 【被谁使用】tryMatch（人满且地图空闲时）。仅服务端。
     */
    private void startMatchVote(String key, String mapId, String mode, MapConfig config, List<UUID> queued) {
        Set<UUID> online = new HashSet<>();
        for (UUID uuid : queued) {
            ServerPlayerEntity player = Cstmm.getServer() != null
                    ? Cstmm.getServer().getPlayerManager().getPlayer(uuid) : null;
            if (player != null) online.add(uuid);
        }
        if (online.isEmpty()) return;

        QueueStartVote vote = new QueueStartVote(mapId, mode, online);
        startVotes.put(key, vote);

        String message = "§6=== 开局投票 ===\n"
                + "§e地图「" + config.getDisplayName() + "」(" + modeDisplayName(mode) + ") 已人满，是否立刻开局？\n"
                + "§a需要 " + vote.requiredYes + " 票同意 (过半)\n"
                + "§7按 §aF7 §7同意  |  按 §cF8 §7反对  |  剩余 30 秒";
        broadcastStartVote(vote, message);
        Cstmm.LOGGER.info("[CSTMM - QueueManager] Start vote initiated for queue {} ({} online, need {} yes)",
                key, online.size(), vote.requiredYes);
    }

    /**
     * 【作用】开局投票每秒推进：超时按否决处理；提前通过（同意过半）立即开局；
     *         提前失败（剩余可投数不足以过半）立即结束等待下次检查重发。
     * 【被谁使用】tryMatch（每秒）。仅服务端。
     */
    private void tickStartVotes() {
        for (String key : new ArrayList<>(startVotes.keySet())) {
            QueueStartVote vote = startVotes.get(key);
            if (vote == null) continue;
            vote.remainingSeconds--;
            if (vote.remainingSeconds <= 0) {
                concludeStartVote(key, vote, false);
            } else if (vote.yesVotes >= vote.requiredYes) {
                concludeStartVote(key, vote, true);
            } else if (vote.yesVotes + countNotVotedOnline(vote) < vote.requiredYes) {
                concludeStartVote(key, vote, false);
            }
        }
    }

    /**
     * 【作用】接收开局投票（F7/F8）：玩家不在任何对局中时按其所在队列的开局投票计票。
     *         同意过半立即开局；否决至无法过半立即失败。不在投票中的玩家静默忽略。
     * 【被谁使用】NetworkHandler 的 VOTE_YES/VOTE_NO 分支（无对局时回落）。仅服务端。
     */
    public void castStartVote(UUID uuid, boolean agree) {
        for (String key : new ArrayList<>(startVotes.keySet())) {
            QueueStartVote vote = startVotes.get(key);
            if (vote == null || !vote.eligible.contains(uuid) || vote.voted.contains(uuid)) continue;
            vote.voted.add(uuid);
            if (agree) vote.yesVotes++;
            broadcastStartVote(vote, "§7投票进度: §a" + vote.yesVotes + " §7同意  |  需要 §a"
                    + vote.requiredYes + " §7票同意");
            if (vote.yesVotes >= vote.requiredYes) {
                concludeStartVote(key, vote, true);
            } else if (vote.yesVotes + countNotVotedOnline(vote) < vote.requiredYes) {
                concludeStartVote(key, vote, false);
            }
            return;
        }
    }

    /** 开局投票结果：通过 → 立即按当前队列开局；否决/超时 → 保留队列，10 秒后重新检查。 */
    private void concludeStartVote(String key, QueueStartVote vote, boolean passed) {
        startVotes.remove(key);
        broadcastStartVote(vote, passed
                ? "§a开局投票通过！正在开始比赛..."
                : "§e开局投票未通过，匹配将继续等待（稍后重新发起投票）");
        if (passed) {
            List<UUID> queued = queues.get(key);
            if (queued != null && !queued.isEmpty()) {
                startMatchFromQueue(vote.mapId, vote.mode, queued);
            }
        }
        Cstmm.LOGGER.info("[CSTMM - QueueManager] Start vote for queue {}: {} ({} yes / {} eligible)",
                key, passed ? "PASSED" : "FAILED", vote.yesVotes, vote.eligible.size());
    }

    /** 向投票资格玩家广播投票消息（VOTE_STARTED 通道，客户端与对局投票共用 F7/F8 提示 UI）。 */
    private void broadcastStartVote(QueueStartVote vote, String message) {
        MinecraftServer server = Cstmm.getServer();
        if (server == null) return;
        for (UUID uuid : vote.eligible) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
            if (player != null) {
                NetworkHandler.sendMatchStatus(player, new cn.woshiikun_1145.mcmod.choco.cstmm.network.payload.MatchPayloads.MatchStatusPayload(
                        cn.woshiikun_1145.mcmod.choco.cstmm.network.payload.MatchPayloads.MatchStatusPayload.StatusType.VOTE_STARTED,
                        message, 0, 0));
            }
        }
    }

    /** 统计投票资格中在线且尚未投票的人数（提前判负用）。 */
    private int countNotVotedOnline(QueueStartVote vote) {
        MinecraftServer server = Cstmm.getServer();
        if (server == null) return 0;
        int count = 0;
        for (UUID uuid : vote.eligible) {
            if (vote.voted.contains(uuid)) continue;
            if (server.getPlayerManager().getPlayer(uuid) != null) count++;
        }
        return count;
    }

    /** 地图队列开局：人数达到两队最低之和时触发——此时才分配队伍（与快速匹配同规则）
     * 【被谁使用】tryMatch（服务端内部，队列人数达标且地图空闲时调用）。
     */
    private void startMatchFromQueue(String mapId, String mode, List<UUID> queued) {
        ServerWorld world = getWorld();
        if (world == null) return;

        MapConfig config = ConfigManager.getInstance().getMap(mapId);
        if (config == null) return;
        int minRed = config.getMinRedPlayers();
        int minBlue = config.getMinBluePlayers();

        // 只取满足开局所需的两队最低人数之和（按队列顺序、跳过离线玩家，与旧逻辑一致）；
        // 多余玩家在开局成功后由 dissolveQueuesForMap 解散并提示
        int take = config.getTotalMinPlayers();
        List<UUID> taken = new ArrayList<>();
        for (UUID uuid : queued) {
            if (taken.size() >= take) break;
            if (world.getServer().getPlayerManager().getPlayer(uuid) != null) {
                taken.add(uuid);
            }
        }

        // 开局时才分配队伍：随机打乱 + 战队聚组 + 平衡分队 + 最低人数修正（离线玩家在分队时被丢弃）
        List<ServerPlayerEntity> redPlayers = new ArrayList<>();
        List<ServerPlayerEntity> bluePlayers = new ArrayList<>();
        engine.splitIntoTeams(config, taken, world, redPlayers, bluePlayers);

        if (redPlayers.size() < minRed || bluePlayers.size() < minBlue) {
            Cstmm.LOGGER.warn("[CSTMM - QueueManager] Failed to start match: insufficient players");
            return;
        }

        // 【作用】调用对局管理器开局；失败（地图冷却/占用等）时节流提示，玩家保留在队列中
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
            queues.get(key).remove(p.getUuid());
            playerQueueMap.remove(p.getUuid());
        }
        for (ServerPlayerEntity p : bluePlayers) {
            queues.get(key).remove(p.getUuid());
            playerQueueMap.remove(p.getUuid());
        }

        // 先到先得：本图已被占用，解散该图所有队列（含另一模式），剩余玩家移出并提示
        dissolveQueuesForMap(mapId, mode);

        Cstmm.LOGGER.info("[CSTMM - QueueManager] Started {} match from queue: {}", mode, mapId);
    }

    /**
     * 地图成功开局后（先到先得）解散该图所有队列（两种模式，含同模式残余队列）：
     * 剩余玩家移出 queues/playerQueueMap 并收到模式开局提示。
     * 【被谁使用】startMatchFromQueue（内部）、QuickMatchEngine#startQuickMatch（服务端）。
     */
    void dissolveQueuesForMap(String mapId, String startedMode) {
        MapConfig config = ConfigManager.getInstance().getMap(mapId);
        String displayName = config != null ? config.getDisplayName() : mapId;
        String message = "§e地图「" + displayName + "」已以" + modeDisplayName(startedMode)
                + "开局，你已退出匹配队列";

        for (String mode : MODES) {
            List<UUID> list = queues.remove(queueKey(mapId, mode));
            if (list == null) continue;

            int dissolved = 0;
            for (UUID uuid : list) {
                // 已被本局带走的玩家（尚未从列表清理）不重复提示
                if (playerQueueMap.remove(uuid) == null) continue;
                dissolved++;
                ServerPlayerEntity player = getPlayer(uuid);
                if (player != null) {
                    player.sendMessage(Text.literal(message), false);
                }
            }
            Cstmm.LOGGER.info("[CSTMM - QueueManager] Dissolved {} queue for map {} ({} players, match started as {})",
                    mode, mapId, dissolved, startedMode);
        }
        onQueueChanged();
    }

    /** 两条快速队列人数合计
     * 【被谁使用】getAllQueueStatus（队列页汇总）、ModCommands queue 命令；服务端。
     */
    public int getQuickQueueSize() {
        return engine.getQuickQueueSize();
    }

    // ==================== 队列变化推送 ====================

    /** 队列发生变化（加入/退出/开局/解散/补位）：向订阅了队列状态的玩家推送最新快照
     * 【被谁使用】joinQueue/#leaveQueue/#joinQuickQueue/#startMatchFromQueue/#dissolveQueuesForMap（内部）、QuickMatchEngine#reinforceInto；服务端。
     */
    void onQueueChanged() {
        NetworkHandler.pushQueueStatusToSubscribers();
    }

    // ==================== 队列变化推送结束 ====================

    // ==================== 队列状态快照（队列标签页） ====================

    // 队列状态 JSON 序列化器（服务端内部使用）
    private static final Gson QUEUE_STATUS_GSON = new Gson();
    /** 每张地图在状态包中的玩家名上限（防止大队列撑爆 65536B 单包） */
    private static final int MAX_PLAYERS_PER_MAP = 40;

    /**
     * 构建队列状态快照 JSON（"队列"标签页数据源，每秒由客户端请求）：
     * own = 自己的队列状态；clan = 自己战队的匹配状态；maps = 每张地图的队列状态。
     * 地图显示名/类型/图片由客户端从 ConfigDataCache 按 id 解析，此处不重复下发。
     * 【被谁使用】NetworkHandler 队列状态请求与订阅推送（服务端，客户端"队列"标签页每秒拉取）。
     */
    public String buildQueueStatusJson(ServerPlayerEntity viewer) {
        JsonObject root = new JsonObject();

        // ===== own：自己的匹配状态 =====
        JsonObject own = new JsonObject();
        QueueEntry entry = playerQueueMap.get(viewer.getUuid());
        own.addProperty("inQueue", entry != null);
        // 自己的头像绑定（队列页"战队徽标右侧"展示；读档案 avatarType/avatarId 字段，
        // 只下发绑定，图片由客户端自行获取；未绑定为空串）
        PlayerProfile ownProfile = PlayerDataManager.getInstance().getProfile(viewer.getUuid());
        own.addProperty("avatarType", ownProfile == null ? "" : ownProfile.getAvatarType());
        own.addProperty("avatarId", ownProfile == null ? "" : ownProfile.getAvatarId());
        if (entry != null) {
            own.addProperty("mode", modeDisplayName(entry.mode));
            if (entry.team == -1) {
                // 快速匹配：已匹配 = 本模式快速队列人数；needed = -1（无固定门槛，等待开局/补位）
                own.addProperty("map", "快速匹配");
                own.addProperty("mapId", "quick");
                own.addProperty("matched", engine.quickQueueSizeOf(entry.mode));
                own.addProperty("needed", -1);
            } else {
                MapConfig cfg = ConfigManager.getInstance().getMap(entry.mapId);
                own.addProperty("map", cfg != null ? cfg.getDisplayName() : entry.mapId);
                own.addProperty("mapId", entry.mapId);
                List<UUID> list = queues.get(queueKey(entry.mapId, entry.mode));
                int matched = list == null ? 0 : list.size();
                own.addProperty("matched", matched);
                int needed = cfg != null ? Math.max(0, cfg.getTotalMinPlayers() - matched) : 0;
                own.addProperty("needed", needed);
            }
        }
        root.add("own", own);

        // ===== clan：自己战队的匹配状态 =====
        Clan clan = ClanManager.getInstance().getClanByPlayer(viewer.getUuid());
        JsonObject clanJson = new JsonObject();
        clanJson.addProperty("inClan", clan != null);
        if (clan != null) {
            clanJson.addProperty("name", clan.getName());
            clanJson.addProperty("abbr", clan.getAbbreviation());
            int count = 0;
            JsonArray members = new JsonArray();
            for (Map.Entry<UUID, QueueEntry> e : playerQueueMap.entrySet()) {
                if (ClanManager.getInstance().getClanByPlayer(e.getKey()) != clan) continue;
                count++;
                JsonObject m = new JsonObject();
                m.addProperty("player", resolveName(e.getKey()));
                QueueEntry qe = e.getValue();
                if (qe.team == -1) {
                    m.addProperty("map", "快速匹配");
                } else {
                    MapConfig c = ConfigManager.getInstance().getMap(qe.mapId);
                    m.addProperty("map", c != null ? c.getDisplayName() : qe.mapId);
                }
                members.add(m);
            }
            clanJson.addProperty("count", count);
            clanJson.add("members", members);
        }
        root.add("clan", clanJson);

        // ===== maps：每张地图的队列状态（蓝红人数/玩家列表按竞技、休闲两模式分别下发） =====
        JsonArray maps = new JsonArray();
        for (MapConfig cfg : ConfigManager.getInstance().getMaps()) {
            JsonObject mo = new JsonObject();
            mo.addProperty("id", cfg.getId());
            String status;
            if (!cfg.isEnabled()) {
                status = "DISABLED";
            } else if (isMapInActiveMatch(cfg.getId())) {
                status = "IN_MATCH";
            } else if (MatchManager.getInstance().isMapInCooldown(cfg.getId())) {
                status = "COOLDOWN";
            } else {
                status = "AVAILABLE";
            }
            mo.addProperty("status", status);
            JsonArray modes = new JsonArray();
            for (String mode : MODES) {
                JsonObject modeJson = new JsonObject();
                modeJson.addProperty("mode", modeDisplayName(mode));
                // 队伍开局时才分配：队列无蓝红之分，只下发排队总人数与玩家列表
                List<UUID> list = queues.get(queueKey(cfg.getId(), mode));
                modeJson.addProperty("count", list == null ? 0 : list.size());
                JsonArray players = new JsonArray();
                if (list != null) {
                    for (UUID u : list) addPlayerName(players, u);
                }
                modeJson.add("players", players);
                modes.add(modeJson);
            }
            mo.add("modes", modes);
            maps.add(mo);
        }
        root.add("maps", maps);
        return QUEUE_STATUS_GSON.toJson(root);
    }

    // 判断地图当前是否有未结束的对局（队列页状态用）
    private boolean isMapInActiveMatch(String mapId) {
        return MatchManager.getInstance().getAllActiveSessions().stream()
                .anyMatch(s -> s.getMapName().equals(mapId) && s.getPhase() != MatchSession.GamePhase.ENDED);
    }

    // 追加玩家名到状态数组（超过单图上限后忽略，防止包体超限）
    private void addPlayerName(JsonArray players, UUID uuid) {
        if (players.size() >= MAX_PLAYERS_PER_MAP) return;
        players.add(resolveName(uuid));
    }

    // 解析玩家显示名（离线玩家回退为 UUID 前 8 位）
    private String resolveName(UUID uuid) {
        MinecraftServer server = Cstmm.getServer();
        ServerPlayerEntity p = server == null ? null : server.getPlayerManager().getPlayer(uuid);
        return p != null ? p.getName().getString() : uuid.toString().substring(0, 8);
    }

    /**
     * 玩家当前匹配状态描述（战队页成员列表用）：空闲返回 ""，
     * 正在匹配返回 "地图显示名-模式名"（快速匹配为 "快速匹配-模式名"）。
     * 【被谁使用】NetworkHandler 战队页成员列表构建（服务端）。
     */
    public String matchStateOf(UUID uuid) {
        QueueEntry e = playerQueueMap.get(uuid);
        if (e == null) return "";
        if (e.team == -1) return "快速匹配-" + modeDisplayName(e.mode);
        MapConfig cfg = ConfigManager.getInstance().getMap(e.mapId);
        return (cfg != null ? cfg.getDisplayName() : e.mapId) + "-" + modeDisplayName(e.mode);
    }

    /**
     * 【作用】获取主世界引用（仅用于借 world.getServer() 访问服务器/玩家管理器，与对局维度无关）。
     * 【被谁使用】joinQueue/#leaveQueue/#startMatchFromQueue（内部）、QuickMatchEngine 各入口；服务端。
     */
    static ServerWorld getWorld() {
        MinecraftServer server = Cstmm.getServer();
        if (server != null) {
            return server.getWorld(ServerWorld.OVERWORLD);
        }
        return null;
    }

    // ==================== 内部类 ====================

    /**
     * 【作用】玩家排队条目：记录所排队列的地图 ID、匹配模式与队伍标记（-1 快速匹配 / 0 地图队列未分队）。
     * 【被谁使用】QueueManager 队列映射与 QuickMatchEngine（服务端）。
     */
    private static class QueueEntry {
        String mapId;
        String mode;  // 匹配模式（快速匹配为所在快速队列的模式）
        int team;     // -1 表示快速匹配；0 = 地图队列（未分配队伍，开局时才分队）

        QueueEntry(String mapId, String mode, int team) {
            this.mapId = mapId;
            this.mode = mode;
            this.team = team;
        }
    }
}
