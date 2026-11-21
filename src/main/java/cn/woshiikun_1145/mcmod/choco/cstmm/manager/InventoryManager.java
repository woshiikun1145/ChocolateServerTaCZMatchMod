package cn.woshiikun_1145.mcmod.choco.cstmm.manager;

import cn.woshiikun_1145.mcmod.choco.cstmm.Cstmm;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.MatchSession;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.config.GlobalConfig;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.config.MapConfig;
import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * 【作用】背包快照管理器：玩家进对局前保存整套背包（36 格主背包 + 盔甲 + 副手），
 *         对局结束/离线/被踢后恢复原背包，支持管理员逐格恢复。
 *         持久化到 config/cstmm/data/bags/<uuid>.json（每玩家一文件，临时文件 + 原子替换），
 *         按 AUTO_SAVE_INTERVAL tick 周期自动落盘；损坏/非法命名文件绝不覆盖、绝不删除。
 * 【被谁使用】Cstmm（启动 initialize、停服 saveAll）、MatchManager（开局 saveInventory、
 *           结束/离线/踢出 restoreInventory、竞技标记 setCompetitive）、
 *           EventListener（断线与重连时的快照检查与恢复）、ModCommands（管理员恢复指令）、
 *           MatchScheduler（定时 autoSave）。仅服务端。
 */
public class InventoryManager {
    private static InventoryManager instance;

    /** 未恢复的背包快照，key: 玩家 UUID；启动时从 bags/ 目录载入 */
    private final Map<UUID, InventorySnapshot> savedInventories;
    /** 玩家是否处于竞技模式（进对局置 true，恢复背包后移除） */
    private final Map<UUID, Boolean> competitivePlayers;

    /** 快照目录 config/cstmm/data/bags */
    private final Path bagsDir;
    /** 旧版单文件 bags.json（迁移后重命名留档） */
    private final Path legacyBagsFile;
    /** 加载失败（损坏）的文件名：绝不覆盖、绝不删除，留待管理员修复 */
    private final Set<String> unloadableFiles = new HashSet<>();
    private final Gson gson;
    private final AtomicInteger saveCounter = new AtomicInteger(0);
    /** 自动落盘间隔（秒）：autoSave 由 MatchScheduler 每秒调用一次，每 15 秒落盘一次。
     *  （2026-11-12 修复：旧值 300 按"每 300 tick"语义注释，但调用方实为每秒一次，
     *   导致落盘周期实际为 5 分钟——短对局的快照在内存里生生死死，bags/<uuid>.json 根本没被写过） */
    private static final int AUTO_SAVE_INTERVAL_SECONDS = 15;

    private static RegistryWrapper.WrapperLookup registryLookup;
    private boolean loaded = false;

    private InventoryManager() {
        this.savedInventories = new ConcurrentHashMap<>();
        this.competitivePlayers = new ConcurrentHashMap<>();

        Path configRoot = FabricLoader.getInstance().getConfigDir();
        Path dataDir = configRoot.resolve("cstmm/data");
        this.bagsDir = dataDir.resolve("bags");
        this.legacyBagsFile = dataDir.resolve("bags.json");

        this.gson = new GsonBuilder()
                .setPrettyPrinting()
                .registerTypeAdapter(ItemStack.class, new ItemStackAdapter())
                .create();

        try {
            Files.createDirectories(bagsDir);
        } catch (IOException e) {
            Cstmm.LOGGER.error("[CSTMM - InventoryManager] Failed to create bags directory", e);
        }
    }

    // 单例入口（懒加载）
    public static InventoryManager getInstance() {
        if (instance == null) {
            instance = new InventoryManager();
        }
        return instance;
    }

    // ===== 新增：初始化方法 =====
    /**
     * 【作用】注入注册表查询句柄（ItemStack NBT 序列化必需）并加载 bags/ 目录快照。
     * 【被谁使用】Cstmm（服务器启动时调用一次）。仅服务端。
     */
    public static void initialize(RegistryWrapper.WrapperLookup lookup) {
        registryLookup = lookup;
        getInstance().loadBags();
    }

    private static RegistryWrapper.WrapperLookup getRegistryLookup() {
        return registryLookup;
    }

    // ===== 核心业务方法 =====

    /**
     * 【作用】保存玩家当前整套背包为快照（进对局前调用）；已有未恢复快照时先恢复再保存
     * （自愈历史遗留快照：调用方均为"进入新对局"场景，把快照内容还原成当前背包后重新保存，
     * 保证本次对局必定正常存包——2026-11-12 修复"背包无法正常保存"：旧实现直接拒绝并保留快照，
     * 玩家当前背包被装备清空后无处可退，结算时回滚到陈旧快照）。
     * 【被谁使用】MatchManager（对局开始传送玩家入地图前）。仅服务端。
     */
    public void saveInventory(ServerPlayerEntity player) {
        UUID uuid = player.getUuid();

        // 已有未恢复的快照：先恢复（把真实原背包还给玩家）再走正常保存，快照内容不丢失；
        // 恢复失败（异常路径保留快照供重试）时仍拒绝覆盖，维持"不覆盖未恢复快照"的底线保护
        if (savedInventories.containsKey(uuid)) {
            Cstmm.LOGGER.warn("[CSTMM - InventoryManager] {} has a stale snapshot, restoring it before saving",
                    player.getName());
            if (!restoreInventory(player)) {
                Cstmm.LOGGER.warn("[CSTMM - InventoryManager] {} stale snapshot restore failed, refusing to overwrite",
                        player.getName());
                competitivePlayers.put(uuid, true);
                return;
            }
        }

        ItemStack[] main = new ItemStack[36];
        for (int i = 0; i < 36; i++) {
            main[i] = player.getInventory().getStack(i).copy();
        }
        ItemStack[] armor = new ItemStack[4];
        for (int i = 0; i < 4; i++) {
            armor[i] = player.getInventory().armor.get(i).copy();
        }
        ItemStack offhand = player.getInventory().offHand.getFirst().copy();

        InventorySnapshot snapshot = new InventorySnapshot(main, armor, offhand);
        savedInventories.put(uuid, snapshot);
        competitivePlayers.put(uuid, true);

        // 立即落盘（与 restoreSlot 同理由）：开局存包后若崩溃且未到自动保存周期，
        // 磁盘上没有该快照，重启后玩家原背包永久丢失
        saveBags();

        Cstmm.LOGGER.debug("[CSTMM - InventoryManager] Saved inventory for {}", player.getName());
    }

    /**
     * 【作用】整体恢复玩家原背包：清空当前背包后按快照回填；恢复失败保留快照供下次重试。
     * 【被谁使用】MatchManager（对局结束/玩家离线/被投票踢出）、EventListener（断线重连兜底恢复）、
     *           ModCommands（管理员手动恢复命令）。仅服务端。
     */
    public boolean restoreInventory(ServerPlayerEntity player) {
        UUID uuid = player.getUuid();
        // 先读取快照但暂不移除：恢复失败时保留条目，下次重连可重试
        InventorySnapshot snapshot = savedInventories.get(uuid);
        if (snapshot == null) {
            return false;
        }

        // 清空并恢复：任一步异常都保留快照条目以便下次重试，绝不上抛触发保护模式
        try {
            player.getInventory().clear();
            player.getInventory().armor.clear();
            player.getInventory().offHand.clear();

            for (int i = 0; i < Math.min(36, snapshot.getMainInventory().length); i++) {
                ItemStack stack = snapshot.getMainInventory()[i];
                player.getInventory().setStack(i, stack != null ? stack.copy() : ItemStack.EMPTY);
            }
            for (int i = 0; i < Math.min(4, snapshot.getArmor().length); i++) {
                ItemStack stack = snapshot.getArmor()[i];
                player.getInventory().armor.set(i, stack != null ? stack.copy() : ItemStack.EMPTY);
            }
            ItemStack offhand = snapshot.getOffhand();
            if (offhand != null) {
                player.getInventory().offHand.set(0, offhand.copy());
            }
        } catch (Exception e) {
            // 恢复异常：保留快照条目（不 remove），下次重连可重试
            Cstmm.LOGGER.error("[CSTMM - InventoryManager] Failed to restore inventory for {}, snapshot kept for retry",
                    player.getName(), e);
            return false;
        }

        // 恢复成功后才移除内存快照条目；立即落盘清除磁盘残留——
        // 若崩溃时磁盘仍是旧快照文件，重启加载后玩家会同时获得 player.dat 物品 + 快照物品（复制）
        savedInventories.remove(uuid);
        competitivePlayers.remove(uuid);
        // 落盘失败不应让恢复本身失败，更不能上抛触发保护模式（快照条目已移除，内存状态正确）
        try {
            saveBags();
        } catch (RuntimeException e) {
            Cstmm.LOGGER.error("[CSTMM - InventoryManager] Failed to persist bags after restore for {}", uuid, e);
        }
        Cstmm.LOGGER.debug("[CSTMM - InventoryManager] Restored inventory for {}", player.getName());
        return true;
    }

    /**
     * 【作用】从快照恢复单个格位（0-35 主背包、36-39 盔甲、40 副手），快照中该格清空并立即落盘。
     * 【被谁使用】ModCommands（管理员恢复命令，玩家原背包未整体恢复时可逐格领取物品）。仅服务端。
     */
    public boolean restoreSlot(ServerPlayerEntity player, int slotIndex) {
        UUID uuid = player.getUuid();
        InventorySnapshot snapshot = savedInventories.get(uuid);
        if (snapshot == null) {
            return false;
        }

        // 校验快照数组长度，防止手改 bags/<uuid>.json 导致数组越界
        if (slotIndex >= 0 && slotIndex < 36) {
            if (slotIndex >= snapshot.getMainInventory().length) return false;
            ItemStack stack = snapshot.getMainInventory()[slotIndex];
            player.getInventory().setStack(slotIndex, stack != null ? stack.copy() : ItemStack.EMPTY);
            snapshot.getMainInventory()[slotIndex] = ItemStack.EMPTY;
        } else if (slotIndex >= 36 && slotIndex < 40) {
            int armorIndex = slotIndex - 36;
            if (armorIndex >= snapshot.getArmor().length) return false;
            ItemStack stack = snapshot.getArmor()[armorIndex];
            player.getInventory().armor.set(armorIndex, stack != null ? stack.copy() : ItemStack.EMPTY);
            snapshot.getArmor()[armorIndex] = ItemStack.EMPTY;
        } else if (slotIndex == 40) {
            ItemStack stack = snapshot.getOffhand();
            player.getInventory().offHand.set(0, stack != null ? stack.copy() : ItemStack.EMPTY);
            snapshot.setOffhand(ItemStack.EMPTY);
        } else {
            return false;
        }

        // 立即落盘：快照已被消费，若延迟到自动保存，崩溃后磁盘上仍是旧物品，
        // 重启加载后可再次恢复同一物品（复制）。落盘异常不影响本次单格恢复结果。
        try {
            saveBags();
        } catch (RuntimeException e) {
            Cstmm.LOGGER.error("[CSTMM - InventoryManager] Failed to persist bags after slot restore for {}", uuid, e);
        }

        Cstmm.LOGGER.debug("[CSTMM - InventoryManager] Restored slot {} for {}", slotIndex, player.getName());
        return true;
    }

    // 查询玩家是否有未恢复的快照（EventListener 断线/重连时判断是否需要恢复）
    public boolean hasSavedInventory(UUID uuid) {
        return savedInventories.containsKey(uuid);
    }

    /**
     * 【作用】玩家 bag 磁盘文件路径（bags/<uuid>.json），供管理命令核对"内存快照 + 磁盘文件"
     *         双份保存状态（磁盘文件在 saveInventory/saveBags 落盘后存在、恢复后清理）。
     * 【被谁使用】ModCommands（data get player bags 概要）。仅服务端。
     */
    public Path getBagFile(UUID uuid) {
        return bagsDir.resolve(uuid + ".json");
    }

    /**
     * 【作用】查询玩家已保存的背包快照（无快照返回 null），供管理员命令查看快照概要。
     * 【被谁使用】ModCommands（/cstmm data get player <玩家> bags）。仅服务端。
     */
    public InventorySnapshot getSnapshot(UUID uuid) {
        return uuid == null ? null : savedInventories.get(uuid);
    }

    /**
     * 【作用】删除玩家保存的背包快照（内存移除 + 删除磁盘 bags/<uuid>.json），管理员命令用。
     *         内存移除后下次 saveBags 的残留清理也会兜底删文件，此处立即删除语义更明确。
     * 【被谁使用】ModCommands（/cstmm data delete player ... bags|all）。仅服务端。
     * @return true 表示确实删除了内容（内存快照存在或磁盘文件存在）
     */
    public boolean deleteSavedInventory(UUID uuid) {
        if (uuid == null) return false;
        InventorySnapshot removed = savedInventories.remove(uuid);
        boolean fileDeleted = false;
        try {
            fileDeleted = Files.deleteIfExists(bagsDir.resolve(uuid + ".json"));
        } catch (IOException e) {
            Cstmm.LOGGER.error("[CSTMM - InventoryManager] Failed to delete bag file for {}", uuid, e);
        }
        if (removed != null || fileDeleted) {
            Cstmm.LOGGER.info("[CSTMM - InventoryManager] Saved inventory deleted for {} (inMemory={}, file={})",
                    uuid, removed != null, fileDeleted);
            return true;
        }
        return false;
    }

    // 标记/取消玩家竞技状态（MatchManager 开局置 true、对局结束置 false）
    public void setCompetitive(UUID uuid, boolean competitive) {
        competitivePlayers.put(uuid, competitive);
    }

    // 查询玩家是否处于竞技状态（当前项目内暂无调用方，预留查询入口）
    public boolean isCompetitivePlayer(UUID uuid) {
        return competitivePlayers.getOrDefault(uuid, false);
    }

    // 【作用】计数器达到 AUTO_SAVE_INTERVAL_SECONDS（15 秒）时触发一次落盘（MatchScheduler 每秒调用）
    public void autoSave() {
        int count = saveCounter.incrementAndGet();
        if (count % AUTO_SAVE_INTERVAL_SECONDS == 0) {
            saveBags();
        }
    }

    // 【作用】把全部快照逐玩家落盘到 bags/<uuid>.json（原子替换），并清理已恢复玩家的残留文件
    public void saveBags() {
        if (getRegistryLookup() == null) {
            Cstmm.LOGGER.warn("[CSTMM - InventoryManager] Cannot save bags: RegistryLookup is null");
            return;
        }
        // 逐玩家落盘：bags/<uuid>.json，单文件临时写入 + 原子替换，单文件失败不影响其他玩家
        for (Map.Entry<UUID, InventorySnapshot> entry : savedInventories.entrySet()) {
            Path file = bagsDir.resolve(entry.getKey() + ".json");
            Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
            try (Writer writer = new OutputStreamWriter(Files.newOutputStream(tmp), StandardCharsets.UTF_8)) {
                gson.toJson(entry.getValue(), writer);
            } catch (IOException | RuntimeException e) {
                // Gson 写入失败会抛 JsonIOException（RuntimeException），一并兜住：
                // 单文件落盘失败只记录日志，绝不上抛触发全局保护模式
                Cstmm.LOGGER.error("[CSTMM - InventoryManager] Failed to save {}", file.getFileName(), e);
                continue;
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                try {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e2) {
                    Cstmm.LOGGER.error("[CSTMM - InventoryManager] Failed to replace {}", file.getFileName(), e2);
                }
            }
        }
        // 清理已恢复玩家的残留文件（损坏文件与非法命名文件除外，绝不删除用户数据）
        try (Stream<Path> stream = Files.list(bagsDir)) {
            Set<String> expected = new HashSet<>();
            for (UUID uuid : savedInventories.keySet()) {
                expected.add(uuid + ".json");
            }
            for (Path file : stream.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                String name = file.getFileName().toString();
                if (!expected.contains(name) && !unloadableFiles.contains(name)) {
                    Files.deleteIfExists(file);
                }
            }
        } catch (IOException | RuntimeException e) {
            // Files.list 的迭代在读取目录失败时会抛 UncheckedIOException（RuntimeException）
            Cstmm.LOGGER.error("[CSTMM - InventoryManager] Failed to clean up bags directory", e);
        }
        Cstmm.LOGGER.debug("[CSTMM - InventoryManager] Saved {} inventories to bags/", savedInventories.size());
    }

    // 【作用】启动时从 bags/ 目录加载全部快照进内存（含旧版 bags.json 迁移）；仅执行一次
    public void loadBags() {
        if (loaded) return;
        if (getRegistryLookup() == null) {
            Cstmm.LOGGER.warn("[CSTMM - InventoryManager] Cannot load bags: RegistryLookup is null");
            return;
        }

        // 旧版 bags.json（单文件 Map<UUID, snapshot>）迁移到 bags/<uuid>.json
        migrateLegacyFile();

        if (!Files.exists(bagsDir)) {
            Cstmm.LOGGER.info("[CSTMM - InventoryManager] bags directory not found, starting fresh");
            loaded = true;
            return;
        }

        try (Stream<Path> stream = Files.list(bagsDir)) {
            for (Path file : stream.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                String name = file.getFileName().toString();
                UUID uuid;
                try {
                    uuid = UUID.fromString(name.substring(0, name.length() - 5));
                } catch (IllegalArgumentException e) {
                    // 非法文件名：跳过且不删除
                    unloadableFiles.add(name);
                    Cstmm.LOGGER.warn("[CSTMM - InventoryManager] Skipping bag file with invalid name: {}", name);
                    continue;
                }
                try (Reader reader = new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8)) {
                    InventorySnapshot snapshot = gson.fromJson(reader, InventorySnapshot.class);
                    if (snapshot != null) {
                        savedInventories.put(uuid, snapshot);
                        competitivePlayers.put(uuid, true);
                    }
                } catch (IOException | JsonParseException e) {
                    // 损坏 JSON：不加载、不覆盖、不删除，管理员修复后重启生效
                    unloadableFiles.add(name);
                    Cstmm.LOGGER.error("[CSTMM - InventoryManager] Failed to load {}, kept untouched", name, e);
                }
            }
            Cstmm.LOGGER.info("[CSTMM - InventoryManager] Loaded {} inventories from bags/", savedInventories.size());
        } catch (IOException e) {
            Cstmm.LOGGER.error("[CSTMM - InventoryManager] Failed to list bags directory", e);
        }
        loaded = true;
    }

    // 【作用】把旧版单文件 bags.json 的快照迁到 bags/<uuid>.json，迁移后重命名留档避免重复迁移
    private void migrateLegacyFile() {
        if (!Files.exists(legacyBagsFile)) {
            return;
        }
        try (Reader reader = new InputStreamReader(Files.newInputStream(legacyBagsFile), StandardCharsets.UTF_8)) {
            Map<UUID, InventorySnapshot> legacy = gson.fromJson(reader,
                    new TypeToken<Map<UUID, InventorySnapshot>>() {}.getType());
            if (legacy != null) {
                for (Map.Entry<UUID, InventorySnapshot> entry : legacy.entrySet()) {
                    savedInventories.putIfAbsent(entry.getKey(), entry.getValue());
                    competitivePlayers.put(entry.getKey(), true);
                }
            }
            // 重命名留档，避免下次启动重复迁移；逐玩家文件立即落盘
            Files.move(legacyBagsFile, legacyBagsFile.resolveSibling("bags.json.migrated"),
                    StandardCopyOption.REPLACE_EXISTING);
            saveBags();
            Cstmm.LOGGER.info("[CSTMM - InventoryManager] Migrated legacy bags.json ({} entries) to bags/<uuid>.json",
                    legacy == null ? 0 : legacy.size());
        } catch (IOException | JsonParseException e) {
            // 迁移失败不阻断启动，保留原文件待下次启动重试
            Cstmm.LOGGER.error("[CSTMM - InventoryManager] Failed to migrate legacy bags.json, will retry on next start", e);
        }
    }

    // 停服时全量落盘（Cstmm 服务器停止事件调用）
    public void saveAll() {
        saveBags();
    }

    // ===== ItemStack 序列化适配器（修复版） =====

    /**
     * 【作用】ItemStack 的 Gson 序列化适配器：以物品 NBT 字符串形式与 JSON 互转
     *         （空物品/注册表句柄缺失/解析失败时安全降级为 JsonNull 或 EMPTY）。
     * 【被谁使用】InventoryManager 构造器中注册到 Gson，供快照读写 bags/<uuid>.json 使用。
     */
    private static class ItemStackAdapter implements JsonSerializer<ItemStack>, JsonDeserializer<ItemStack> {
        @Override
        public JsonElement serialize(ItemStack src, Type typeOfSrc, JsonSerializationContext context) {
            if (src == null || src.isEmpty()) {
                return JsonNull.INSTANCE;
            }
            RegistryWrapper.WrapperLookup lookup = getRegistryLookup();
            if (lookup == null) {
                return JsonNull.INSTANCE;
            }
            try {
                NbtElement nbt = src.encode(lookup);
                return new JsonPrimitive(nbt.toString());
            } catch (Exception e) {
                Cstmm.LOGGER.warn("[CSTMM - InventoryManager] Failed to serialize ItemStack", e);
                return JsonNull.INSTANCE;
            }
        }

        @Override
        public ItemStack deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
                throws JsonParseException {
            if (json.isJsonNull()) {
                return ItemStack.EMPTY;
            }
            RegistryWrapper.WrapperLookup lookup = getRegistryLookup();
            if (lookup == null) {
                return ItemStack.EMPTY;
            }
            try {
                String nbtString = json.getAsString();
                NbtElement nbt = NbtHelper.fromNbtProviderString(nbtString);
                if (!(nbt instanceof NbtCompound compound)) {
                    return ItemStack.EMPTY;
                }
                return ItemStack.fromNbt(lookup, compound).orElse(ItemStack.EMPTY);
            } catch (Exception e) {
                Cstmm.LOGGER.warn("[CSTMM - InventoryManager] Failed to deserialize ItemStack", e);
                return ItemStack.EMPTY;
            }
        }
    }

    // ==================== 装备发放与商店购买（原独立类 EquipmentManager 并入本管理器，全部方法与逻辑逐字保留；单例入口统一为 InventoryManager#getInstance） ====================

    /*
     * 装备管理器 - 仅服务端
     * 负责竞技模式装备发放和商店购买（免费）
     * 【作用】按全局配置给竞技玩家发放默认装备（配置缺失时发放钻石套兜底），
     *         并处理商店购买请求（按地图商品配置免费发放物品）。
     * 【被谁使用】MatchManager（开局 setupCompetitiveGear）、NetworkHandler（客户端商店购买请求
     *           givePurchasedItem）。仅服务端。
     */

    /**
     * 发放默认装备给玩家（竞技模式）
     * 【被谁使用】setupCompetitiveGear（开局清空背包后调用）；仅服务端内部使用。
     */
    public void giveDefaultGear(ServerPlayerEntity player) {
        GlobalConfig config = ConfigManager.getInstance().getGlobalConfig();
        if (config == null) {
            Cstmm.LOGGER.warn("[CSTMM - EquipmentManager] Global config not loaded, using fallback gear");
            giveFallbackGear(player);
            return;
        }

        var gearList = config.getDefaultGear();
        if (gearList == null || gearList.isEmpty()) {
            Cstmm.LOGGER.warn("[CSTMM - EquipmentManager] No default gear configured, using fallback");
            giveFallbackGear(player);
            return;
        }
        giveGearList(player, gearList);
    }

    /**
     * 【作用】按地图发放默认装备（爆破图回合重生用）：地图配置了 defaultGear 时优先使用，
     *         未配置（空列表）回退全局默认装备——行为兼容旧地图，后续（阶段⑤）由配置界面
     *         "手持添加"逐图写入并自动迁移落盘。
     * 【被谁使用】BombRoundManager（回合重置给阵亡玩家发装备）。仅服务端。
     */
    public void giveDefaultGear(ServerPlayerEntity player, MapConfig map) {
        if (map != null && !map.getDefaultGear().isEmpty()) {
            giveGearList(player, map.getDefaultGear());
            return;
        }
        giveDefaultGear(player);
    }

    /** 按 EquipSlot 列表逐槽发放装备（giveDefaultGear 两个重载共用）。 */
    private void giveGearList(ServerPlayerEntity player, List<GlobalConfig.EquipSlot> gearList) {
        RegistryWrapper.WrapperLookup lookup = player.getWorld().getRegistryManager();

        for (GlobalConfig.EquipSlot equipSlot : gearList) {
            String slot = equipSlot.getSlot();
            String itemNbt = equipSlot.getItemId();

            // 槽位为 null/空串（JSON 显式 null 等异常数据）直接跳过，避免 NPE 干扰后续条目
            if (slot == null || slot.isBlank()) continue;
            if (itemNbt == null || itemNbt.isEmpty()) continue;

            try {
                ItemStack stack = parseItemStack(itemNbt, lookup);
                if (stack.isEmpty()) continue;
                applyToSlot(player, slot, stack);
            } catch (Exception e) {
                Cstmm.LOGGER.warn("[CSTMM - EquipmentManager] Failed to apply gear to slot {}: {}", slot, e.getMessage());
            }
        }

        Cstmm.LOGGER.debug("[CSTMM - EquipmentManager] Gave default gear to {}", player.getName());
    }

    // 【作用】兜底装备：全局配置缺失/未配置装备时发钻石盔甲套装 + 64 熟牛肉
    private void giveFallbackGear(ServerPlayerEntity player) {
        applyToSlot(player, "head", new ItemStack(net.minecraft.item.Items.DIAMOND_HELMET));
        applyToSlot(player, "chest", new ItemStack(net.minecraft.item.Items.DIAMOND_CHESTPLATE));
        applyToSlot(player, "legs", new ItemStack(net.minecraft.item.Items.DIAMOND_LEGGINGS));
        applyToSlot(player, "feet", new ItemStack(net.minecraft.item.Items.DIAMOND_BOOTS));
        player.getInventory().offerOrDrop(new ItemStack(net.minecraft.item.Items.COOKED_BEEF, 64));
        Cstmm.LOGGER.debug("[CSTMM - EquipmentManager] Gave fallback gear to {}", player.getName());
    }

    // 【作用】按槽位名把物品放到对应装备栏。兼容两种写法：
    //         ① 简写：head/chest/legs/feet/mainhand/offhand（兜底装备与旧配置使用）；
    //         ② 配置界面 /item replace entity 的 <slot> 语法：armor.head/chest/legs/feet/body、
    //            weapon.mainhand/offhand、container.0~35（玩家主背包对应槽位）——此前仅识别
    //            简写，配置界面保存的 armor.* 槽位会落入 default 分支被塞进背包，此处一并修正；
    //         armor.body（玩家无身体盔甲槽）与其余未知槽位塞背包或掉落
    private void applyToSlot(ServerPlayerEntity player, String slot, ItemStack stack) {
        if (stack.isEmpty()) return;

        switch (slot.toLowerCase()) {
            case "head", "armor.head" -> player.getInventory().armor.set(3, stack);
            case "chest", "armor.chest" -> player.getInventory().armor.set(2, stack);
            case "legs", "armor.legs" -> player.getInventory().armor.set(1, stack);
            case "feet", "armor.feet" -> player.getInventory().armor.set(0, stack);
            case "mainhand", "weapon.mainhand" -> player.getInventory().setStack(player.getInventory().selectedSlot, stack);
            case "offhand", "weapon.offhand" -> player.getInventory().offHand.set(0, stack);
            default -> {
                // container.N：替换主背包 N 号槽位（/item replace 语义）；越界/非法退化为塞背包或掉落
                String s = slot.toLowerCase();
                if (s.startsWith("container.")) {
                    try {
                        int idx = Integer.parseInt(s.substring("container.".length()));
                        if (idx >= 0 && idx < player.getInventory().main.size()) {
                            player.getInventory().main.set(idx, stack);
                            return;
                        }
                    } catch (NumberFormatException ignored) {
                        // 非数字后缀，走下方 offerOrDrop
                    }
                }
                player.getInventory().offerOrDrop(stack);
            }
        }
    }

    /**
     * 解析物品字符串为 ItemStack（统一委托 ItemStrings，与服务端/客户端预览同一套规则）。
     * 支持格式：
     * 1. /give 格式（主格式）："minecraft:netherite_sword[minecraft:enchantments={...}] 1"
     * 2. 完整 SNBT："{id:\"minecraft:diamond_sword\",count:1,components:{...}}"（含 1.20 tag 迁移）
     * 3. "物品ID 数量"（如 "minecraft:cooked_beef 64"）与纯物品ID
     */
    private ItemStack parseItemStack(String itemString, RegistryWrapper.WrapperLookup lookup) {
        return cn.woshiikun_1145.mcmod.choco.cstmm.util.ItemStrings.toStack(itemString, lookup);
    }

    /**
     * 【作用】公开的物品字符串解析入口（parseItemStack 的对外包装）：
     *         供 EconomyManager 商店扣费购买复用同一套解析规则（"物品ID 数量" / SNBT）。
     * 【被谁使用】EconomyManager#tryPurchase。仅服务端。
     */
    public ItemStack parseAndBuild(ServerPlayerEntity player, String itemString) {
        if (itemString == null || itemString.isBlank()) return ItemStack.EMPTY;
        try {
            return parseItemStack(itemString, player.getWorld().getRegistryManager());
        } catch (Exception e) {
            Cstmm.LOGGER.warn("[CSTMM - EquipmentManager] parseAndBuild failed: {}", e.getMessage());
            return ItemStack.EMPTY;
        }
    }

    /**
     * 商店购买物品 - 免费发放，不扣费
     * 商品取自玩家所在地图的配置；购买资格按对局会话模式判定（地图配置已无模式）
     * 【被谁使用】NetworkHandler（处理客户端 SHOP_BUY 购买请求 payload）。仅服务端。
     */
    public boolean givePurchasedItem(ServerPlayerEntity player, int itemIndex) {
        MatchSession session = MatchManager.getInstance().getPlayerSession(player.getUuid());
        MapConfig map = session != null
                ? ConfigManager.getInstance().getMap(session.getMapName()) : null;
        // 资格按会话；map 仍需非空以取商品列表
        if (session == null || session.getPhase() == MatchSession.GamePhase.ENDED
                || map == null || !session.isCompetitive()) {
            player.sendMessage(Text.literal("§c仅竞技模式对局中才可购买商品！"), false);
            return false;
        }

        // 商品取平铺分类列（与购买页下发顺序一致；未配置列时回退旧版平铺 shopItems）
        var items = map.getFlatShopItems();
        if (items == null || itemIndex < 0 || itemIndex >= items.size()) {
            return false;
        }

        GlobalConfig.ShopItem shopItem = items.get(itemIndex);
        String itemNbt = shopItem.getItemId();

        if (itemNbt == null || itemNbt.isEmpty()) return false;

        try {
            RegistryWrapper.WrapperLookup lookup = player.getWorld().getRegistryManager();
            ItemStack stack = parseItemStack(itemNbt, lookup);
            if (stack.isEmpty()) return false;

            // 不扣费，直接发放
            player.getInventory().offerOrDrop(stack);
            return true;
        } catch (Exception e) {
            Cstmm.LOGGER.warn("[CSTMM - EquipmentManager] Failed to give purchased item: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 清空玩家背包并给予竞技装备
     * 【被谁使用】MatchManager（对局开始把玩家传送入地图时）。仅服务端。
     */
    public void setupCompetitiveGear(ServerPlayerEntity player) {
        player.getInventory().clear();
        player.getInventory().armor.clear();
        player.getInventory().offHand.clear();
        giveDefaultGear(player);
    }

    /**
     * 【作用】玩家背包快照（内存存储 + 持久化）
     * 对应原 inventory/ 箱子存储
     * 【被谁使用】InventoryManager 在对局开始时保存快照、结束/离队时恢复，
     * 并随 Gson 持久化到背包数据文件（服务端）。
     */
    public static class InventorySnapshot {
        private ItemStack[] mainInventory;      // 主背包 0-35
        private ItemStack[] armor;              // 盔甲 36-39
        private ItemStack offhand;              // 副手 40
        // 快照保存时间戳（毫秒），持久化字段
        private long savedAt;

        // Gson 反序列化所需的无参构造：初始化空背包结构
        public InventorySnapshot() {
            this.mainInventory = new ItemStack[36];
            this.armor = new ItemStack[4];
            this.offhand = ItemStack.EMPTY;
            this.savedAt = System.currentTimeMillis();
        }

        public InventorySnapshot(ItemStack[] mainInventory, ItemStack[] armor, ItemStack offhand) {
            this.mainInventory = mainInventory;
            this.armor = armor;
            this.offhand = offhand;
            this.savedAt = System.currentTimeMillis();
        }

        // Gson 反序列化会用 JSON 中的 null 直接覆盖字段，getter 内兜底为空数组，避免下游 NPE
        public ItemStack[] getMainInventory() { return mainInventory != null ? mainInventory : new ItemStack[0]; }
        public ItemStack[] getArmor() { return armor != null ? armor : new ItemStack[0]; }
        public ItemStack getOffhand() { return offhand != null ? offhand : ItemStack.EMPTY; }
        public long getSavedAt() { return savedAt; }

        public void setMainInventory(ItemStack[] mainInventory) { this.mainInventory = mainInventory; }
        public void setArmor(ItemStack[] armor) { this.armor = armor; }
        public void setOffhand(ItemStack offhand) { this.offhand = offhand; }
        public void setSavedAt(long savedAt) { this.savedAt = savedAt; }

        /** 【作用】快照是否为空（主背包、盔甲、副手全部无物品），InventoryManager 据此跳过无效存档。 */
        public boolean isEmpty() {
            // 逐格检查主背包与盔甲，任一非空即不为空
            for (ItemStack stack : getMainInventory()) {
                if (stack != null && !stack.isEmpty()) return false;
            }
            for (ItemStack stack : getArmor()) {
                if (stack != null && !stack.isEmpty()) return false;
            }
            return getOffhand().isEmpty();
        }

        @Override
        public String toString() {
            return "InventorySnapshot{" +
                    "mainInventory=" + Arrays.toString(mainInventory) +
                    ", armor=" + Arrays.toString(armor) +
                    ", offhand=" + offhand +
                    ", savedAt=" + savedAt +
                    '}';
        }
    }
}