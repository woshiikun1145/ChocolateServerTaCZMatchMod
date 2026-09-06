package cn.woshiikun_1145.mcmod.choco.cstmm.data.config;

import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

public class MapConfig {
    private String id;
    private String displayName;
    private boolean enabled;
    private String backgroundBase64;
    private WinCondition winCondition;
    private int targetKills;
    private int maxDuration;
    private TieRule tieRule;
    private Boundary boundary;
    private List<BlockPos> redSpawns;
    private List<BlockPos> blueSpawns;
    private int minPlayers;
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

    private transient final java.util.Map<BlockPos, Long> spawnCooldowns;

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

    public BlockPos getRandomRedSpawn() {
        return getRandomSpawn(redSpawns);
    }

    public BlockPos getRandomBlueSpawn() {
        return getRandomSpawn(blueSpawns);
    }

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

    public enum WinCondition {
        KILLS,
        TIMER
    }

    public enum TieRule {
        OVERTIME,
        DRAW
    }

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