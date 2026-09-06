package cn.woshiikun_1145.mcmod.choco.cstmm.manager;

import cn.woshiikun_1145.mcmod.choco.cstmm.Cstmm;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.PlayerProfile;
import cn.woshiikun_1145.mcmod.choco.cstmm.network.NetworkHandler;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerDataManager {
    private static PlayerDataManager instance;

    private final Map<UUID, PlayerProfile> profiles;
    /** 档案文件损坏的玩家：不创建新档案、跳过写盘（防止空档案覆盖损坏文件），历史战绩待管理员恢复 */
    private final Set<UUID> corruptedProfiles;
    private final Path dataDir;
    private final Gson gson;

    private PlayerDataManager() {
        this.profiles = new ConcurrentHashMap<>();
        this.corruptedProfiles = ConcurrentHashMap.newKeySet();
        Path configRoot = FabricLoader.getInstance().getConfigDir();
        this.dataDir = configRoot.resolve("cstmm/data/players");
        this.gson = new GsonBuilder().setPrettyPrinting().create();

        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            Cstmm.LOGGER.error("[CSTMM - PlayerDataManager] Failed to create players directory", e);
        }
    }

    public static PlayerDataManager getInstance() {
        if (instance == null) {
            instance = new PlayerDataManager();
        }
        return instance;
    }

    public void ensureProfile(ServerPlayerEntity player) {
        UUID uuid = player.getUuid();
        if (profiles.containsKey(uuid)) return;

        // 档案损坏：不创建新档案（否则后续 saveProfile 会用空档案覆盖损坏文件、历史战绩清零）
        if (corruptedProfiles.contains(uuid)) {
            // ensureProfile 仅在玩家进服时调用一次，即每次进服提醒一次
            Cstmm.LOGGER.warn("[CSTMM - PlayerDataManager] Profile for {} is corrupted, skip creating a new one", player.getName());
            player.sendMessage(Text.literal("§c你的战绩档案已损坏，请联系管理员恢复"), false);
            return;
        }

        PlayerProfile profile = loadProfile(uuid);
        if (profile == null) {
            profile = new PlayerProfile(uuid, player.getName().getString());
            Cstmm.LOGGER.debug("[CSTMM - PlayerDataManager] Created new profile for {}", player.getName());
        } else {
            profile.setPlayerName(player.getName().getString());
            Cstmm.LOGGER.debug("[CSTMM - PlayerDataManager] Loaded profile for {}", player.getName());
        }
        profiles.put(uuid, profile);
    }

    private PlayerProfile loadProfile(UUID uuid) {
        Path file = dataDir.resolve(uuid.toString() + ".json");
        if (!Files.exists(file)) return null;

        try (Reader reader = new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8)) {
            return gson.fromJson(reader, PlayerProfile.class);
        } catch (IOException | RuntimeException e) {
            // 档案损坏（JSON 解析失败或读取 IO 异常）：标记为损坏，
            // 不再视为无档案，防止后续写操作用新档案覆盖损坏文件
            corruptedProfiles.add(uuid);
            Cstmm.LOGGER.warn("[CSTMM - PlayerDataManager] Failed to load profile for {}, marked as corrupted", uuid, e);
            return null;
        }
    }

    private void saveProfile(UUID uuid) {
        if (corruptedProfiles.contains(uuid)) {
            // 损坏档案跳过写盘，防止用内存档案覆盖损坏文件（历史战绩待管理员恢复）
            Cstmm.LOGGER.debug("[CSTMM - PlayerDataManager] Skipped saving corrupted profile for {}", uuid);
            return;
        }
        PlayerProfile profile = profiles.get(uuid);
        if (profile == null) return;

        Path file = dataDir.resolve(uuid.toString() + ".json");
        Path tmp = dataDir.resolve(uuid.toString() + ".json.tmp");
        // 先写临时文件再原子替换，避免崩溃/断电产生半写损坏 JSON
        try (Writer writer = new OutputStreamWriter(Files.newOutputStream(tmp), StandardCharsets.UTF_8)) {
            gson.toJson(profile, writer);
        } catch (IOException e) {
            Cstmm.LOGGER.error("[CSTMM - PlayerDataManager] Failed to save profile for {}", uuid, e);
            return;
        }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e2) {
                Cstmm.LOGGER.error("[CSTMM - PlayerDataManager] Failed to replace profile file for {}", uuid, e2);
            }
        }
    }

    // ==================== 数据更新 ====================

    public void addKill(UUID playerUuid) {
        if (corruptedProfiles.contains(playerUuid)) return;
        PlayerProfile profile = profiles.get(playerUuid);
        if (profile == null) return;
        profile.addKills(1);
        saveProfile(playerUuid);
        syncProfile(playerUuid);
    }

    public void addDeath(UUID playerUuid) {
        if (corruptedProfiles.contains(playerUuid)) return;
        PlayerProfile profile = profiles.get(playerUuid);
        if (profile == null) return;
        profile.addDeaths(1);
        saveProfile(playerUuid);
        syncProfile(playerUuid);
    }

    public void addPenaltyDeath(UUID playerUuid) {
        if (corruptedProfiles.contains(playerUuid)) return;
        PlayerProfile profile = profiles.get(playerUuid);
        if (profile == null) return;
        profile.addPenaltyDeaths(1);
        saveProfile(playerUuid);
        syncProfile(playerUuid);
        Cstmm.LOGGER.debug("[CSTMM - PlayerDataManager] Penalty death +1 for {}", playerUuid);
    }

    /** 结算：只记录场次与胜负。击杀/死亡由死亡监听实时计入，不在此重复叠加 */
    public void recordMatchEnd(UUID playerUuid, boolean won) {
        if (corruptedProfiles.contains(playerUuid)) return;
        PlayerProfile profile = profiles.get(playerUuid);
        if (profile == null) return;
        profile.addMatch();
        if (won) {
            profile.addWin();
        }
        saveProfile(playerUuid);
        syncProfile(playerUuid);
    }

    // ==================== 同步到客户端 ====================

    private void syncProfile(UUID playerUuid) {
        PlayerProfile profile = profiles.get(playerUuid);
        if (profile == null) return;

        var server = Cstmm.getServer();
        if (server == null) return;

        ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerUuid);
        if (player != null) {
            NetworkHandler.sendPlayerProfile(player);
        }
    }

    public void syncProfileToPlayer(ServerPlayerEntity player) {
        PlayerProfile profile = profiles.get(player.getUuid());
        if (profile == null) return;
        NetworkHandler.sendPlayerProfile(player);
    }

    // ==================== 查询 ====================

    public PlayerProfile getProfile(UUID uuid) {
        return profiles.get(uuid);
    }

    public PlayerProfile getProfile(ServerPlayerEntity player) {
        return profiles.get(player.getUuid());
    }

    public String getKDString(UUID uuid) {
        PlayerProfile profile = profiles.get(uuid);
        if (profile == null) return "0.00";
        return profile.getKDString();
    }

    public double getKD(UUID uuid) {
        PlayerProfile profile = profiles.get(uuid);
        if (profile == null) return 0.0;
        return profile.getKD();
    }

    // ==================== 批量操作 ====================

    public void saveAll() {
        for (UUID uuid : profiles.keySet()) {
            saveProfile(uuid);
        }
        Cstmm.LOGGER.info("[CSTMM - PlayerDataManager] Saved {} player profiles", profiles.size());
    }

    public void loadAll() {
        // try-with-resources 关闭 Files.list 返回的 Stream，避免目录句柄泄漏
        try (var stream = Files.list(dataDir)) {
            stream
                    .filter(p -> p.toString().endsWith(".json"))
                    .forEach(p -> {
                        String fileName = p.getFileName().toString();
                        String uuidStr = fileName.replace(".json", "");
                        try {
                            UUID uuid = UUID.fromString(uuidStr);
                            PlayerProfile profile = loadProfile(uuid);
                            if (profile != null) {
                                profiles.put(uuid, profile);
                            }
                        } catch (IllegalArgumentException e) {
                            Cstmm.LOGGER.warn("[CSTMM - PlayerDataManager] Invalid UUID in filename: {}", fileName);
                        }
                    });
            Cstmm.LOGGER.info("[CSTMM - PlayerDataManager] Loaded {} player profiles", profiles.size());
        } catch (IOException e) {
            Cstmm.LOGGER.error("[CSTMM - PlayerDataManager] Failed to list player profiles", e);
        }
    }

    public Map<UUID, PlayerProfile> getAllProfiles() {
        return new ConcurrentHashMap<>(profiles);
    }
}