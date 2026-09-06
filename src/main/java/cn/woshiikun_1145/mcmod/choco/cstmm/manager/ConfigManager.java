package cn.woshiikun_1145.mcmod.choco.cstmm.manager;

import cn.woshiikun_1145.mcmod.choco.cstmm.Cstmm;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.config.GlobalConfig;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.config.MapConfig;
import cn.woshiikun_1145.mcmod.choco.cstmm.util.BlockPosAdapter;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.math.BlockPos;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 配置管理器 - 仅服务端
 * 管理 maps.json 和 global.json
 */
public class ConfigManager {
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .serializeNulls()
            .registerTypeAdapter(BlockPos.class, new BlockPosAdapter())
            .create();

    private static ConfigManager instance;

    private final Path configDir;
    private final Path mapsPath;
    private final Path globalPath;

    private List<MapConfig> maps;
    private GlobalConfig globalConfig;

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private ConfigManager() {
        Path configRoot = FabricLoader.getInstance().getConfigDir();
        this.configDir = configRoot.resolve("cstmm/configs");
        this.mapsPath = configDir.resolve("maps.json");
        this.globalPath = configDir.resolve("global.json");

        this.maps = new ArrayList<>();
        this.globalConfig = new GlobalConfig();

        try {
            Files.createDirectories(configDir);
        } catch (IOException e) {
            Cstmm.LOGGER.error("[CSTMM - ConfigManager] Failed to create config directory", e);
        }
    }

    public static ConfigManager getInstance() {
        if (instance == null) {
            instance = new ConfigManager();
        }
        return instance;
    }

    /**
     * 加载所有配置
     */
    public void load() {
        lock.writeLock().lock();
        try {
            loadMaps();
            loadGlobal();
            Cstmm.LOGGER.info("[CSTMM - ConfigManager] Loaded {} maps and global config", maps.size());
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 保存所有配置
     */
    public void save() {
        lock.writeLock().lock();
        try {
            saveMaps();
            saveGlobal();
            Cstmm.LOGGER.info("[CSTMM - ConfigManager] Saved all configurations");
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ========== Maps ==========

    private void loadMaps() {
        if (!Files.exists(mapsPath)) {
            Cstmm.LOGGER.info("[CSTMM - ConfigManager] maps.json not found, creating default...");
            createDefaultMaps();
            saveMaps();
            return;
        }

        try (Reader reader = new InputStreamReader(Files.newInputStream(mapsPath), StandardCharsets.UTF_8)) {
            JsonArray arr = GSON.fromJson(reader, JsonArray.class);
            if (arr != null) {
                // 逐条跳过 null（坏坐标被 BlockPosAdapter 置 null）或无效条目，
                // 绝不让单条坏数据导致整个文件加载失败后被空配置回写
                List<MapConfig> valid = new ArrayList<>(arr.size());
                for (int i = 0; i < arr.size(); i++) {
                    JsonElement el = arr.get(i);
                    MapConfig map = (el == null) ? null : GSON.fromJson(el, MapConfig.class);
                    if (map == null) {
                        Cstmm.LOGGER.warn("[CSTMM - ConfigManager] maps.json entry #{} is null or malformed, skipping it", i);
                        continue;
                    }
                    if (map.getId() == null || map.getId().isBlank()) {
                        Cstmm.LOGGER.warn("[CSTMM - ConfigManager] maps.json entry #{} has no id, skipping it", i);
                        continue;
                    }
                    // 旧版本迁移：仅有 minPlayers（总最低人数）时，按每队 minPlayers/2 推导两队最低人数
                    if (el.isJsonObject() && !el.getAsJsonObject().has("minRedPlayers")
                            && !el.getAsJsonObject().has("minBluePlayers") && el.getAsJsonObject().has("minPlayers")) {
                        int legacy = Math.max(1, map.getMinPlayers() / 2);
                        map.setMinRedPlayers(legacy);
                        map.setMinBluePlayers(legacy);
                        Cstmm.LOGGER.info("[CSTMM - ConfigManager] Migrated map '{}': minPlayers={} -> minRed/minBlue={}/{}",
                                map.getId(), map.getMinPlayers(), legacy, legacy);
                    }
                    valid.add(map);
                }
                this.maps = valid;
                Cstmm.LOGGER.info("[CSTMM - ConfigManager] Loaded {} maps", maps.size());
            } else {
                // 空文件：仅在内存中使用默认配置，不回写，避免覆盖用户文件
                Cstmm.LOGGER.warn("[CSTMM - ConfigManager] maps.json is empty, using in-memory defaults (file kept untouched)");
                createDefaultMaps();
            }
        } catch (Exception e) {
            // JSON 语法错误（JsonSyntaxException）或 IO 故障：保留内存配置，绝不回写覆盖用户文件
            Cstmm.LOGGER.error("[CSTMM - ConfigManager] Failed to load maps.json, keeping current config", e);
        }
    }

    /**
     * 校验地图是否可写盘：redSpawns/blueSpawns 为 null 或空会导致开局取出生点异常，
     * 无 id 的地图无法被后续更新/删除定位。无效时 warn 并返回 false。
     */
    private boolean isValidMapForSave(MapConfig map) {
        if (map == null) {
            Cstmm.LOGGER.warn("[CSTMM - ConfigManager] Rejected saving null map entry");
            return false;
        }
        String label = map.getId() == null || map.getId().isBlank() ? "<no-id>" : map.getId();
        if (map.getRedSpawns() == null || map.getRedSpawns().isEmpty()) {
            Cstmm.LOGGER.warn("[CSTMM - ConfigManager] Rejected saving map '{}': redSpawns is null or empty", label);
            return false;
        }
        if (map.getBlueSpawns() == null || map.getBlueSpawns().isEmpty()) {
            Cstmm.LOGGER.warn("[CSTMM - ConfigManager] Rejected saving map '{}': blueSpawns is null or empty", label);
            return false;
        }
        return true;
    }

    private void saveMaps() {
        // 写盘之前校验：无效地图不落盘（内存列表保留原值，等管理员修正后可再保存）
        List<MapConfig> toSave = new ArrayList<>(maps.size());
        for (MapConfig map : maps) {
            if (isValidMapForSave(map)) {
                toSave.add(map);
            }
        }
        try (Writer writer = new OutputStreamWriter(Files.newOutputStream(mapsPath), StandardCharsets.UTF_8)) {
            GSON.toJson(toSave, writer);
        } catch (IOException e) {
            Cstmm.LOGGER.error("[CSTMM - ConfigManager] Failed to save maps.json", e);
        }
    }

    /** 为单张地图填充默认商品（商店按地图各一份） */
    private void addDefaultShopItems(MapConfig map) {
        map.getShopItems().add(new GlobalConfig.ShopItem("minecraft:diamond_sword", 0, 0));
        map.getShopItems().add(new GlobalConfig.ShopItem("minecraft:bow", 0, 0));
        map.getShopItems().add(new GlobalConfig.ShopItem("minecraft:cooked_beef 64", 0, 0));
        map.getShopItems().add(new GlobalConfig.ShopItem("minecraft:arrow 64", 0, 0));
        map.getShopItems().add(new GlobalConfig.ShopItem("minecraft:totem_of_undying", 0, 0));
    }

    private void createDefaultMaps() {
        this.maps = new ArrayList<>();

        // 1v1 地图
        MapConfig map1v1 = new MapConfig();
        map1v1.setId("1v1");
        map1v1.setDisplayName("1v1 竞技场");
        map1v1.setEnabled(true);
        map1v1.setWinCondition(MapConfig.WinCondition.KILLS);
        map1v1.setTargetKills(10);
        map1v1.setMinRedPlayers(1);
        map1v1.setMinBluePlayers(1);
        map1v1.setBackgroundBase64("");
        map1v1.getBoundary().setMinX(-6200); map1v1.getBoundary().setMinY(120); map1v1.getBoundary().setMinZ(-6500);
        map1v1.getBoundary().setMaxX(-6100); map1v1.getBoundary().setMaxY(130); map1v1.getBoundary().setMaxZ(-6200);
        map1v1.getRedSpawns().add(new BlockPos(-6154, 125, -6483));
        map1v1.getBlueSpawns().add(new BlockPos(-6154, 125, -6250));
        addDefaultShopItems(map1v1);
        maps.add(map1v1);

        // 花园 地图
        MapConfig mapGarden = new MapConfig();
        mapGarden.setId("garden");
        mapGarden.setDisplayName("花园");
        mapGarden.setEnabled(true);
        mapGarden.setWinCondition(MapConfig.WinCondition.KILLS);
        mapGarden.setTargetKills(10);
        mapGarden.setMinRedPlayers(1);
        mapGarden.setMinBluePlayers(1);
        mapGarden.setBackgroundBase64("");
        mapGarden.getBoundary().setMinX(-6400); mapGarden.getBoundary().setMinY(195); mapGarden.getBoundary().setMinZ(2060);
        mapGarden.getBoundary().setMaxX(-6370); mapGarden.getBoundary().setMaxY(210); mapGarden.getBoundary().setMaxZ(2110);
        mapGarden.getRedSpawns().add(new BlockPos(-6388, 204, 2100));
        mapGarden.getBlueSpawns().add(new BlockPos(-6417, 200, 2071));
        addDefaultShopItems(mapGarden);
        maps.add(mapGarden);

        // 小镇 地图
        MapConfig mapTown = new MapConfig();
        mapTown.setId("town");
        mapTown.setDisplayName("炼狱小镇");
        mapTown.setEnabled(true);
        mapTown.setWinCondition(MapConfig.WinCondition.TIMER);
        mapTown.setMaxDuration(1800);
        mapTown.setTieRule(MapConfig.TieRule.OVERTIME);
        mapTown.setMinRedPlayers(2);
        mapTown.setMinBluePlayers(2);
        mapTown.setReinforceable(true);
        mapTown.setBackgroundBase64("");
        mapTown.getBoundary().setMinX(-6280); mapTown.getBoundary().setMinY(60); mapTown.getBoundary().setMinZ(-6570);
        mapTown.getBoundary().setMaxX(-6120); mapTown.getBoundary().setMaxY(70); mapTown.getBoundary().setMaxZ(-6480);
        mapTown.getRedSpawns().add(new BlockPos(-6274, 67, -6497));
        mapTown.getBlueSpawns().add(new BlockPos(-6135, 62, -6558));
        addDefaultShopItems(mapTown);
        maps.add(mapTown);

        Cstmm.LOGGER.info("[CSTMM - ConfigManager] Created default maps (1v1, garden, town)");
    }

    // ========== Global ==========

    private void loadGlobal() {
        if (!Files.exists(globalPath)) {
            Cstmm.LOGGER.info("[CSTMM - ConfigManager] global.json not found, creating default...");
            createDefaultGlobal();
            saveGlobal();
            return;
        }

        try (Reader reader = new InputStreamReader(Files.newInputStream(globalPath), StandardCharsets.UTF_8)) {
            GlobalConfig loaded = GSON.fromJson(reader, GlobalConfig.class);
            if (loaded != null) {
                // JSON 中显式 null 列表字段会覆盖构造器兜底，这里恢复为空列表避免下游 NPE
                if (loaded.getDefaultGear() == null) loaded.setDefaultGear(new ArrayList<>());
                this.globalConfig = loaded;
                Cstmm.LOGGER.info("[CSTMM - ConfigManager] Loaded global config with {} gear items",
                        globalConfig.getDefaultGear().size());
            } else {
                Cstmm.LOGGER.warn("[CSTMM - ConfigManager] global.json is empty, using in-memory defaults (file kept untouched)");
                createDefaultGlobal();
            }
        } catch (Exception e) {
            // JSON 语法错误或 IO 故障：保留内存配置，绝不回写覆盖用户文件
            Cstmm.LOGGER.error("[CSTMM - ConfigManager] Failed to load global.json, keeping current config", e);
        }
    }

    private void saveGlobal() {
        try (Writer writer = new OutputStreamWriter(Files.newOutputStream(globalPath), StandardCharsets.UTF_8)) {
            GSON.toJson(globalConfig, writer);
        } catch (IOException e) {
            Cstmm.LOGGER.error("[CSTMM - ConfigManager] Failed to save global.json", e);
        }
    }

    private void createDefaultGlobal() {
        this.globalConfig = new GlobalConfig();

        // ========== 默认发放装备 ==========
        // 注意：商店商品已迁移到每张地图的配置（见 MapConfig.shopItems，默认值在 createDefaultMaps 中填充）
        globalConfig.getDefaultGear().add(new GlobalConfig.EquipSlot("head", "minecraft:diamond_helmet"));
        globalConfig.getDefaultGear().add(new GlobalConfig.EquipSlot("chest", "minecraft:diamond_chestplate"));
        globalConfig.getDefaultGear().add(new GlobalConfig.EquipSlot("legs", "minecraft:diamond_leggings"));
        globalConfig.getDefaultGear().add(new GlobalConfig.EquipSlot("feet", "minecraft:diamond_boots"));

        // ========== 新增：默认游戏参数 ==========
        // 注意：准备时间、边界警告/惩罚、踢人冷却、补位规则已迁移到 MapConfig（按地图配置）
        globalConfig.setQuickTimeout(30);

        Cstmm.LOGGER.info("[CSTMM - ConfigManager] Created default global config with game parameters");
    }

    // ========== Public Accessors ==========

    public List<MapConfig> getMaps() {
        lock.readLock().lock();
        try {
            return new ArrayList<>(maps);
        } finally {
            lock.readLock().unlock();
        }
    }

    public MapConfig getMap(String id) {
        lock.readLock().lock();
        try {
            return maps.stream()
                    .filter(m -> m.getId().equals(id))
                    .findFirst()
                    .orElse(null);
        } finally {
            lock.readLock().unlock();
        }
    }

    public void updateMap(MapConfig map) {
        // 校验放在写盘之前：无效地图直接拒绝（不更新内存、不落盘），保存入口拿不到玩家，仅 warn + 拒绝
        if (!isValidMapForSave(map)) {
            return;
        }
        lock.writeLock().lock();
        try {
            for (int i = 0; i < maps.size(); i++) {
                if (maps.get(i).getId().equals(map.getId())) {
                    maps.set(i, map);
                    saveMaps();
                    return;
                }
            }
            maps.add(map);
            saveMaps();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void removeMap(String id) {
        lock.writeLock().lock();
        try {
            maps.removeIf(m -> m.getId().equals(id));
            saveMaps();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 返回共享实例（调用方只读，不得修改）。
     * 该方法被每秒 tick（边界检测等）高频调用，避免每次 JSON 序列化+反序列化深拷贝的开销。
     */
    public GlobalConfig getGlobalConfig() {
        lock.readLock().lock();
        try {
            return globalConfig;
        } finally {
            lock.readLock().unlock();
        }
    }

    public void updateGlobalConfig(GlobalConfig config) {
        lock.writeLock().lock();
        try {
            this.globalConfig = config;
            saveGlobal();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 重新加载配置（保留运行时状态）
     */
    public void reload() {
        Cstmm.LOGGER.info("[CSTMM - ConfigManager] Reloading configurations...");
        lock.writeLock().lock();
        try {
            loadMaps();
            loadGlobal();
        } finally {
            lock.writeLock().unlock();
        }
        Cstmm.LOGGER.info("[CSTMM - ConfigManager] Reloaded {} maps and global config", maps.size());
    }
}