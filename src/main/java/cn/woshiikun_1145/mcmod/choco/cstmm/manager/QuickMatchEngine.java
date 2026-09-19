package cn.woshiikun_1145.mcmod.choco.cstmm.manager;

import cn.woshiikun_1145.mcmod.choco.cstmm.Cstmm;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.Clan;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.config.GlobalConfig;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.config.MapConfig;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.MatchSession;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 快速匹配引擎（自 QueueManager 簇 F 提取，逻辑逐字保留）。
 * 持有按模式独立的快速队列状态，通过回引用访问 QueueManager 的地图队列与玩家映射。
 * 匹配管线保持：直接开局 → 跨队列合并 → 超时补位，绝不混合两种模式。
 */
final class QuickMatchEngine {
    private final QueueManager qm;
    /** 快速匹配队列，按模式独立：key: 匹配模式 */
    private final Map<String, List<UUID>> quickQueues;
    /** 快速匹配等待计数（tryQuickMatch 每秒调用一次，实为秒数；与 quickTimeout 秒口径一致） */
    private int quickWaitSeconds;

    QuickMatchEngine(QueueManager qm) {
        this.qm = qm;
        this.quickQueues = new HashMap<>();
        this.quickQueues.put(QueueManager.MODE_COMPETITIVE, new ArrayList<>());
        this.quickQueues.put(QueueManager.MODE_CASUAL, new ArrayList<>());
        this.quickWaitSeconds = 0;
    }

    private List<UUID> quickQueueOf(String mode) {
        return quickQueues.computeIfAbsent(QueueManager.normalizeMode(mode), k -> new ArrayList<>());
    }

    boolean removeFromQuickQueues(UUID uuid) {
        for (List<UUID> queue : quickQueues.values()) {
            if (queue.remove(uuid)) return true;
        }
        return false;
    }

    boolean containsInQuickQueues(UUID uuid) {
        for (List<UUID> queue : quickQueues.values()) {
            if (queue.contains(uuid)) return true;
        }
        return false;
    }

    int getQuickQueueSize() {
        return quickQueues.values().stream().mapToInt(List::size).sum();
    }

    /** 指定模式的快速队列人数 */
    int quickQueueSizeOf(String mode) {
        return quickQueueOf(mode).size();
    }

    // ==================== 快速匹配（按模式独立） ====================

    void tryQuickMatch() {
        if (getQuickQueueSize() == 0) {
            quickWaitSeconds = 0;
            return;
        }

        quickWaitSeconds++;

        GlobalConfig globalConfig = ConfigManager.getInstance().getGlobalConfig();
        int quickTimeout = globalConfig != null ? globalConfig.getQuickTimeout() : 30;
        boolean timeout = quickWaitSeconds >= quickTimeout;

        // 流程（每秒一次，与文档一致）：
        // 1) 优先搜索未占用且不在冷却的地图直接开局（逐张尝试，人数达到该图两队最低之和即可）；
        // 2) 人数不足时与同模式的地图队列合并开局；
        // 3) 超过 quickTimeout 秒仍无法开局才进入补位流程。
        // 两种模式的快速队列各自独立处理，绝不混合
        List<String> availableMaps = getAvailableMaps();

        for (String mode : QueueManager.MODES) {
            List<UUID> queue = quickQueueOf(mode);
            if (queue.isEmpty()) continue;
            List<UUID> players = new ArrayList<>(queue);

            // 1) 直接开局：随机顺序逐张候选地图尝试，避开单张地图人数要求过高导致的漏配
            if (!timeout) {
                List<String> candidates = new ArrayList<>(availableMaps);
                Collections.shuffle(candidates);
                boolean started = false;
                for (String mapId : candidates) {
                    if (startQuickMatch(mapId, mode, players, false)) {
                        started = true;
                        break;
                    }
                }
                if (started) continue;
            }

            // 2) 跨队列合并开局（仅与同模式地图队列合并）
            if (tryStartWithOtherQueue(players, mode)) continue;

            // 3) 超时：强制开局（直接开局 → 补位）
            if (timeout) {
                forceStartQuickMatch(players, mode);
            }
        }

        if (timeout || getQuickQueueSize() == 0) {
            quickWaitSeconds = 0;
        }
    }

    private List<String> getAvailableMaps() {
        List<String> available = new ArrayList<>();
        List<MapConfig> maps = ConfigManager.getInstance().getMaps();

        for (MapConfig config : maps) {
            if (!config.isEnabled()) continue;
            if (MatchManager.getInstance().isMapInCooldown(config.getId())) continue;

            boolean inUse = MatchManager.getInstance().getAllActiveSessions().stream()
                    .anyMatch(s -> s.getMapName().equals(config.getId()) &&
                            s.getPhase() != MatchSession.GamePhase.ENDED);

            if (!inUse) {
                available.add(config.getId());
            }
        }
        return available;
    }

    /**
     * 跨队列合并开局（限同模式）：搜索该模式的其他地图队列，
     * 若快速队列 + 某地图队列的人数达到该图两队最低人数之和（minRedPlayers + minBluePlayers），
     * 则合并开该队列的地图。开局成功后被带走的玩家会同时移出快速队列与该地图队列，
     * 该图其余队列（含另一模式）被解散。
     * @return 是否成功开局
     */
    private boolean tryStartWithOtherQueue(List<UUID> quickPlayers, String mode) {
        ServerWorld world = QueueManager.getWorld();
        if (world == null || quickPlayers.isEmpty()) return false;

        for (String key : new ArrayList<>(qm.queues.keySet())) {
            Map<Integer, List<UUID>> teamMap = qm.queues.get(key);
            if (teamMap == null) continue;
            // 快速队列(mode M) 只与同模式 M 的地图队列合并
            if (!mode.equals(QueueManager.modeOf(key))) continue;

            String mapId = QueueManager.mapIdOf(key);
            MapConfig config = ConfigManager.getInstance().getMap(mapId);
            if (config == null || !config.isEnabled()) continue;
            if (MatchManager.getInstance().isMapInCooldown(mapId)) continue;

            boolean inUse = MatchManager.getInstance().getAllActiveSessions().stream()
                    .anyMatch(s -> s.getMapName().equals(mapId) && s.getPhase() != MatchSession.GamePhase.ENDED);
            if (inUse) continue;

            List<UUID> queued = new ArrayList<>(teamMap.get(1));
            queued.addAll(teamMap.get(2));
            if (quickPlayers.size() + queued.size() < config.getTotalMinPlayers()) continue;

            // 合并两路人马开该队列的地图（内部会移出快速队列与 playerQueueMap，
            // 并通过 dissolveQueuesForMap 解散该图全部队列，无需再清理本地 teamMap 引用）
            List<UUID> combined = new ArrayList<>(quickPlayers);
            combined.addAll(queued);
            if (startQuickMatch(mapId, mode, combined, false)) {
                Cstmm.LOGGER.info("[CSTMM - QueueManager] Quick match ({}) merged with {} queued players on map {}", mode, queued.size(), mapId);
                return true;
            }
        }
        return false;
    }

    /**
     * 超时后的补位流程（限同模式对局）：
     * 1. 优先补位到「双方人数下限（两队最低人数）均大于 1」且补位后不超过最高人数的地图；
     * 2. 若补位会超过最高人数，则寻找无人数上限的地图（maxRed/maxBlue == 0）补位；
     * 3. 均无候选时玩家留在队列中，等待下一轮超时重试。
     * @return 是否有玩家成功补位
     */
    private boolean tryReinforcement(List<UUID> players, String mode) {
        ServerWorld world = QueueManager.getWorld();
        if (world == null) return false;

        boolean competitive = QueueManager.isCompetitiveMode(mode);

        // 候选 1：双方人数下限均 > 1，且补位到下限不会超过最高人数的地图
        List<MatchSession> priority = new ArrayList<>();
        // 候选 2：无人数上限的地图
        List<MatchSession> unlimited = new ArrayList<>();

        for (MatchSession session : MatchManager.getInstance().getAllActiveSessions()) {
            if (session.getPhase() == MatchSession.GamePhase.ENDED) continue;
            // 模式过滤：快速队列只补位到同模式对局
            if (session.isCompetitive() != competitive) continue;

            MapConfig config = ConfigManager.getInstance().getMap(session.getMapName());
            if (config == null || !config.isEnabled() || !config.isReinforceable()) continue;

            int capRed = config.getMaxRedPlayers();
            int capBlue = config.getMaxBluePlayers();
            // 补位阈值即该图两队各自的最低人数
            int redThreshold = config.getMinRedPlayers();
            int blueThreshold = config.getMinBluePlayers();

            boolean priorityOk = redThreshold > 1 && blueThreshold > 1
                    && (capRed == 0 || capRed >= redThreshold)
                    && (capBlue == 0 || capBlue >= blueThreshold);

            if (priorityOk) {
                priority.add(session);
            } else if (capRed == 0 && capBlue == 0) {
                unlimited.add(session);
            }
        }

        for (MatchSession session : priority) {
            if (reinforceSession(session, players, world)) {
                return true;
            }
        }
        for (MatchSession session : unlimited) {
            if (reinforceSession(session, players, world)) {
                return true;
            }
        }
        return false;
    }

    /** 按目标对局地图自身的补位模式与最低人数执行补位 */
    private boolean reinforceSession(MatchSession session, List<UUID> players, ServerWorld world) {
        MapConfig config = ConfigManager.getInstance().getMap(session.getMapName());
        if (config == null) return false;
        return reinforceInto(session, players, world,
                config.isAlwaysReinforce(), config.getMinRedPlayers(), config.getMinBluePlayers());
    }

    /**
     * 计算某队可补位名额：CONDITIONAL 模式补到人数下限（阈值），ALWAYS 模式补到该队满员（cap=0 无上限时有多少补多少）。
     * @param candidateCount 当前等待补位的候选人数，用于无上限时的 target 上限
     */
    private int reinforcementNeed(int currentSize, int threshold, int cap, boolean alwaysReinforce, int candidateCount) {
        int target;
        if (alwaysReinforce) {
            target = cap > 0 ? cap : currentSize + candidateCount;
        } else {
            target = threshold;
            if (cap > 0) {
                target = Math.min(target, cap);
            }
        }
        return Math.max(0, target - currentSize);
    }

    /**
     * 将快速队列玩家补位到指定对局。
     * 补位时遵守队伍平衡原则（两队均可补时优先补人少的一队），
     * 补位玩家统一走 registerAndSetupPlayer 完成登记与初始化（传送/装备/状态保存）。
     */
    private boolean reinforceInto(MatchSession session, List<UUID> players, ServerWorld world,
                                  boolean alwaysReinforce, int redThreshold, int blueThreshold) {
        MapConfig config = ConfigManager.getInstance().getMap(session.getMapName());
        if (config == null) return false;

        int redNeed = reinforcementNeed(session.getRedPlayers().size(), redThreshold,
                config.getMaxRedPlayers(), alwaysReinforce, players.size());
        int blueNeed = reinforcementNeed(session.getBluePlayers().size(), blueThreshold,
                config.getMaxBluePlayers(), alwaysReinforce, players.size());
        if (redNeed <= 0 && blueNeed <= 0) return false;

        int assigned = 0;
        for (UUID uuid : players) {
            if (redNeed <= 0 && blueNeed <= 0) break;

            ServerPlayerEntity player = world.getServer().getPlayerManager().getPlayer(uuid);
            if (player == null) continue;

            // 队伍平衡原则：两队均可补时优先补人少的一队；
            // 战队偏好（尽量）：补进已有同战队成员的一队
            boolean toRed;
            if (redNeed > 0 && blueNeed > 0) {
                Clan clan = ClanManager.getInstance().getClanByPlayer(player.getUuid());
                if (clan != null) {
                    boolean redHas = hasClanUuids(session.getRedPlayers(), clan);
                    boolean blueHas = hasClanUuids(session.getBluePlayers(), clan);
                    if (redHas != blueHas) {
                        toRed = redHas;
                    } else {
                        toRed = session.getRedPlayers().size() <= session.getBluePlayers().size();
                    }
                } else {
                    toRed = session.getRedPlayers().size() <= session.getBluePlayers().size();
                }
            } else {
                toRed = redNeed > 0;
            }

            if (toRed) {
                session.getRedPlayers().add(uuid);
            } else {
                session.getBluePlayers().add(uuid);
            }
            // 统一初始化：登记会话、保存原点/模式/背包、传送、发装备
            MatchManager.getInstance().registerAndSetupPlayer(session, player);

            removeFromQuickQueues(uuid);
            qm.playerQueueMap.remove(uuid);
            player.sendMessage(Text.literal("§e快速匹配：补位加入 "
                    + config.getDisplayName() + (toRed ? " 红队！" : " 蓝队！")), false);

            if (toRed) redNeed--; else blueNeed--;
            assigned++;
        }

        if (assigned > 0) {
            Cstmm.LOGGER.info("[CSTMM - QueueManager] Reinforced {} players into {} ({})",
                    assigned, config.getId(), session.getSessionId());
            qm.onQueueChanged();
            return true;
        }
        return false;
    }

    /**
     * @param mode   快速队列所属匹配模式
     * @param notify 开局失败时是否向玩家发送提示（超时前的静默重试传 false，避免刷屏）
     * @return 是否成功开局
     */
    private boolean startQuickMatch(String mapId, String mode, List<UUID> players, boolean notify) {
        ServerWorld world = QueueManager.getWorld();
        if (world == null) return false;

        MapConfig config = ConfigManager.getInstance().getMap(mapId);
        int minRed = config != null ? config.getMinRedPlayers() : 1;
        int minBlue = config != null ? config.getMinBluePlayers() : 1;
        // 人数需达到该图两队最低人数之和才能开局
        if (players.size() < minRed + minBlue) return false;

        // 随机打乱后分队
        Collections.shuffle(players);

        int redMax = config != null && config.getMaxRedPlayers() > 0 ? config.getMaxRedPlayers() : Integer.MAX_VALUE;
        int blueMax = config != null && config.getMaxBluePlayers() > 0 ? config.getMaxBluePlayers() : Integer.MAX_VALUE;

        List<ServerPlayerEntity> redPlayers = new ArrayList<>();
        List<ServerPlayerEntity> bluePlayers = new ArrayList<>();
        List<ServerPlayerEntity> overflow = new ArrayList<>();

        // 战队聚组：同战队成员相邻（组间按大小降序，无战队在后），配合战队优先分队让同战队尽量同队
        List<ServerPlayerEntity> ordered = clanGroupedOrder(onlinePlayers(players, world));

        // 分队顺序：战队优先（同战队进已有同战队成员的一队）→ 人数平衡分配（遵守 maxRed/maxBlue 上限）；
        // 分完后再修正两队最低人数（见 fixMinPlayers）
        for (ServerPlayerEntity player : ordered) {
            boolean toRed;
            if (redPlayers.size() >= redMax) {
                toRed = false;
            } else if (bluePlayers.size() >= blueMax) {
                toRed = true;
            } else {
                Clan clan = ClanManager.getInstance().getClanByPlayer(player.getUuid());
                if (clan != null) {
                    boolean redHas = hasClanPlayers(redPlayers, clan);
                    boolean blueHas = hasClanPlayers(bluePlayers, clan);
                    if (redHas != blueHas) {
                        toRed = redHas;
                    } else {
                        toRed = redPlayers.size() <= bluePlayers.size();
                    }
                } else {
                    toRed = redPlayers.size() <= bluePlayers.size();
                }
            }

            if (toRed && redPlayers.size() < redMax) {
                redPlayers.add(player);
            } else if (!toRed && bluePlayers.size() < blueMax) {
                bluePlayers.add(player);
            } else {
                overflow.add(player);
            }
        }

        // 战队聚组可能破坏两队最低人数：在两队间移动成员修正（尽量挑不拆散战队的成员）
        fixMinPlayers(redPlayers, bluePlayers, minRed, minBlue, redMax, blueMax);

        // 溢出玩家尝试塞进对方队伍，塞不下则留在队列中等待下一轮
        for (ServerPlayerEntity player : overflow) {
            if (redPlayers.size() < redMax && redPlayers.size() <= bluePlayers.size()) {
                redPlayers.add(player);
            } else if (bluePlayers.size() < blueMax) {
                bluePlayers.add(player);
            } else if (redPlayers.size() < redMax) {
                redPlayers.add(player);
            }
        }

        // 在线人数不足两队最低人数（如部分玩家离线）时无法开局
        if (redPlayers.size() < minRed || bluePlayers.size() < minBlue) return false;

        if (!MatchManager.getInstance().startMatch(mapId, QueueManager.isCompetitiveMode(mode), redPlayers, bluePlayers)) {
            if (notify) {
                for (ServerPlayerEntity p : redPlayers) {
                    p.sendMessage(Text.literal("§c快速匹配失败（地图不可用），你仍在队列中等待..."), false);
                }
                for (ServerPlayerEntity p : bluePlayers) {
                    p.sendMessage(Text.literal("§c快速匹配失败（地图不可用），你仍在队列中等待..."), false);
                }
            }
            return false;
        }

        for (ServerPlayerEntity p : redPlayers) {
            removeFromQuickQueues(p.getUuid());
            qm.playerQueueMap.remove(p.getUuid());
        }
        for (ServerPlayerEntity p : bluePlayers) {
            removeFromQuickQueues(p.getUuid());
            qm.playerQueueMap.remove(p.getUuid());
        }

        // 先到先得：本图已被占用，解散该图所有队列（含另一模式）
        qm.dissolveQueuesForMap(mapId, mode);

        Cstmm.LOGGER.info("[CSTMM - QueueManager] Quick match ({}) started on {} with {} players", mode, mapId, players.size());
        return true;
    }

    private void forceStartQuickMatch(List<UUID> players, String mode) {
        // 优先找可用地图：随机顺序逐张尝试（避开单张地图人数要求过高导致的漏配）
        List<String> available = getAvailableMaps();
        Collections.shuffle(available);
        for (String mapId : available) {
            if (startQuickMatch(mapId, mode, players, false)) {
                return;
            }
        }

        // 所有地图都在使用或开局失败 → 按补位规则补位（限定同模式对局）；
        // 无法补位时玩家留在队列中等待下一轮超时重试（不发送误导性失败消息）
        tryReinforcement(players, mode);
    }

    // ==================== 战队聚组分队辅助 ====================

    /** 解析在线玩家（开局成功后才移出队列，避免开局失败导致玩家凭空脱离队列） */
    private List<ServerPlayerEntity> onlinePlayers(List<UUID> uuids, ServerWorld world) {
        List<ServerPlayerEntity> online = new ArrayList<>();
        for (UUID uuid : uuids) {
            ServerPlayerEntity player = world.getServer().getPlayerManager().getPlayer(uuid);
            if (player != null) online.add(player);
        }
        return online;
    }

    /** 战队聚组排序：同战队相邻（组内保持随机），组间按人数降序，无战队成员排在最后 */
    private List<ServerPlayerEntity> clanGroupedOrder(List<ServerPlayerEntity> online) {
        ClanManager cm = ClanManager.getInstance();
        Map<Clan, List<ServerPlayerEntity>> groups = new LinkedHashMap<>();
        List<ServerPlayerEntity> clanless = new ArrayList<>();
        for (ServerPlayerEntity p : online) {
            Clan clan = cm.getClanByPlayer(p.getUuid());
            if (clan == null) clanless.add(p);
            else groups.computeIfAbsent(clan, k -> new ArrayList<>()).add(p);
        }
        List<List<ServerPlayerEntity>> groupList = new ArrayList<>(groups.values());
        groupList.sort((a, b) -> b.size() - a.size());
        List<ServerPlayerEntity> ordered = new ArrayList<>();
        for (List<ServerPlayerEntity> g : groupList) ordered.addAll(g);
        ordered.addAll(clanless);
        return ordered;
    }

    private boolean hasClanPlayers(List<ServerPlayerEntity> team, Clan clan) {
        ClanManager cm = ClanManager.getInstance();
        for (ServerPlayerEntity p : team) {
            if (cm.getClanByPlayer(p.getUuid()) == clan) return true;
        }
        return false;
    }

    private boolean hasClanUuids(Collection<UUID> team, Clan clan) {
        ClanManager cm = ClanManager.getInstance();
        for (UUID uuid : team) {
            if (cm.getClanByPlayer(uuid) == clan) return true;
        }
        return false;
    }

    /**
     * 战队聚组后修正两队最低人数：从超出需要的一队向不足的一队移动成员。
     * 移动优先级：无战队成员 → 独自代表本战队的成员（整族迁移不拆队）→ 大战队成员（影响最小）。
     */
    private void fixMinPlayers(List<ServerPlayerEntity> red, List<ServerPlayerEntity> blue,
                               int minRed, int minBlue, int redMax, int blueMax) {
        while (red.size() < minRed && blue.size() - 1 >= minBlue) {
            ServerPlayerEntity mover = pickMovable(blue);
            if (mover == null || red.size() + 1 > redMax) break;
            blue.remove(mover);
            red.add(mover);
        }
        while (blue.size() < minBlue && red.size() - 1 >= minRed) {
            ServerPlayerEntity mover = pickMovable(red);
            if (mover == null || blue.size() + 1 > blueMax) break;
            red.remove(mover);
            blue.add(mover);
        }
    }

    private ServerPlayerEntity pickMovable(List<ServerPlayerEntity> team) {
        ClanManager cm = ClanManager.getInstance();
        ServerPlayerEntity fallback = null;
        int fallbackClanSize = -1;
        for (ServerPlayerEntity p : team) {
            Clan c = cm.getClanByPlayer(p.getUuid());
            if (c == null) return p;
            int count = 0;
            for (ServerPlayerEntity q : team) {
                if (cm.getClanByPlayer(q.getUuid()) == c) count++;
            }
            if (count == 1) return p;
            if (count > fallbackClanSize) {
                fallbackClanSize = count;
                fallback = p;
            }
        }
        return fallback;
    }

    // ==================== 队列成员操作（供 QueueManager 门面调用） ====================

    /** 该模式的快速队列是否包含指定玩家（仅查本模式队列，非跨队列查询） */
    boolean quickQueueContains(String mode, UUID uuid) {
        return quickQueueOf(mode).contains(uuid);
    }

    /** 将玩家加入该模式的快速队列 */
    void addQuickQueueMember(String mode, UUID uuid) {
        quickQueueOf(mode).add(uuid);
    }
}
