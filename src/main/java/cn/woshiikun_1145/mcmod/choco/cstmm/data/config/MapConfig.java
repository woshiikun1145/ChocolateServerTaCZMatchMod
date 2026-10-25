package cn.woshiikun_1145.mcmod.choco.cstmm.data.config;

import com.google.gson.annotations.SerializedName;
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
    // 对局区域公共边界（越界警告与处决；队伍专属边界未配置时回退到该边界）
    private Boundary boundary;
    /** 红队专属边界（team=1），未配置（全 0）时红队回退使用公共边界 */
    private Boundary redBoundary;
    /** 蓝队专属边界（team=2），未配置（全 0）时蓝队回退使用公共边界 */
    private Boundary blueBoundary;
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
    /** 本图商店商品（旧版平铺列表兜底；新配置统一用 shopColumns 分类列，此字段仅作兼容保留） */
    private List<GlobalConfig.ShopItem> shopItems;
    /** 地图类型：NORMAL=普通击杀/计时图；BOMB=爆破图（红=T 进攻方、蓝=CT 防守方，多回合制） */
    private String gameType;
    /**
     * 商店分类列（购买页网格布局）：普通图的 5 列分类；爆破图下此字段作为 T/CT 未独立配置时的
     * 回退（兼容旧版 bombShopColumns）。旧 JSON 键 bombShopColumns 自动迁移。
     */
    @SerializedName(value = "shopColumns", alternate = "bombShopColumns")
    private List<ShopColumn> shopColumns;
    /** 爆破图购买页 T 方（红队）分类列（第 1 列固定装备列、T 方空列，第 2~5 列可配，最多 4 列） */
    private List<ShopColumn> shopColumnsT;
    /** 爆破图购买页 CT 方（蓝队）分类列（第 1 列固定拆弹装备列，第 2~5 列可配，最多 4 列） */
    private List<ShopColumn> shopColumnsCT;
    /** 爆破图包点列表（普通图为空列表；包点数量无上限） */
    private List<Bombsite> bombsites;
    /** 爆破图炸弹参数（全局可调；普通图忽略） */
    private BombSettings bombSettings;
    /** 本图默认装备（爆破图回合重生/阵亡玩家回装备用；未配置时回退全局默认装备） */
    private List<GlobalConfig.EquipSlot> defaultGear;

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
        this.redBoundary = new Boundary();
        this.blueBoundary = new Boundary();
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
        this.gameType = "NORMAL";
        this.shopColumns = new ArrayList<>();
        this.shopColumnsT = new ArrayList<>();
        this.shopColumnsCT = new ArrayList<>();
        this.bombsites = new ArrayList<>();
        this.bombSettings = new BombSettings();
        this.defaultGear = new ArrayList<>();
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
    public Boundary getRedBoundary() { return redBoundary; }
    public Boundary getBlueBoundary() { return blueBoundary; }

    /**
     * 【作用】取指定队伍生效的边界（BoundaryChecker 越界判定时调用）：
     * 红队（team=1）优先用 redBoundary，蓝队（team=2）优先用 blueBoundary，
     * 队伍专属边界未配置（全 0）或队伍未知（team 非 1/2，如观战/未分组）时回退公共边界。
     */
    public Boundary getBoundaryForTeam(int team) {
        if (team == 1 && redBoundary != null && redBoundary.isConfigured()) return redBoundary;
        if (team == 2 && blueBoundary != null && blueBoundary.isConfigured()) return blueBoundary;
        return boundary;
    }
    public List<BlockPos> getRedSpawns() { return redSpawns; }
    public List<BlockPos> getBlueSpawns() { return blueSpawns; }
    /** 红队出生点整体替换（JSON 显式 null 时由 ModCommands 命令侧编辑兜底重建） */
    public void setRedSpawns(List<BlockPos> redSpawns) { this.redSpawns = redSpawns; }
    /** 蓝队出生点整体替换（同上） */
    public void setBlueSpawns(List<BlockPos> blueSpawns) { this.blueSpawns = blueSpawns; }
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

    // ==================== 地图类型与爆破参数 ====================

    /** 地图类型（Gson 兜底：缺失/null 时按普通图处理） */
    public String getGameType() {
        return gameType == null || gameType.isEmpty() ? "NORMAL" : gameType;
    }

    /** 是否为爆破地图（BombRoundManager 回合机、队列过滤、商店/装备逻辑按此分支） */
    public boolean isBombMap() {
        return "BOMB".equalsIgnoreCase(getGameType());
    }

    public void setGameType(String gameType) {
        this.gameType = "BOMB".equalsIgnoreCase(gameType) ? "BOMB" : "NORMAL";
        // 切为普通图时清空包点，避免残留数据在误改回普通图时被引用
        if (!isBombMap()) {
            this.bombsites = new ArrayList<>();
        }
    }

    /** 包点列表（Gson 兜底：缺失/null 返回空列表；数量无上限） */
    public List<Bombsite> getBombsites() {
        return bombsites == null ? List.of() : bombsites;
    }

    public void setBombsites(List<Bombsite> bombsites) { this.bombsites = bombsites; }

    /** 炸弹参数（Gson 兜底：缺失/null 时以默认值对象兜底） */
    public BombSettings getBombSettings() {
        return bombSettings == null ? new BombSettings() : bombSettings;
    }

    public void setBombSettings(BombSettings bombSettings) { this.bombSettings = bombSettings; }

    /** 商店分类列（Gson 兜底：缺失/null 返回空列表；普通图最多 5 列生效） */
    public List<ShopColumn> getShopColumns() {
        return shopColumns == null ? List.of() : shopColumns;
    }

    public void setShopColumns(List<ShopColumn> shopColumns) { this.shopColumns = shopColumns; }

    /** 爆破图 T 方（红队）分类列（Gson 兜底：缺失/null 返回空列表；最多 4 列生效） */
    public List<ShopColumn> getShopColumnsT() {
        return shopColumnsT == null ? List.of() : shopColumnsT;
    }

    public void setShopColumnsT(List<ShopColumn> shopColumnsT) { this.shopColumnsT = shopColumnsT; }

    /** 爆破图 CT 方（蓝队）分类列（Gson 兜底：缺失/null 返回空列表；最多 4 列生效） */
    public List<ShopColumn> getShopColumnsCT() {
        return shopColumnsCT == null ? List.of() : shopColumnsCT;
    }

    public void setShopColumnsCT(List<ShopColumn> shopColumnsCT) { this.shopColumnsCT = shopColumnsCT; }

    /**
     * 【作用】爆破图按队伍取购买页分类列（NetworkHandler 下发与 EconomyManager 购买共用，
     *         保证两端的列/索引一一对应）：T 方取 shopColumnsT、CT 方取 shopColumnsCT；
     *         对应队伍未独立配置（空列表）时回退通用 shopColumns（兼容旧版单套配置）。
     */
    public List<ShopColumn> getBombShopColumnsForTeam(boolean tTeam) {
        List<ShopColumn> cols = tTeam ? shopColumnsT : shopColumnsCT;
        return (cols != null && !cols.isEmpty()) ? cols : getShopColumns();
    }

    /**
     * 【作用】商店商品平铺列表（普通图购买索引定位用）：按分类列顺序拼接全部商品；
     *         未配置分类列时回退旧版平铺 shopItems。
     * 【被谁使用】EquipmentManager.givePurchasedItem（普通图免费发放）、ConfigScreen 保存前预校验。
     */
    public List<GlobalConfig.ShopItem> getFlatShopItems() {
        List<ShopColumn> cols = getShopColumns();
        if (!cols.isEmpty()) {
            List<GlobalConfig.ShopItem> flat = new ArrayList<>();
            for (ShopColumn col : cols) {
                flat.addAll(col.getItems());
            }
            if (!flat.isEmpty()) return flat;
        }
        return getShopItems();
    }

    /**
     * 【作用】爆破图按队伍取平铺商品列表（购买索引定位用，与该队伍购买页下发顺序一致）：
     *         平铺 getBombShopColumnsForTeam 的列；列与旧版 shopItems 均为空时回退 shopItems。
     * 【被谁使用】EconomyManager.tryPurchase（按购买索引发放）、ConfigScreen 保存前预校验。
     */
    public List<GlobalConfig.ShopItem> getBombFlatShopItems(boolean tTeam) {
        List<ShopColumn> cols = getBombShopColumnsForTeam(tTeam);
        if (!cols.isEmpty()) {
            List<GlobalConfig.ShopItem> flat = new ArrayList<>();
            for (ShopColumn col : cols) {
                flat.addAll(col.getItems());
            }
            if (!flat.isEmpty()) return flat;
        }
        return getShopItems();
    }

    /**
     * 【作用】商店分类列（购买页网格布局的一列）：列头名称与图标可配，列内商品在购买页上下滚动。
     * 【被谁使用】NetworkHandler（购买页数据组装）、EconomyManager（拼接索引购买）、
     *           ConfigScreen（分类列编辑器）、ShopScreen（5 列网格渲染）。
     */
    public static class ShopColumn {
        /** 列头名称（如"步枪"） */
        private String name = "分类";
        /** 列头图标：base64 / http(s) URL / 空（不显示图标） */
        private String icon = "";
        /** 该列商品（物品串/价格/显示名/图标/队伍，购买按拼接索引定位） */
        private List<GlobalConfig.ShopItem> items = new ArrayList<>();

        public ShopColumn() {}

        public ShopColumn(String name) { this.name = name; }

        public ShopColumn(String name, List<GlobalConfig.ShopItem> items) {
            this.name = name;
            this.items = items == null ? new ArrayList<>() : new ArrayList<>(items);
        }

        public String getName() { return name == null || name.isBlank() ? "分类" : name; }
        public String getIcon() { return icon == null ? "" : icon; }
        public List<GlobalConfig.ShopItem> getItems() { return items == null ? new ArrayList<>() : items; }

        public void setName(String name) { this.name = name; }
        public void setIcon(String icon) { this.icon = icon; }
        public void setItems(List<GlobalConfig.ShopItem> items) { this.items = items; }
    }

    /** 本图默认装备（Gson 兜底：缺失/null 返回空列表；空 = 回退全局默认装备） */
    public List<GlobalConfig.EquipSlot> getDefaultGear() {
        return defaultGear == null ? List.of() : defaultGear;
    }

    public void setDefaultGear(List<GlobalConfig.EquipSlot> defaultGear) { this.defaultGear = defaultGear; }

    public void setId(String id) { this.id = id; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public void setBackgroundBase64(String backgroundBase64) { this.backgroundBase64 = backgroundBase64; }
    public void setWinCondition(WinCondition winCondition) { this.winCondition = winCondition; }
    public void setTargetKills(int targetKills) { this.targetKills = targetKills; }
    public void setMaxDuration(int maxDuration) { this.maxDuration = maxDuration; }
    public void setTieRule(TieRule tieRule) { this.tieRule = tieRule; }
    public void setBoundary(Boundary boundary) { this.boundary = boundary; }
    public void setRedBoundary(Boundary redBoundary) { this.redBoundary = redBoundary; }
    public void setBlueBoundary(Boundary blueBoundary) { this.blueBoundary = blueBoundary; }
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

        // 用 ThreadLocalRandom 复用线程级随机源，避免每次选点都新建 Random 实例
        if (available.isEmpty()) {
            return spawns.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(spawns.size()));
        }

        BlockPos selected = available.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(available.size()));
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
     * Gson 持久化为 maps.json 中地图的 boundary / redBoundary / blueBoundary 对象。
     * 【被谁使用】BoundaryChecker 按队伍取 getBoundaryForTeam 后调用 contains/isConfigured 判定越界（服务端）；
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

    /**
     * 【作用】爆破图包点（C4 可安放区域）：名称（如 A/B，用于 HUD 与消息展示）、
     * 中心方块坐标与判定半径（立方体半边长，三轴 |坐标差| 均不超过半径即在包点内）。
     * 【被谁使用】BombRoundManager 安放判定、爆炸定位；配置界面（阶段⑤）可视化编辑。
     */
    public static class Bombsite {
        /** 包点名（"A"/"B"/自定义，非空） */
        private String name;
        /** 包点中心 X/Y/Z（方块坐标） */
        private int x, y, z;
        /** 安放判定半径（立方体半边长，方块；三轴 |坐标差| ≤ 半径即在包点内；<=0 时按 3 处理） */
        private int radius;

        public Bombsite() {
            this.name = "A";
            this.x = 0; this.y = 0; this.z = 0;
            this.radius = 3;
        }

        public Bombsite(String name, int x, int y, int z, int radius) {
            this.name = name == null || name.isBlank() ? "A" : name;
            this.x = x; this.y = y; this.z = z;
            this.radius = radius;
        }

        public String getName() { return name == null || name.isBlank() ? "A" : name; }
        public int getX() { return x; }
        public int getY() { return y; }
        public int getZ() { return z; }
        /** 安放判定半径（非正数按 3 兜底，防止包点退化成不可用） */
        public int getRadius() { return Math.max(3, radius); }
        public void setName(String name) { this.name = name; }
        public void setX(int x) { this.x = x; }
        public void setY(int y) { this.y = y; }
        public void setZ(int z) { this.z = z; }
        public void setRadius(int radius) { this.radius = radius; }

        /** 玩家坐标是否在包点判定范围内（立方体：三轴 |坐标差| 均不超过半径；半径为方块尺度） */
        public boolean contains(double px, double py, double pz) {
            double dx = Math.abs(px - (x + 0.5)), dy = Math.abs(py - (y + 0.5)), dz = Math.abs(pz - (z + 0.5));
            int r = getRadius();
            return dx <= r && dy <= r && dz <= r;
        }
    }

    /**
     * 【作用】爆破图炸弹参数（每图可调，Gson 持久化）。
     * 距离类参数单位=方块（1 方块≈1m）；伤害参数对应《C4爆炸计算.md》模型，
     * 实际对 MC 20 HP 结算时按 ÷5 向下取整换算（D_max=51、致死 ≥20）。
     * 【被谁使用】BombRoundManager 回合计时与安放/拆除、C4DamageCalculator（阶段④）。
     */
    public static class BombSettings {
        /** 未安放时每回合限时（秒），到时未安放 CT 胜；默认 120（CS 1:55，可按图调整） */
        private int roundTimeSeconds = 120;
        /** C4 安放后的爆炸倒计时（秒）；CS2 固定 40 秒 */
        private int c4TimerSeconds = 40;
        /** 安放 C4 所需长按时间（秒） */
        private int plantTimeSeconds = 3;
        /** 裸拆所需时间（秒，CS 标准 10s） */
        private int defuseSeconds = 10;
        /** 持拆弹装备拆除时间（秒，CS 标准 5s） */
        private int defuseKitSeconds = 5;
        /**
         * 爆炸伤害模型参数（新版公式，MC 20 HP 口径）：
         * m  = 伤害衰减系数（d1 = -m·l² + h 抛物线衰减，默认 0.2 → 零伤距离 √(h/m) ≈ 16 格）；
         * h  = 基础爆炸伤害（l=0 处，默认 51）；
         * lA = 开始计算掩体减伤的距离（l < lA 满伤害，默认 10）；
         * lB = 停止计算减伤的距离（l ≥ lB 减伤率 p=0，默认 16）；
         * dB = 背对爆炸的固定减伤（默认 0.6）。
         * eCR 不需要参数：实体碰撞箱未遮挡表面积占比（原版爆炸暴露判定），
         * 修正 eCRB：eCR ≥ 0.5 时取 1/eCR（半暴露 ×2 加成），< 0.5 取 0.5。
         */
        private double explosionM = 0.2;
        private double explosionH = 51.0;
        private double explosionLA = 10.0;
        private double explosionLB = 16.0;
        private double explosionBackDamage = 0.6;
        /** 爆炸音效可闻半径（方块，默认 64；播放音量 = 半径 / 16） */
        private int explosionSoundRange = 64;
        /** 回合间歇时长（秒，默认 5）：回合结束后等待再开始下一回合的时间 */
        private int roundIntermissionSeconds = 5;

        public int getRoundTimeSeconds() { return Math.max(15, roundTimeSeconds); }
        public int getC4TimerSeconds() { return Math.max(10, c4TimerSeconds); }
        public int getPlantTimeSeconds() { return Math.max(1, plantTimeSeconds); }
        public int getDefuseSeconds() { return Math.max(1, defuseSeconds); }
        public int getDefuseKitSeconds() { return Math.max(1, defuseKitSeconds); }
        public double getExplosionM() { return explosionM > 0 ? explosionM : 0.2; }
        public double getExplosionH() { return explosionH > 0 ? explosionH : 51.0; }
        public double getExplosionLA() { return explosionLA >= 0 ? explosionLA : 10.0; }
        public double getExplosionLB() { return explosionLB > 0 ? explosionLB : 16.0; }
        public double getExplosionBackDamage() { return explosionBackDamage >= 0 ? explosionBackDamage : 0.6; }
        public int getExplosionSoundRange() { return Math.max(8, explosionSoundRange); }
        public int getRoundIntermissionSeconds() { return Math.max(0, roundIntermissionSeconds); }

        public void setRoundTimeSeconds(int v) { this.roundTimeSeconds = v; }
        public void setC4TimerSeconds(int v) { this.c4TimerSeconds = v; }
        public void setPlantTimeSeconds(int v) { this.plantTimeSeconds = v; }
        public void setDefuseSeconds(int v) { this.defuseSeconds = v; }
        public void setDefuseKitSeconds(int v) { this.defuseKitSeconds = v; }
        public void setExplosionM(double v) { this.explosionM = v; }
        public void setExplosionH(double v) { this.explosionH = v; }
        public void setExplosionLA(double v) { this.explosionLA = v; }
        public void setExplosionLB(double v) { this.explosionLB = v; }
        public void setExplosionBackDamage(double v) { this.explosionBackDamage = v; }
        public void setExplosionSoundRange(int v) { this.explosionSoundRange = v; }
        public void setRoundIntermissionSeconds(int v) { this.roundIntermissionSeconds = v; }
    }
}