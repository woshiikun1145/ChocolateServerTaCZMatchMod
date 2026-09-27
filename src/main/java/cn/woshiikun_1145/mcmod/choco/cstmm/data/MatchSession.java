package cn.woshiikun_1145.mcmod.choco.cstmm.data;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 【作用】一局对局的运行时状态：双方队伍成员、击杀数、剩余时间、阶段（准备/战斗/结束）、
 * 加时次数与边界警告计时，仅存在于内存（不持久化）。
 * 【被谁使用】MatchManager 创建并推进（服务端）；MatchScheduler 每 tick 计时；
 * EventListener 击杀判定、BoundaryChecker 越界检查、VoteManager 投票、QueueManager/QuickMatchEngine
 * 开局、EquipmentManager 发装备、ModCommands/NetworkHandler 查询展示均读取。
 */
public class MatchSession {
    // 对局唯一 id（随机 UUID），VoteApi 的 matchId 即此值
    private final String sessionId;
    // 对局地图 id（MapConfig.id）
    private final String mapName;
    // 红队成员 UUID 集合
    private final Set<UUID> redPlayers;
    // 蓝队成员 UUID 集合
    private final Set<UUID> bluePlayers;
    // 红队当前总击杀数
    private int redKills;
    // 蓝队当前总击杀数
    private int blueKills;
    // 剩余对局秒数（由 MatchScheduler 递减）
    private int remainingSeconds;
    // 当前阶段：IDLE/PREPARING/FIGHTING/ENDED
    private GamePhase phase;
    // 准备阶段倒计秒（PREPARING 阶段由 MatchScheduler 递减）
    private int prepCounter;
    // 是否竞技模式（排位/快速匹配的对局为 true）
    private boolean isCompetitive;
    // 对局创建时间戳（毫秒），用于 getElapsedMillis 判断开局保护期等
    private long startTime;
    // 已进行的加时赛次数
    private int overtimeCount;
    // 各玩家持续越界的已警告秒数（BoundaryChecker 读写）
    private final java.util.Map<UUID, Integer> boundaryWarningTime;
    // 各玩家本局累计个人击杀数（EventListener 异队击杀时累加；HUD 花名册读取）
    private final java.util.Map<UUID, Integer> playerKills;
    // 各玩家本局累计死亡数（EventListener 死亡事件累加；用于本局 K/D）
    private final java.util.Map<UUID, Integer> playerDeaths;
    // 各玩家当前这条命的击杀数（死亡时清零并转存到 lastLifeKills）
    private final java.util.Map<UUID, Integer> lifeKills;
    // 各玩家上一条命的击杀数（死亡时冻结，重生不清除；HUD 击杀徽章读取）
    private final java.util.Map<UUID, Integer> lastLifeKills;
    // 玩家名缓存（uuid → 最近一次在线时的名字；离线玩家仍可显示卡片名）
    private final java.util.Map<UUID, String> playerNames;

    // 构造：初始化空局，阶段为 IDLE，待 MatchManager 填充队伍并进入准备阶段
    public MatchSession(String mapName) {
        this.sessionId = UUID.randomUUID().toString();
        this.mapName = mapName;
        this.redPlayers = new HashSet<>();
        this.bluePlayers = new HashSet<>();
        this.redKills = 0;
        this.blueKills = 0;
        this.remainingSeconds = 0;
        this.phase = GamePhase.IDLE;
        this.prepCounter = 0;
        this.isCompetitive = false;
        this.startTime = System.currentTimeMillis();
        this.boundaryWarningTime = new java.util.HashMap<>();
        this.playerKills = new java.util.HashMap<>();
        this.playerDeaths = new java.util.HashMap<>();
        this.lifeKills = new java.util.HashMap<>();
        this.lastLifeKills = new java.util.HashMap<>();
        this.playerNames = new java.util.HashMap<>();
        this.overtimeCount = 0;
    }

    public String getSessionId() { return sessionId; }
    public String getMapName() { return mapName; }
    public Set<UUID> getRedPlayers() { return redPlayers; }
    public Set<UUID> getBluePlayers() { return bluePlayers; }
    public int getRedKills() { return redKills; }
    public int getBlueKills() { return blueKills; }
    public int getRemainingSeconds() { return remainingSeconds; }
    public GamePhase getPhase() { return phase; }
    public int getPrepCounter() { return prepCounter; }
    public boolean isCompetitive() { return isCompetitive; }
    public long getStartTime() { return startTime; }
    public int getOvertimeCount() { return overtimeCount; }

    public void setRedKills(int redKills) { this.redKills = redKills; }
    public void setBlueKills(int blueKills) { this.blueKills = blueKills; }
    public void setRemainingSeconds(int remainingSeconds) { this.remainingSeconds = remainingSeconds; }
    public void setPhase(GamePhase phase) { this.phase = phase; }
    public void setPrepCounter(int prepCounter) { this.prepCounter = prepCounter; }
    public void setCompetitive(boolean competitive) { isCompetitive = competitive; }
    public void setStartTime(long startTime) { this.startTime = startTime; }
    public void setOvertimeCount(int overtimeCount) { this.overtimeCount = overtimeCount; }

    public void addRedKills(int amount) { this.redKills += amount; }
    public void addBlueKills(int amount) { this.blueKills += amount; }

    /** 【作用】对局总人数（红蓝两队之和）。 */
    public int getTotalPlayers() {
        return redPlayers.size() + bluePlayers.size();
    }

    /** 【作用】玩家是否参与本局（红蓝任一队）。 */
    public boolean isPlayerInGame(UUID playerUuid) {
        return redPlayers.contains(playerUuid) || bluePlayers.contains(playerUuid);
    }

    /** 【作用】查询玩家队伍：1=红队、2=蓝队、0=不在本局红蓝名单（对局成员必属两队之一，已无观战者设定）。 */
    public int getPlayerTeam(UUID playerUuid) {
        if (redPlayers.contains(playerUuid)) return 1;
        if (bluePlayers.contains(playerUuid)) return 2;
        return 0;
    }

    /** 【作用】返回两队全部玩家的合并集合（副本，修改不影响原集合）。 */
    public Set<UUID> getAllPlayers() {
        Set<UUID> all = new HashSet<>();
        all.addAll(redPlayers);
        all.addAll(bluePlayers);
        return all;
    }

    /** 【作用】读取玩家已持续越界的警告秒数（无记录返回 0）。 */
    public int getBoundaryWarningTime(UUID playerUuid) {
        return boundaryWarningTime.getOrDefault(playerUuid, 0);
    }

    /** 【作用】累计/设置玩家的越界警告秒数（BoundaryChecker 每秒调用）。 */
    public void setBoundaryWarningTime(UUID playerUuid, int seconds) {
        boundaryWarningTime.put(playerUuid, seconds);
    }

    /** 【作用】清除玩家的越界警告记录（回场或处决后调用）。 */
    public void resetBoundaryWarningTime(UUID playerUuid) {
        boundaryWarningTime.remove(playerUuid);
    }

    // ==================== 个人击杀/死亡统计（HUD 花名册用） ====================

    /**
     * 【作用】记录一次有效击杀（EventListener 异队玩家击杀时调用）：
     * 本局个人击杀 +1、当前生命击杀 +1。
     * 【被谁使用】EventListener 的 AFTER_DEATH 有效击杀路径。仅服务端。
     */
    public void recordKill(UUID killerUuid) {
        playerKills.merge(killerUuid, 1, Integer::sum);
        lifeKills.merge(killerUuid, 1, Integer::sum);
    }

    /**
     * 【作用】记录一次死亡（EventListener 死亡事件调用，含环境死/越界处决）：
     * 本局死亡数 +1；当前生命击杀冻结为上一条命战绩后清零。
     * 冻结无条件执行（含 0 杀死亡）：上一条命确实没拿到击杀时徽章应归零，
     * 而不是保留更早生命的旧值。
     * 【被谁使用】EventListener 的 AFTER_DEATH（对局内玩家死亡即调用，无论死因）。仅服务端。
     */
    public void recordDeath(UUID killedUuid) {
        playerDeaths.merge(killedUuid, 1, Integer::sum);
        lastLifeKills.put(killedUuid, lifeKills.getOrDefault(killedUuid, 0));
        lifeKills.put(killedUuid, 0);
    }

    /** 【作用】本局个人击杀数（无记录返回 0）。HUD 花名册构建读取。 */
    public int getPlayerKills(UUID playerUuid) {
        return playerKills.getOrDefault(playerUuid, 0);
    }

    /** 【作用】本局个人死亡数（无记录返回 0）。HUD 花名册构建读取。 */
    public int getPlayerDeaths(UUID playerUuid) {
        return playerDeaths.getOrDefault(playerUuid, 0);
    }

    /** 【作用】上一条命击杀数（无记录或从未死亡返回 0）。HUD 击杀徽章读取。 */
    public int getLastLifeKills(UUID playerUuid) {
        return lastLifeKills.getOrDefault(playerUuid, 0);
    }

    /**
     * 【作用】缓存玩家名（对局内任何在线时刻调用；离线后花名册仍能显示其名字）。
     * 【被谁使用】MatchManager（prepareMatch/registerAndSetupPlayer/handleRespawn/broadcastHudData）。仅服务端。
     */
    public void recordPlayerName(UUID playerUuid, String name) {
        if (name != null && !name.isEmpty()) {
            playerNames.put(playerUuid, name);
        }
    }

    /** 【作用】读取缓存的玩家名（未缓存返回 null，调用方自行回退）。HUD 花名册构建读取。 */
    public String getPlayerName(UUID playerUuid) {
        return playerNames.get(playerUuid);
    }

    /** 【作用】对局已进行的毫秒数（距创建时刻）。 */
    public long getElapsedMillis() {
        return System.currentTimeMillis() - startTime;
    }

    // 对局阶段：准备 → 战斗 → 结束（IDLE 为占位初始态）
    public enum GamePhase {
        IDLE,
        PREPARING,
        FIGHTING,
        ENDED
    }
}