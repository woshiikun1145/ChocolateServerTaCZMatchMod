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

public class InventoryManager {
    private static InventoryManager instance;

    private final Map<UUID, InventorySnapshot> savedInventories;
    private final Map<UUID, Boolean> competitivePlayers;

    private final Path bagsDir;
    private final Path legacyBagsFile;
    /** 加载失败（损坏）的文件名：绝不覆盖、绝不删除，留待管理员修复 */
    private final Set<String> unloadableFiles = new HashSet<>();
    private final Gson gson;
    private final AtomicInteger saveCounter = new AtomicInteger(0);
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

    public static InventoryManager getInstance() {
        if (instance == null) {
            instance = new InventoryManager();
        }
        return instance;
    }

    // ===== 新增：初始化方法 =====
    public static void initialize(RegistryWrapper.WrapperLookup lookup) {
        registryLookup = lookup;
        getInstance().loadBags();
    }

    private static RegistryWrapper.WrapperLookup getRegistryLookup() {
        return registryLookup;
    }

    // ===== 核心业务方法 =====

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

    public boolean hasSavedInventory(UUID uuid) {
        return savedInventories.containsKey(uuid);
    }

    public void setCompetitive(UUID uuid, boolean competitive) {
        competitivePlayers.put(uuid, competitive);
    }

    public boolean isCompetitivePlayer(UUID uuid) {
        return competitivePlayers.getOrDefault(uuid, false);
    }

    public void autoSave() {
        int count = saveCounter.incrementAndGet();
        if (count % AUTO_SAVE_INTERVAL == 0) {
            saveBags();
        }
    }

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

    public void saveAll() {
        saveBags();
    }

    // ===== ItemStack 序列化适配器（修复版） =====

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