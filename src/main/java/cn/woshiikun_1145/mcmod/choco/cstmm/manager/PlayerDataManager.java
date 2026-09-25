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

/**
 * 【作用】玩家战绩档案管理器：负责档案（KD、场次、胜负、惩罚死亡）的加载/创建/持久化与实时更新，
 *         并把最新档案同步到在线玩家的客户端缓存。持久化到 config/cstmm/data/players/<uuid>.json，
 *         原子写盘（临时文件 + ATOMIC_MOVE）；损坏档案加入 corruptedProfiles，绝不覆盖用户数据。
 * 【被谁使用】Cstmm（服务器启停时 loadAll/saveAll）、EventListener（进服 ensureProfile、
 *           击杀/死亡监听 addKill/addDeath）、MatchManager（对局结算 recordMatchEnd）、
 *           BoundaryChecker（出界处决 addPenaltyDeath）、ModCommands（管理员查询/修改档案）、
 *           NetworkHandler（客户端档案请求与下发）。仅服务端。
 */
public class PlayerDataManager {
    private static PlayerDataManager instance;

    /** 内存中的档案缓存，key: 玩家 UUID；启动时 loadAll 一次性载入全部磁盘档案（离线玩家也可查） */
    private final Map<UUID, PlayerProfile> profiles;
    /** 档案文件损坏的玩家：不创建新档案、跳过写盘（防止空档案覆盖损坏文件），历史战绩待管理员恢复 */
    private final Set<UUID> corruptedProfiles;
    /** 档案目录 config/cstmm/data/players */
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

    // 单例入口（懒加载）
    public static PlayerDataManager getInstance() {
        if (instance == null) {
            instance = new PlayerDataManager();
        }
        return instance;
    }

    /**
     * 【作用】确保玩家在内存中有档案：无则从磁盘加载或新建（玩家进服时调用）。
     * 【被谁使用】EventListener（玩家进服事件，每次进服调用一次）。仅服务端。
     */
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
            // 档案文件随进服创建（新档案立即落盘，而不是等首次击杀/死亡/结算才写盘）
            profiles.put(uuid, profile);
            saveProfile(uuid);
            Cstmm.LOGGER.debug("[CSTMM - PlayerDataManager] Created new profile for {}", player.getName());
        } else {
            profile.setPlayerName(player.getName().getString());
            profiles.put(uuid, profile);
            Cstmm.LOGGER.debug("[CSTMM - PlayerDataManager] Loaded profile for {}", player.getName());
        }
    }

    // 【作用】从磁盘读取单个档案；文件不存在返回 null，损坏则标记 corruptedProfiles 后返回 null
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

    // 【作用】把单个档案原子写盘（先写 .tmp 再 ATOMIC_MOVE 替换）；损坏档案跳过不写
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

    /**
     * 【作用】击杀数 +1 并落盘、同步客户端。
     * 【被谁使用】EventListener（死亡监听：被 TaCZ 击杀时给击杀者记击杀）。仅服务端。
     */
    public void addKill(UUID playerUuid) {
        if (corruptedProfiles.contains(playerUuid)) return;
        PlayerProfile profile = profiles.get(playerUuid);
        if (profile == null) return;
        profile.addKills(1);
        saveProfile(playerUuid);
        syncProfile(playerUuid);
    }

    /**
     * 【作用】死亡数 +1 并落盘、同步客户端。
     * 【被谁使用】EventListener（死亡监听：给被击杀者记死亡）。仅服务端。
     */
    public void addDeath(UUID playerUuid) {
        if (corruptedProfiles.contains(playerUuid)) return;
        PlayerProfile profile = profiles.get(playerUuid);
        if (profile == null) return;
        profile.addDeaths(1);
        saveProfile(playerUuid);
        syncProfile(playerUuid);
    }

    /**
     * 【作用】惩罚死亡 +1（出界超时处决时记，影响 KD 但不计入正常死亡数）。
     * 【被谁使用】BoundaryChecker（出界倒计时超限处决玩家时）。仅服务端。
     */
    public void addPenaltyDeath(UUID playerUuid) {
        if (corruptedProfiles.contains(playerUuid)) return;
        PlayerProfile profile = profiles.get(playerUuid);
        if (profile == null) return;
        profile.addPenaltyDeaths(1);
        saveProfile(playerUuid);
        syncProfile(playerUuid);
        Cstmm.LOGGER.debug("[CSTMM - PlayerDataManager] Penalty death +1 for {}", playerUuid);
    }

    /** 结算：只记录场次与胜负。击杀/死亡由死亡监听实时计入，不在此重复叠加
     * 【被谁使用】MatchManager#updatePlayerProfiles（对局结束时逐个玩家调用）。仅服务端。 */
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

    /**
     * 【作用】设置/清除玩家头像绑定并立即落盘（头像绑定存于档案文件 config/cstmm/data/players/<uuid>.json
     *         的 avatarType/avatarId 字段，随档案一起持久化）。
     * @param type "qq" / "bili"；空串表示清除头像
     * @param id 账号数字 ID（QQ号 / B站 UID）
     * @return null 表示成功，否则返回可直接发给玩家的错误消息
     * 【被谁使用】NetworkHandler 的 SET_FACE 处理（服务端主线程）。
     */
    public String setAvatarBinding(UUID uuid, String type, String id) {
        if (uuid == null) return "§c无效的玩家";
        String err = PlayerProfile.validateAvatarBinding(type, id);
        if (err != null) return err;
        PlayerProfile profile = profiles.get(uuid);
        if (profile == null) return "§c档案未加载，请重新进服后再试";
        String t = type == null ? "" : type.trim().toLowerCase();
        String v = id == null ? "" : id.trim();
        profile.setAvatarBinding(t, v);
        saveProfile(uuid);
        Cstmm.LOGGER.info("[CSTMM - PlayerDataManager] Avatar binding {} for {}",
                t.isEmpty() ? "cleared" : "updated", uuid);
        return null;
    }

    // ==================== 同步到客户端 ====================

    /** 管理员命令修改档案后统一调用：原子写盘并同步在线玩家的客户端缓存
     * 【被谁使用】ModCommands（/cstmm data 修改玩家 KD/场次等字段后）。仅服务端。 */
    public void persistAndSync(UUID playerUuid) {
        saveProfile(playerUuid);
        syncProfile(playerUuid);
    }

    // 【作用】把玩家最新档案推送到其客户端缓存（玩家不在线则跳过）
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

    /**
     * 【作用】应客户端请求，把指定玩家自己的档案下发到客户端缓存。
     * 【被谁使用】NetworkHandler（处理 REQUEST_PROFILE 请求 payload）。仅服务端。
     */
    public void syncProfileToPlayer(ServerPlayerEntity player) {
        PlayerProfile profile = profiles.get(player.getUuid());
        if (profile == null) return;
        NetworkHandler.sendPlayerProfile(player);
    }

    // ==================== 查询 ====================

    // 按 UUID 查询档案（NetworkHandler 构建档案 JSON 下发时使用）
    public PlayerProfile getProfile(UUID uuid) {
        return profiles.get(uuid);
    }

    // 按玩家实体查询档案（内部便捷重载）
    public PlayerProfile getProfile(ServerPlayerEntity player) {
        return profiles.get(player.getUuid());
    }

    /**
     * 按玩家名或 UUID 字符串查找档案（名称不区分大小写，支持离线玩家）；
     * 找不到返回 null。启动时 loadAll 已把全部磁盘档案载入内存，离线可查。
     * 【被谁使用】ModCommands（/cstmm data 按名或 UUID 定位待修改的档案）。
     */
    public PlayerProfile findProfile(String nameOrUuid) {
        if (nameOrUuid == null || nameOrUuid.isBlank()) return null;
        String input = nameOrUuid.trim();
        try {
            return profiles.get(UUID.fromString(input));
        } catch (IllegalArgumentException ignored) {
        }
        for (PlayerProfile p : profiles.values()) {
            if (p.getPlayerName() != null && p.getPlayerName().equalsIgnoreCase(input)) return p;
        }
        return null;
    }

    // 查询 KD 格式化字符串（当前项目内暂无调用方，预留查询入口）
    public String getKDString(UUID uuid) {
        PlayerProfile profile = profiles.get(uuid);
        if (profile == null) return "0.00";
        return profile.getKDString();
    }

    // 查询 KD 数值（当前项目内暂无调用方，预留查询入口）
    public double getKD(UUID uuid) {
        PlayerProfile profile = profiles.get(uuid);
        if (profile == null) return 0.0;
        return profile.getKD();
    }

    /**
     * 【作用】删除玩家全部战绩数据（管理员 /cstmm data delete 命令）：内存档案移除、
     *         磁盘 players/<uuid>.json 删除、损坏标记解除（管理员可用重新建档修复损坏档案）。
     *         玩家在线时不会立即重建档案（ensureProfile 仅进服调用），其后续击杀/死亡
     *         在无档案状态下被安全跳过，重新进服后从零建档。
     * 【被谁使用】ModCommands（/cstmm data delete player）。仅服务端。
     * @return true 表示确实删除了内容（内存档案存在或磁盘文件存在）
     */
    public boolean deleteProfile(UUID playerUuid) {
        if (playerUuid == null) return false;
        PlayerProfile removed = profiles.remove(playerUuid);
        corruptedProfiles.remove(playerUuid);
        boolean fileDeleted = false;
        try {
            fileDeleted = Files.deleteIfExists(dataDir.resolve(playerUuid + ".json"));
        } catch (IOException e) {
            Cstmm.LOGGER.error("[CSTMM - PlayerDataManager] Failed to delete profile file for {}", playerUuid, e);
        }
        if (removed != null || fileDeleted) {
            Cstmm.LOGGER.info("[CSTMM - PlayerDataManager] Profile deleted for {} (inMemory={}, file={})",
                    playerUuid, removed != null, fileDeleted);
            return true;
        }
        return false;
    }

    // ==================== 批量操作 ====================

    // 【作用】停服时把全部档案落盘（Cstmm 服务器停止事件调用）
    public void saveAll() {
        for (UUID uuid : profiles.keySet()) {
            saveProfile(uuid);
        }
        Cstmm.LOGGER.info("[CSTMM - PlayerDataManager] Saved {} player profiles", profiles.size());
    }

    // 【作用】服务器启动时把 players/ 目录下全部档案一次性载入内存（支持离线查询）
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

    // 全部档案的副本（ModCommands 列出所有玩家战绩时使用）
    public Map<UUID, PlayerProfile> getAllProfiles() {
        return new ConcurrentHashMap<>(profiles);
    }
}