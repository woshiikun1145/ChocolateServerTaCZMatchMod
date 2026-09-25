package cn.woshiikun_1145.mcmod.choco.cstmm.manager;

import cn.woshiikun_1145.mcmod.choco.cstmm.Cstmm;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.InventorySnapshot;
import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.network.ServerPlayerEntity;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.lang.reflect.Type;
import java.util.HashSet;
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
    /** 自动落盘间隔（tick）：每 300 tick（15 秒）随 MatchScheduler 调用 autoSave 落盘一次 */
    private static final int AUTO_SAVE_INTERVAL = 300;

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
     * 【作用】保存玩家当前整套背包为快照（进对局前调用）；已有未恢复快照时拒绝覆盖（防原物品丢失）。
     * 【被谁使用】MatchManager（对局开始传送玩家入地图前）。仅服务端。
     */
    public void saveInventory(ServerPlayerEntity player) {
        UUID uuid = player.getUuid();

        // 已有未恢复的快照时不得覆盖：旧快照才是玩家真正的原背包，
        // 覆盖会导致原物品永久丢失（如上次对局异常退出未恢复的场景）
        if (savedInventories.containsKey(uuid)) {
            Cstmm.LOGGER.warn("[CSTMM - InventoryManager] {} already has an unrestored snapshot, keeping it",
                    player.getName());
            competitivePlayers.put(uuid, true);
            return;
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

        // 第一步：清空玩家背包
        player.getInventory().clear();
        player.getInventory().armor.clear();
        player.getInventory().offHand.clear();

        // 第二步：从快照（内存 map，服务启动时已从 bags/<uuid>.json 载入）读取数组并恢复
        try {
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

        // 恢复成功后才移除内存快照条目
        savedInventories.remove(uuid);
        competitivePlayers.remove(uuid);
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
        // 重启加载后可再次恢复同一物品（复制）
        saveBags();

        Cstmm.LOGGER.debug("[CSTMM - InventoryManager] Restored slot {} for {}", slotIndex, player.getName());
        return true;
    }

    // 查询玩家是否有未恢复的快照（EventListener 断线/重连时判断是否需要恢复）
    public boolean hasSavedInventory(UUID uuid) {
        return savedInventories.containsKey(uuid);
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

    // 【作用】计数器达到 AUTO_SAVE_INTERVAL（300 tick）时触发一次落盘（MatchScheduler 每 tick 调用）
    public void autoSave() {
        int count = saveCounter.incrementAndGet();
        if (count % AUTO_SAVE_INTERVAL == 0) {
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
            } catch (IOException e) {
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
        } catch (IOException e) {
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
}