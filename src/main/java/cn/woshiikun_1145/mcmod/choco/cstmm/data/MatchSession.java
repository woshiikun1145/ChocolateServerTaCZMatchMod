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

    // ==================== 爆破模式回合状态（仅爆破图对局使用） ====================
    // 是否爆破对局（地图 gameType=BOMB 时由 MatchManager 开局时置位）
    private boolean bombMode;
    // 已完成的回合数（含加时回合；回合从 1 开始计）
    private int roundNumber = 0;
    // 队伍回合胜场（跟随队伍，不随攻防转换：1=红队 2=蓝队）
    private int team1RoundWins = 0;
    private int team2RoundWins = 0;
    // 当前充当 T（进攻方）的队伍编号：1=红队 2=蓝队（休闲固定 1；竞技第 13 回合/加时第 4 回合翻转）
    private int tTeam = 1;
    // 是否处于加时赛阶段（竞技 12:12 后进入；加时 3:3 投票通过后开新一场加时并累加 overtimeCount）
    private boolean inOvertime = false;
    // 当前这场加时赛已进行的回合数（1~6，攻防在第 4 回合转换）
    private int overtimeRoundInSet = 0;
    // 本场加时赛的回合胜场（先到 4 胜；3:3 时投票决定继续或平局）
    private int otTeam1Wins = 0;
    private int otTeam2Wins = 0;
    // 回合结束后的间歇倒计时（秒）：>0 时本回合已分胜负，等间歇结束再开下一回合/结算整场
    private int roundEndCounter = 0;
    // 下一回合是否仍属本场加时（供间歇结束后的回合推进判定）
    private boolean roundPending = false;
    // 本回合已死亡玩家（旁观者；回合重置时清空）——爆破模式全歼判定以此为准，
    // 离线玩家同样记入（宽限期内按死亡处理，防回合被离线玩家卡死）
    private final Set<UUID> deadThisRound = new HashSet<>();
    // 玩家死亡位置（回合内旁观者传送锚点；回合重置清空）
    private final java.util.Map<UUID, int[]> deathPositions = new java.util.HashMap<>();
    // C4 已安放（安放后 T 全灭不结束回合、全 CT 死立即 T 胜、回合倒计时切换为 C4 计时）
    private boolean bombPlanted = false;
    // C4 爆炸倒计时（秒，安放时从配置 c4TimerSeconds 起每秒递减）
    private int bombTimerSeconds = 0;
    // 安放的包点名（HUD/消息展示）
    private String plantedSite = "";
    // 安放位置（爆炸中心；服务端方块坐标）
    private int[] plantedPos = null;
    // 当前 C4 携带者（null = C4 在地上或已安放；每回合开始随机指派一名存活 T）
    private UUID bombCarrier = null;
    // 拆弹进度（0~1000，服务端每 tick 累计；拆弹者松键/离开/死亡时清零）
    private int defuseProgress = 0;
    // 当前正在拆弹的玩家（null = 无人拆弹）
    private UUID defusingPlayer = null;
    // 当前正在安放 C4 的玩家（null = 无人安放；手持 C4 长按右键时由 BombInteractionManager 设置）
    private UUID plantingPlayer = null;
    // 安放进度（0~1000，服务端每 tick 累计；松键/离开包点/死亡/切物品时清零）
    private int plantProgress = 0;
    // 各玩家 3 分钟重连宽限截止时间戳（离线时刻起算；回合结束时清算超时者）
    private final java.util.Map<UUID, Long> reconnectDeadlines = new java.util.HashMap<>();
    // 整队无人在线的等待重连状态（挂起回合推进；有人重连即恢复；3 分钟无人重连 → 当前回合判负该队）
    private boolean waitingReconnect = false;

    // ==================== 爆破模式经济与拆弹器状态（仅爆破图对局使用） ====================
    // 各玩家匹配内货币（价格=匹配内货币，非服务器货币；不持久化，对局结束清零）
    private final java.util.Map<UUID, Integer> playerMoney = new java.util.HashMap<>();
    // 队伍连败回合数（败金递增用：base + streak × step，胜方清零；1=红队 2=蓝队）
    private int team1LoseStreak = 0;
    private int team2LoseStreak = 0;
    // 本回合是否安放过 C4（含被拆除后；回合重置清零）——
    // CS 规则：T 方战败时，存活且从未下包的玩家拿 0 败金，用此字段判定
    private boolean bombEverPlantedThisRound = false;
    // 拆弹装备持有状态不在会话中存储——用玩家 tag（BombInteractionManager.DEFUSE_KIT_TAG）表示，
    // 休闲每回合自动打/竞技阵亡清除/商店购买获得；HUD kit 字段由服务端实时读玩家 tag 下发

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

    // ==================== 爆破回合状态访问器（BombRoundManager 读写） ====================

    public boolean isBombMode() { return bombMode; }
    public void setBombMode(boolean bombMode) { this.bombMode = bombMode; }
    public int getRoundNumber() { return roundNumber; }
    public void setRoundNumber(int roundNumber) { this.roundNumber = roundNumber; }
    public void incrementRoundNumber() { this.roundNumber++; }
    public int getTeam1RoundWins() { return team1RoundWins; }
    public int getTeam2RoundWins() { return team2RoundWins; }

    /** 队伍回合胜场 +1（winnerTeam=1/2）。 */
    public void addRoundWin(int winnerTeam) {
        if (winnerTeam == 1) team1RoundWins++;
        else if (winnerTeam == 2) team2RoundWins++;
    }

    public int getTTeam() { return tTeam; }
    public void setTTeam(int tTeam) {
        if (tTeam == 1 || tTeam == 2) this.tTeam = tTeam;
    }

    /** 队伍当前阵营：true = T（进攻方），false = CT（防守方）。 */
    public boolean isTTeam(int team) { return team == tTeam; }

    /** 玩家当前阵营（0=不在局内）：true = T，false = CT。 */
    public Boolean getPlayerSide(UUID playerUuid) {
        int team = getPlayerTeam(playerUuid);
        return team == 0 ? null : isTTeam(team);
    }

    /** 对方队伍编号（1↔2）。 */
    public int getOppositeTeam(int team) { return team == 1 ? 2 : 1; }

    /**
     * 【作用】队伍显示名：爆破对局按当前攻防身份返回“T 方”/“CT 方”（攻防会在竞技第 13 回合
     *         与加时第 4 回合翻转，不能写死红/蓝队名）；普通对局返回“红队”/“蓝队”。
     *         队伍编号 1=红队、2=蓝队。
     * 【被谁使用】BombRoundManager、VoteManager、QuickMatchEngine、ModCommands 的玩家可见消息拼装。
     */
    public String teamDisplayName(int team) {
        if (team != 1 && team != 2) return "未知队伍";
        if (!bombMode) return team == 1 ? "红队" : "蓝队";
        return team == tTeam ? "T 方" : "CT 方";
    }

    public boolean isInOvertime() { return inOvertime; }
    public void setInOvertime(boolean inOvertime) { this.inOvertime = inOvertime; }
    public int getOvertimeRoundInSet() { return overtimeRoundInSet; }
    public void setOvertimeRoundInSet(int v) { this.overtimeRoundInSet = v; }
    public int getOtTeam1Wins() { return otTeam1Wins; }
    public int getOtTeam2Wins() { return otTeam2Wins; }

    /** 加时赛回合胜场 +1（内部按队伍归一，OT 3:3 时读此判定是否再投继续）。 */
    public void addOtWin(int winnerTeam) {
        if (winnerTeam == 1) otTeam1Wins++;
        else if (winnerTeam == 2) otTeam2Wins++;
    }

    /** 重置本场加时计分（投票继续后的下一场加时用）。 */
    public void resetOvertimeWins() {
        otTeam1Wins = 0;
        otTeam2Wins = 0;
        overtimeRoundInSet = 0;
    }

    public int getRoundEndCounter() { return roundEndCounter; }
    public void setRoundEndCounter(int v) { this.roundEndCounter = v; }
    public boolean isRoundPending() { return roundPending; }
    public void setRoundPending(boolean roundPending) { this.roundPending = roundPending; }

    public Set<UUID> getDeadThisRound() { return deadThisRound; }

    /** 玩家本回合是否已死亡（含离线宽限期玩家）。 */
    public boolean isDeadThisRound(UUID playerUuid) { return deadThisRound.contains(playerUuid); }

    /** 标记玩家本回合死亡并记录死亡位置（旁观者锚点）。 */
    public void markDeadThisRound(UUID playerUuid, double x, double y, double z) {
        deadThisRound.add(playerUuid);
        deathPositions.put(playerUuid, new int[]{(int) x, (int) y, (int) z});
    }

    /** 死亡位置（无记录返回 null；旁观者传送锚点）。 */
    public int[] getDeathPosition(UUID playerUuid) { return deathPositions.get(playerUuid); }

    /** 回合重置：清空死亡名单/死亡位置/C4/拆弹状态（C4 携带者由回合开始重新指派）。 */
    public void resetRoundState() {
        deadThisRound.clear();
        deathPositions.clear();
        bombPlanted = false;
        bombTimerSeconds = 0;
        plantedSite = "";
        plantedPos = null;
        bombCarrier = null;
        defuseProgress = 0;
        defusingPlayer = null;
        plantingPlayer = null;
        plantProgress = 0;
        roundEndCounter = 0;
        roundPending = false;
        waitingReconnect = false;
        bombEverPlantedThisRound = false;
    }

    public boolean isBombPlanted() { return bombPlanted; }
    public void setBombPlanted(boolean bombPlanted) { this.bombPlanted = bombPlanted; }
    public int getBombTimerSeconds() { return bombTimerSeconds; }
    public void setBombTimerSeconds(int bombTimerSeconds) { this.bombTimerSeconds = bombTimerSeconds; }
    public String getPlantedSite() { return plantedSite; }
    public void setPlantedSite(String plantedSite) { this.plantedSite = plantedSite; }
    public int[] getPlantedPos() { return plantedPos; }
    public void setPlantedPos(int[] plantedPos) { this.plantedPos = plantedPos; }
    public UUID getBombCarrier() { return bombCarrier; }
    public void setBombCarrier(UUID bombCarrier) { this.bombCarrier = bombCarrier; }
    public int getDefuseProgress() { return defuseProgress; }
    public void setDefuseProgress(int defuseProgress) { this.defuseProgress = defuseProgress; }
    public UUID getDefusingPlayer() { return defusingPlayer; }
    public void setDefusingPlayer(UUID defusingPlayer) { this.defusingPlayer = defusingPlayer; }
    public UUID getPlantingPlayer() { return plantingPlayer; }
    public void setPlantingPlayer(UUID plantingPlayer) { this.plantingPlayer = plantingPlayer; }
    public int getPlantProgress() { return plantProgress; }
    public void setPlantProgress(int plantProgress) { this.plantProgress = plantProgress; }

    public java.util.Map<UUID, Long> getReconnectDeadlines() { return reconnectDeadlines; }
    public boolean isWaitingReconnect() { return waitingReconnect; }
    public void setWaitingReconnect(boolean waitingReconnect) { this.waitingReconnect = waitingReconnect; }

    // ==================== 经济与拆弹器访问器（EconomyManager / BombRoundManager 读写） ====================

    /** 玩家匹配内货币余额（无记录返回 0）。 */
    public int getPlayerMoney(UUID playerUuid) {
        return playerMoney.getOrDefault(playerUuid, 0);
    }

    /** 玩家是否有金钱记录（中途补位玩家无记录，由 EconomyManager 懒初始化起始金）。 */
    public boolean hasMoneyRecord(UUID playerUuid) {
        return playerMoney.containsKey(playerUuid);
    }

    /** 设置玩家匹配内货币余额（调用方负责封顶，EconomyManager 统一 clamp）。 */
    public void setPlayerMoney(UUID playerUuid, int amount) {
        playerMoney.put(playerUuid, Math.max(0, amount));
    }

    /** 清空全部玩家货币（对局结束清理；半场/加时重置按人数重赋值，不走此方法）。 */
    public void clearPlayerMoney() {
        playerMoney.clear();
    }

    public int getTeam1LoseStreak() { return team1LoseStreak; }
    public int getTeam2LoseStreak() { return team2LoseStreak; }

    /** 队伍连败数 +1（loserTeam=1/2，胜方清零由 settleRound 处理）。 */
    public void addLoseStreak(int loserTeam) {
        if (loserTeam == 1) team1LoseStreak++;
        else if (loserTeam == 2) team2LoseStreak++;
    }

    /** 重置双方连败计数（开局/半场/加时重置）。 */
    public void resetLoseStreaks() {
        team1LoseStreak = 0;
        team2LoseStreak = 0;
    }

    /** 归零指定队伍连败计数（回合结算胜方清零；team=1/2）。 */
    public void resetLoseStreak(int team) {
        if (team == 1) team1LoseStreak = 0;
        else if (team == 2) team2LoseStreak = 0;
    }

    public boolean isBombEverPlantedThisRound() { return bombEverPlantedThisRound; }
    public void setBombEverPlantedThisRound(boolean v) { this.bombEverPlantedThisRound = v; }

    // 对局阶段：准备 → 战斗 → 结束（IDLE 为占位初始态）
    public enum GamePhase {
        IDLE,
        PREPARING,
        FIGHTING,
        ENDED
    }
}