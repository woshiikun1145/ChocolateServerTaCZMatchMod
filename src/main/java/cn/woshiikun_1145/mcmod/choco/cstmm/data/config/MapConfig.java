package cn.woshiikun_1145.mcmod.choco.cstmm.data.config;

import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * 【作用】单张地图的完整配置（Gson 持久化到 config/cstmm/maps.json）：胜负条件、边界、
 * 双方出生点、最低/最高人数、准备时间、补位规则、商店商品等。
 * 【被谁使用】ConfigManager 加载/保存/生成默认配置（服务端）；MatchManager 开局与传送出生点、
 * QueueManager/QuickMatchEngine 匹配开局、BoundaryChecker 越界检查、VoteManager 踢人冷却、
 * EquipmentManager 商店购买、ModCommands/NetworkHandler 展示与同步均读取。
 */
public class MapConfig {
    // 地图唯一 id（匹配/开局引用键）
    private String id;
    // 展示名（UI/聊天显示）
    private String displayName;
    // 是否启用（禁用地图不参与匹配）
    private boolean enabled;
    // 投票界面背景图（Base64）
    private String backgroundBase64;
    // 胜负条件：击杀达标或时间到
    private WinCondition winCondition;
    // 目标击杀数（winCondition=KILLS 时达标获胜）
    private int targetKills;
    // 对局最长时长（秒，winCondition=TIMER 或防拖局）
    private int maxDuration;
    // 平局处理：加时或直接平局
    private TieRule tieRule;
    // 对局区域边界（越界警告与处决）
    private Boundary boundary;
    // 红队出生点列表
    private List<BlockPos> redSpawns;
    // 蓝队出生点列表
    private List<BlockPos> blueSpawns;
    // 旧版总最低人数（已由 minRedPlayers + minBluePlayers 取代，保留兼容旧配置）
    private int minPlayers;
    // 地图冷却（秒）
    private int cooldownSeconds;
    /** 红队最低人数（开局与补位阈值），匹配队列达到 minRedPlayers + minBluePlayers 才能开局 */
    private int minRedPlayers;
    /** 蓝队最低人数（开局与补位阈值） */
    private int minBluePlayers;
    /** 准备阶段秒数（按地图配置） */
    private int prepareTime;
    /** 边界警告秒数（按地图配置） */
    private int boundaryWarningTime;
    /** 越界处决时给对方队伍的惩罚击杀数（按地图配置） */
    private int boundaryPenaltyKills;
    /** 发起踢人投票的冷却秒数（按地图配置） */
    private int kickCooldownSeconds;
    /** 补位模式：CONDITIONAL=队伍人数低于最低人数时才允许补位；ALWAYS=随时可补位（按地图配置） */
    private String reinforcementMode;
    /** 红队最高人数，0 = 无上限 */
    private int maxRedPlayers;
    /** 蓝队最高人数，0 = 无上限 */
    private int maxBluePlayers;
    /** 是否允许快速匹配玩家中途补位进入该地图的对局 */
    private boolean reinforceable;
    /** 对局所在维度 ID（JSON 键 "dimension"，默认主世界） */
    private String dimension;
    /** 本地图商店商品（每个地图独立一份） */
    private List<GlobalConfig.ShopItem> shopItems;

    // 出生点短期占用记录（坐标 → 上次选中时间戳），transient 不持久化，用于错开连续刷新
    private transient final java.util.Map<BlockPos, Long> spawnCooldowns;

    // 默认构造：填充全部字段的默认值（Gson 缺字段时以 JSON 值覆盖）
    public MapConfig() {
        this.id = "";
        this.displayName = "";
        this.enabled = true;
        this.backgroundBase64 = "";
        this.winCondition = WinCondition.KILLS;
        this.targetKills = 10;
        this.maxDuration = 1800;
        this.tieRule = TieRule.OVERTIME;
        this.boundary = new Boundary();
        this.redSpawns = new ArrayList<>();
        this.blueSpawns = new ArrayList<>();
        this.minPlayers = 2;
        this.cooldownSeconds = 0;
        this.minRedPlayers = 1;
        this.minBluePlayers = 1;
        this.prepareTime = 5;
        this.boundaryWarningTime = 10;
        this.boundaryPenaltyKills = 5;
        this.kickCooldownSeconds = 60;
        this.reinforcementMode = "CONDITIONAL";
        this.maxRedPlayers = 0;
        this.maxBluePlayers = 0;
        this.dimension = "minecraft:overworld";
        this.shopItems = new ArrayList<>();
        this.spawnCooldowns = new java.util.HashMap<>();
    }

    public String getId() { return id; }
    public String getDisplayName() { return displayName; }
    public boolean isEnabled() { return enabled; }
    public String getBackgroundBase64() { return backgroundBase64; }
    public WinCondition getWinCondition() { return winCondition; }
    public int getTargetKills() { return targetKills; }
    public int getMaxDuration() { return maxDuration; }
    public TieRule getTieRule() { return tieRule; }
    public Boundary getBoundary() { return boundary; }
    public List<BlockPos> getRedSpawns() { return redSpawns; }
    public List<BlockPos> getBlueSpawns() { return blueSpawns; }
    public int getMinPlayers() { return minPlayers; }
    public int getMinRedPlayers() { return Math.max(1, minRedPlayers); }
    public int getMinBluePlayers() { return Math.max(1, minBluePlayers); }
    /** 两队最低人数之和（开局所需的总人数下限） */
    public int getTotalMinPlayers() { return getMinRedPlayers() + getMinBluePlayers(); }
    public int getCooldownSeconds() { return cooldownSeconds; }
    public int getMaxRedPlayers() { return maxRedPlayers; }
    public int getMaxBluePlayers() { return maxBluePlayers; }
    public boolean isReinforceable() { return reinforceable; }
    public int getPrepareTime() { return prepareTime; }
    public int getBoundaryWarningTime() { return boundaryWarningTime; }
    public int getBoundaryPenaltyKills() { return boundaryPenaltyKills; }
    public int getKickCooldownSeconds() { return kickCooldownSeconds; }
    public String getReinforcementMode() {
        return reinforcementMode == null || reinforcementMode.isEmpty() ? "CONDITIONAL" : reinforcementMode;
    }
    /** 是否为"随时可补位"模式，其余值均按条件补位处理 */
    public boolean isAlwaysReinforce() { return "ALWAYS".equalsIgnoreCase(getReinforcementMode()); }
    public String getDimension() {
        // Gson 反序列化不经过构造器，JSON 缺失该字段时为 null，兜底为主世界
        return dimension == null || dimension.isEmpty() ? "minecraft:overworld" : dimension;
    }
    public List<GlobalConfig.ShopItem> getShopItems() {
        // Gson 兜底：旧配置缺失该字段时返回空列表，避免下游 NPE
        return shopItems == null ? List.of() : shopItems;
    }

    public void setId(String id) { this.id = id; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public void setBackgroundBase64(String backgroundBase64) { this.backgroundBase64 = backgroundBase64; }
    public void setWinCondition(WinCondition winCondition) { this.winCondition = winCondition; }
    public void setTargetKills(int targetKills) { this.targetKills = targetKills; }
    public void setMaxDuration(int maxDuration) { this.maxDuration = maxDuration; }
    public void setTieRule(TieRule tieRule) { this.tieRule = tieRule; }
    public void setBoundary(Boundary boundary) { this.boundary = boundary; }
    public void setMinPlayers(int minPlayers) { this.minPlayers = minPlayers; }
    public void setMinRedPlayers(int minRedPlayers) { this.minRedPlayers = Math.max(1, minRedPlayers); }
    public void setMinBluePlayers(int minBluePlayers) { this.minBluePlayers = Math.max(1, minBluePlayers); }
    public void setCooldownSeconds(int cooldownSeconds) { this.cooldownSeconds = cooldownSeconds; }
    public void setMaxRedPlayers(int maxRedPlayers) { this.maxRedPlayers = Math.max(0, maxRedPlayers); }
    public void setMaxBluePlayers(int maxBluePlayers) { this.maxBluePlayers = Math.max(0, maxBluePlayers); }
    public void setReinforceable(boolean reinforceable) { this.reinforceable = reinforceable; }
    public void setPrepareTime(int prepareTime) { this.prepareTime = prepareTime; }
    public void setBoundaryWarningTime(int boundaryWarningTime) { this.boundaryWarningTime = boundaryWarningTime; }
    public void setBoundaryPenaltyKills(int boundaryPenaltyKills) { this.boundaryPenaltyKills = boundaryPenaltyKills; }
    public void setKickCooldownSeconds(int kickCooldownSeconds) { this.kickCooldownSeconds = kickCooldownSeconds; }
    public void setReinforcementMode(String reinforcementMode) { this.reinforcementMode = reinforcementMode; }
    public void setDimension(String dimension) { this.dimension = dimension; }
    public void setShopItems(List<GlobalConfig.ShopItem> shopItems) { this.shopItems = shopItems; }

    /** 【作用】随机选取红队出生点（MatchManager 传送玩家时调用）。 */
    public BlockPos getRandomRedSpawn() {
        return getRandomSpawn(redSpawns);
    }

    /** 【作用】随机选取蓝队出生点（MatchManager 传送玩家时调用）。 */
    public BlockPos getRandomBlueSpawn() {
        return getRandomSpawn(blueSpawns);
    }

    // 随机选点：优先在 5 秒内未被占用的出生点中选取，避免玩家出生重叠；全部占用时退化为纯随机
    private BlockPos getRandomSpawn(List<BlockPos> spawns) {
        if (spawns.isEmpty()) {
            return BlockPos.ORIGIN;
        }
        long now = System.currentTimeMillis();
        List<BlockPos> available = spawns.stream()
                .filter(pos -> {
                    long cooldown = spawnCooldowns.getOrDefault(pos, 0L);
                    return now - cooldown >= 5000;
                })
                .toList();

        if (available.isEmpty()) {
            return spawns.get(new java.util.Random().nextInt(spawns.size()));
        }

        BlockPos selected = available.get(new java.util.Random().nextInt(available.size()));
        spawnCooldowns.put(selected, now);
        return selected;
    }

    // 胜负条件：KILLS=击杀达标获胜，TIMER=时间到按击杀数判定
    public enum WinCondition {
        KILLS,
        TIMER
    }

    // 平局处理：OVERTIME=发起加时投票，DRAW=直接平局
    public enum TieRule {
        OVERTIME,
        DRAW
    }

    /**
     * 【作用】对局区域边界（长方体，方块坐标），越界警告与处决的判定依据，
     * Gson 持久化为 maps.json 中地图的 boundary 对象。
     * 【被谁使用】BoundaryChecker 每 tick 调用 contains/isConfigured 判定越界（服务端）；
     * ModCommands 地图配置指令读写。
     */
    public static class Boundary {
        private int minX, minY, minZ;
        private int maxX, maxY, maxZ;

        public Boundary() {
            this.minX = 0; this.minY = 0; this.minZ = 0;
            this.maxX = 0; this.maxY = 0; this.maxZ = 0;
        }

        public Boundary(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
            this.minX = minX; this.minY = minY; this.minZ = minZ;
            this.maxX = maxX; this.maxY = maxY; this.maxZ = maxZ;
        }

        public int getMinX() { return minX; }
        public int getMinY() { return minY; }
        public int getMinZ() { return minZ; }
        public int getMaxX() { return maxX; }
        public int getMaxY() { return maxY; }
        public int getMaxZ() { return maxZ; }

        public void setMinX(int minX) { this.minX = minX; }
        public void setMinY(int minY) { this.minY = minY; }
        public void setMinZ(int minZ) { this.minZ = minZ; }
        public void setMaxX(int maxX) { this.maxX = maxX; }
        public void setMaxY(int maxY) { this.maxY = maxY; }
        public void setMaxZ(int maxZ) { this.maxZ = maxZ; }

        /**
         * 是否在边界内。坐标向下取整到方块后判定，站在边界方块上不算出界（与配置界面的方块坐标语义一致）；
         * 某一维 Min 大于 Max 时自动交换（Min/Max 填反不再恒判出界）。
         */
        public boolean contains(double x, double y, double z) {
            return inAxis(Math.floor(x), minX, maxX)
                    && inAxis(Math.floor(y), minY, maxY)
                    && inAxis(Math.floor(z), minZ, maxZ);
        }

        /** 按方块坐标判定某一维，Min > Max 时自动交换 */
        private static boolean inAxis(double v, int a, int b) {
            return v >= Math.min(a, b) && v <= Math.max(a, b);
        }

        /** 是否配置过边界（全 0 视为未配置，避免把整张地图判为越界） */
        public boolean isConfigured() {
            return minX != 0 || minY != 0 || minZ != 0
                    || maxX != 0 || maxY != 0 || maxZ != 0;
        }
    }
}