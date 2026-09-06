package cn.woshiikun_1145.mcmod.choco.cstmm.network;

import cn.woshiikun_1145.mcmod.choco.cstmm.Cstmm;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.config.GlobalConfig;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.config.MapConfig;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.MatchSession;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.PlayerProfile;
import cn.woshiikun_1145.mcmod.choco.cstmm.manager.*;
import cn.woshiikun_1145.mcmod.choco.cstmm.network.payload.*;
import cn.woshiikun_1145.mcmod.choco.cstmm.util.BlockPosAdapter;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class NetworkHandler {

    private static boolean registered = false;
    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(BlockPos.class, new BlockPosAdapter())
            .create();

    /** 配置同步分包大小（UTF-8 字节），须小于 writeString 上限 32767 字节与 C2S 整包 32768 字节限制 */
    private static final int CONFIG_CHUNK_BYTES = 30000;

    /** RequestConfigSync 每秒最大请求数，超过则拒绝并聊天栏提示 */
    private static final int MAX_SYNC_REQUESTS_PER_SECOND = 2;

    /** 握手重试次数（首次发送后最多再重试 2 次） */
    private static final int MAX_HANDSHAKE_RETRIES = 2;
    /** key: 玩家 UUID, value: 已重试次数 */
    private static final Map<UUID, Integer> pendingHandshakes = new ConcurrentHashMap<>();

    /** C2S 配置更新分包重组缓冲：key: 玩家 UUID */
    private static final Map<UUID, StringBuilder> pendingConfigUpdates = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> pendingConfigUpdateTotals = new ConcurrentHashMap<>();
    /** 已收分片位掩码：bit i 置位表示 partIndex i 已收到 */
    private static final Map<UUID, Long> receivedConfigUpdateParts = new ConcurrentHashMap<>();
    /** 已收分片累积 UTF-8 字节数：key: 玩家 UUID */
    private static final Map<UUID, Integer> pendingConfigUpdateBytes = new ConcurrentHashMap<>();

    /** C2S 配置更新重组累积字节上限，超过则清空该玩家 pending 状态，防止 OOM */
    private static final int MAX_CONFIG_UPDATE_BYTES = 2_000_000;

    /** RequestConfigSync 限频状态：value: [窗口起始毫秒, 窗口内计数] */
    private static final Map<UUID, long[]> syncRequestWindows = new ConcurrentHashMap<>();

    public static void register() {
        if (registered) return;
        registered = true;

        // ===== S2C 数据包注册 =====
        PayloadTypeRegistry.playS2C().register(HudDataPayload.ID, HudDataPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(MatchStatusPayload.ID, MatchStatusPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ConfigSyncPayload.ID, ConfigSyncPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(OpenConfigScreenPayload.ID, OpenConfigScreenPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(PlayerProfilePayload.ID, PlayerProfilePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(HandshakeS2CPayload.ID, HandshakeS2CPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ShopDataS2CPayload.ID, ShopDataS2CPayload.CODEC);

        // ===== C2S 数据包注册 =====
        PayloadTypeRegistry.playC2S().register(MatchActionPayload.ID, MatchActionPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(ConfigUpdatePayload.ID, ConfigUpdatePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(RequestConfigSyncPayload.ID, RequestConfigSyncPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(HandshakeC2SPayload.ID, HandshakeC2SPayload.CODEC);

        // ===== C2S 接收器（服务端处理客户端请求） =====
        ServerPlayNetworking.registerGlobalReceiver(MatchActionPayload.ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            if (player == null) return;
            context.server().execute(() -> handleMatchAction(payload, player));
        });

        ServerPlayNetworking.registerGlobalReceiver(ConfigUpdatePayload.ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            if (player == null) return;
            context.server().execute(() -> handleConfigUpdatePart(payload, player));
        });

        ServerPlayNetworking.registerGlobalReceiver(RequestConfigSyncPayload.ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            if (player == null) return;
            context.server().execute(() -> {
                // 限频：每玩家每秒最多 2 次，防止恶意客户端打满 CPU/带宽
                if (!tryAcquireSyncRequest(player.getUuid())) {
                    player.sendMessage(Text.literal("§c配置同步请求过于频繁，每秒最多 "
                            + MAX_SYNC_REQUESTS_PER_SECOND + " 次！"), false);
                    return;
                }
                sendConfigSync(player);
            });
        });

        // 握手：客户端对服务端主动请求的应答，收到即视为已响应（版本校验由客户端本地完成）
        ServerPlayNetworking.registerGlobalReceiver(HandshakeC2SPayload.ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            if (player == null) return;
            context.server().execute(() -> {
                pendingHandshakes.remove(player.getUuid());
                if (!getModVersion().equals(payload.clientVersion())) {
                    Cstmm.LOGGER.warn("[CSTMM - Network] Handshake version mismatch: client {}, server {}",
                            payload.clientVersion(), getModVersion());
                }
            });
        });

        // 注意：OpenConfigScreenPayload 是 S2C，服务端不注册接收器，客户端注册。

        // ===== 玩家加入事件：服务端主动发起握手 =====
        // （配置同步/履历同步/背包恢复统一由 EventListener 的 JOIN 处理，避免重复注册）
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            sendHandshakeRequest(player);
            pendingHandshakes.put(player.getUuid(), 0);
        });

        // 断线清理：分包重组缓冲与限频状态
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID uuid = handler.getPlayer().getUuid();
            clearPendingConfigUpdate(uuid);
            syncRequestWindows.remove(uuid);
        });

        Cstmm.LOGGER.info("[CSTMM - Network] Registered network handlers");
    }

    private static void handleMatchAction(MatchActionPayload payload, ServerPlayerEntity player) {
        switch (payload.action()) {
            case JOIN_QUEUE -> {
                String mapId = payload.mapName();
                int team = payload.team();
                if (mapId == null || mapId.isEmpty()) {
                    player.sendMessage(Text.literal("§c无效的地图"), false);
                    return;
                }
                // 客户端可注入任意整数，非法队伍值按无偏好处理
                if (team != 1 && team != 2) {
                    team = 0;
                }
                // 模式由玩家加入队列时主动选择，放在第 4 个字段（target）传递；
                // 空/非法值由 QueueManager 按竞技模式兜底
                QueueManager.getInstance().joinQueue(player, mapId, team, payload.target());
            }
            case LEAVE_QUEUE -> QueueManager.getInstance().leaveQueue(player);
            case VOTE_YES -> VoteManager.getInstance().handleVote(player, true);
            case VOTE_NO -> VoteManager.getInstance().handleVote(player, false);
            case REQUEST_PROFILE -> PlayerDataManager.getInstance().syncProfileToPlayer(player);
            case BUY_ITEM -> {
                // 资格校验：仅竞技模式对局中的玩家可购买（模式按会话判定，地图配置已无模式）
                MatchSession buySession = MatchManager.getInstance().getPlayerSession(player.getUuid());
                if (buySession == null || buySession.getPhase() == MatchSession.GamePhase.ENDED
                        || !buySession.isCompetitive()) {
                    player.sendMessage(Text.literal("§c仅竞技模式对局中可购买商品"), false);
                    return;
                }
                try {
                    int itemIndex = Integer.parseInt(payload.mapName());
                    boolean success = EquipmentManager.getInstance().givePurchasedItem(player, itemIndex);
                    if (success) {
                        player.sendMessage(Text.literal("§a购买成功！"), false);
                    } else {
                        player.sendMessage(Text.literal("§c购买失败，物品不存在或索引无效"), false);
                    }
                } catch (NumberFormatException e) {
                    player.sendMessage(Text.literal("§c无效的物品索引"), false);
                }
            }
            case REQUEST_SHOP -> {
                // 按玩家所在地图下发商店数据：仅竞技模式活跃对局中的玩家有购买资格（按会话判定）
                MatchSession session = MatchManager.getInstance().getPlayerSession(player.getUuid());
                MapConfig map = session != null
                        ? ConfigManager.getInstance().getMap(session.getMapName()) : null;
                boolean eligible = session != null && session.getPhase() != MatchSession.GamePhase.ENDED
                        && session.isCompetitive() && map != null;
                List<GlobalConfig.ShopItem> items = eligible && map != null
                        ? map.getShopItems() : List.of();
                ServerPlayNetworking.send(player, ShopDataS2CPayload.create(eligible, items));
            }
            case SELECT_TEAM -> {
                int team = payload.team();
                if (team == 1 || team == 2) {
                    player.sendMessage(Text.literal("§e已选择队伍: " + (team == 1 ? "红队" : "蓝队")), false);
                }
            }
            default -> Cstmm.LOGGER.debug("[CSTMM - Network] Unknown action: {} from {}", payload.action(), player.getName());
        }
    }

    // ===== C2S 配置更新分包重组 =====

    private static void handleConfigUpdatePart(ConfigUpdatePayload payload, ServerPlayerEntity player) {
        UUID uuid = player.getUuid();

        if (!player.hasPermissionLevel(2)) {
            player.sendMessage(Text.literal("§c你没有权限修改配置！"), false);
            return;
        }

        int partIndex = payload.partIndex();
        int totalParts = payload.totalParts();

        // 重组防护：totalParts 必须在 [1, 64]，partIndex 必须在 [0, totalParts)，越界直接丢弃
        if (totalParts < 1 || totalParts > 64 || partIndex < 0 || partIndex >= totalParts) {
            Cstmm.LOGGER.warn("[CSTMM - Network] Invalid config update part (index {}, total {}) from {}, dropped",
                    partIndex, totalParts, player.getName());
            return;
        }

        if (partIndex == 0) {
            pendingConfigUpdates.put(uuid, new StringBuilder());
            pendingConfigUpdateTotals.put(uuid, totalParts);
            receivedConfigUpdateParts.put(uuid, 1L); // bit 0
            pendingConfigUpdateBytes.put(uuid, 0);
        } else if (!pendingConfigUpdates.containsKey(uuid)) {
            // 丢失首包，放弃本次更新
            Cstmm.LOGGER.warn("[CSTMM - Network] Config update missed first part from {}, dropped", player.getName());
            return;
        } else {
            long mask = receivedConfigUpdateParts.getOrDefault(uuid, 0L);
            if ((mask & (1L << partIndex)) != 0) {
                // 同一分片重复到达，直接丢弃，防止重复拼接
                Cstmm.LOGGER.warn("[CSTMM - Network] Duplicate config update part {} from {}, dropped",
                        partIndex, player.getName());
                return;
            }
            receivedConfigUpdateParts.put(uuid, mask | (1L << partIndex));
        }

        // 累积总量上限：UTF-8 字节数超过上限时清空该玩家 pending 状态，防止恶意刷包导致 OOM
        int partBytes = payload.data().getBytes(StandardCharsets.UTF_8).length;
        if (pendingConfigUpdateBytes.merge(uuid, partBytes, Integer::sum) > MAX_CONFIG_UPDATE_BYTES) {
            clearPendingConfigUpdate(uuid);
            Cstmm.LOGGER.warn("[CSTMM - Network] Config update from {} exceeded {} UTF-8 bytes, pending state cleared",
                    player.getName(), MAX_CONFIG_UPDATE_BYTES);
            return;
        }

        pendingConfigUpdates.get(uuid).append(payload.data());

        if (Long.bitCount(receivedConfigUpdateParts.getOrDefault(uuid, 0L))
                >= pendingConfigUpdateTotals.getOrDefault(uuid, -1)) {
            String json = pendingConfigUpdates.remove(uuid).toString();
            clearPendingConfigUpdate(uuid);
            handleConfigUpdate(json, player);
        }
    }

    /** 清空指定玩家的配置更新重组缓冲 */
    private static void clearPendingConfigUpdate(UUID uuid) {
        pendingConfigUpdates.remove(uuid);
        pendingConfigUpdateTotals.remove(uuid);
        receivedConfigUpdateParts.remove(uuid);
        pendingConfigUpdateBytes.remove(uuid);
    }

    private static void handleConfigUpdate(String json, ServerPlayerEntity player) {
        if (!player.hasPermissionLevel(2)) {
            player.sendMessage(Text.literal("§c你没有权限修改配置！"), false);
            return;
        }
        try {
            var obj = GSON.fromJson(json, com.google.gson.JsonObject.class);
            if (obj == null) {
                player.sendMessage(Text.literal("§c配置数据为空，保存已取消"), false);
                return;
            }
            var mapListType = new com.google.gson.reflect.TypeToken<List<MapConfig>>() {}.getType();
            List<MapConfig> maps = GSON.fromJson(obj.get("maps"), mapListType);
            GlobalConfig global = GSON.fromJson(obj.get("global"), GlobalConfig.class);

            if (maps == null || global == null) {
                player.sendMessage(Text.literal("§c配置数据不完整（缺少 maps 或 global），保存已取消"), false);
                return;
            }
            if (global.getDefaultGear() == null) global.setDefaultGear(new java.util.ArrayList<>());

            ConfigManager configManager = ConfigManager.getInstance();

            // 客户端删除的地图 = 服务端存在但不在本次提交中的地图；
            // 正在对局中使用的地图禁止删除，否则该对局将永久僵死
            Set<String> payloadIds = new HashSet<>();
            for (MapConfig map : maps) {
                payloadIds.add(map.getId());
            }
            List<String> removedIds = new ArrayList<>();
            for (MapConfig existing : configManager.getMaps()) {
                if (!payloadIds.contains(existing.getId())) {
                    removedIds.add(existing.getId());
                }
            }
            for (String removedId : removedIds) {
                boolean inUse = MatchManager.getInstance().getAllActiveSessions().stream()
                        .anyMatch(s -> s.getMapName().equals(removedId)
                                && s.getPhase() != cn.woshiikun_1145.mcmod.choco.cstmm.data.MatchSession.GamePhase.ENDED);
                if (inUse) {
                    player.sendMessage(Text.literal(
                            "§c地图 \"" + removedId + "\" 正在对局中使用，禁止删除！本次保存已取消"), false);
                    return;
                }
            }

            // 先解析校验完毕，再统一应用，避免中途异常导致半写状态
            for (MapConfig map : maps) {
                configManager.updateMap(map);
            }
            for (String removedId : removedIds) {
                configManager.removeMap(removedId);
            }
            configManager.updateGlobalConfig(global);

            player.sendMessage(Text.literal("§a配置已保存！"), false);

            // 广播给所有在线玩家：使用服务端权威重建的数据，而非客户端原始 JSON
            String syncJson = buildConfigJson();
            if (syncJson != null) {
                for (ServerPlayerEntity p : player.getServer().getPlayerManager().getPlayerList()) {
                    sendConfigSync(p, syncJson);
                }
            }
        } catch (Exception e) {
            Cstmm.LOGGER.error("[CSTMM - Network] Failed to handle config update", e);
            player.sendMessage(Text.literal("§c配置保存失败: " + e.getMessage()), false);
        }
    }

    /** 每玩家每秒最多 2 次配置同步请求，返回是否放行 */
    private static boolean tryAcquireSyncRequest(UUID uuid) {
        long now = System.currentTimeMillis();
        long[] window = syncRequestWindows.computeIfAbsent(uuid, k -> new long[]{now, 0});
        if (now - window[0] >= 1000L) {
            window[0] = now;
            window[1] = 0;
        }
        if (window[1] >= MAX_SYNC_REQUESTS_PER_SECOND) {
            return false;
        }
        window[1]++;
        return true;
    }

    // ===== UTF-8 字节工具 =====

    /**
     * 按 UTF-8 字节边界切分字符串：每片最多 maxBytes 字节，且不切断多字节字符。
     * PacketByteBuf.writeString 校验的是 UTF-8 编码后的字节数而非字符数，
     * 含中文等非 ASCII 字符时必须按字节切分，否则编码后会超出上限抛 EncoderException。
     */
    public static List<String> splitByUtf8Bytes(String s, int maxBytes) {
        List<String> parts = new ArrayList<>();
        if (s == null || s.isEmpty()) return parts;
        int len = s.length();
        int start = 0;
        while (start < len) {
            int byteCount = 0;
            int end = start;
            while (end < len) {
                char c = s.charAt(end);
                int cb;
                if (Character.isHighSurrogate(c) && end + 1 < len && Character.isLowSurrogate(s.charAt(end + 1))) {
                    cb = 4; // 增补字符（代理对，UTF-8 占 4 字节）
                } else if (c < 0x80) {
                    cb = 1;
                } else if (c < 0x800) {
                    cb = 2;
                } else {
                    // BMP 多字节字符占 3 字节（未配对代理实际编码为 1 字节 '?'，按 3 估算只会更保守）
                    cb = 3;
                }
                if (byteCount + cb > maxBytes) break;
                byteCount += cb;
                end += cb == 4 ? 2 : 1;
            }
            if (end == start) end = start + 1; // 单字符超过 maxBytes 的极端兜底，防止死循环
            parts.add(s.substring(start, end));
            start = end;
        }
        return parts;
    }

    /**
     * 按 UTF-8 字节上限截断字符串：不切断多字节字符，超限时在最后一个完整字符处截断。
     * null 视为空串，保证 writeString 永不因长度/空值抛异常。
     */
    public static String truncateByUtf8Bytes(String s, int maxBytes) {
        if (s == null || s.isEmpty()) return "";
        int len = s.length();
        int byteCount = 0;
        for (int i = 0; i < len; ) {
            char c = s.charAt(i);
            int cb;
            if (Character.isHighSurrogate(c) && i + 1 < len && Character.isLowSurrogate(s.charAt(i + 1))) {
                cb = 4;
            } else if (c < 0x80) {
                cb = 1;
            } else if (c < 0x800) {
                cb = 2;
            } else {
                cb = 3;
            }
            if (byteCount + cb > maxBytes) {
                return s.substring(0, i);
            }
            byteCount += cb;
            i += cb == 4 ? 2 : 1;
        }
        return s;
    }

    // ===== 发送方法 =====

    /**
     * 获取服务端本模组版本号
     */
    public static String getModVersion() {
        return net.fabricmc.loader.api.FabricLoader.getInstance()
                .getModContainer(Cstmm.MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    public static void sendHudData(ServerPlayerEntity player, HudDataPayload payload) {
        ServerPlayNetworking.send(player, payload);
    }

    public static void sendMatchStatus(ServerPlayerEntity player, MatchStatusPayload payload) {
        // message 上限 128 UTF-8 字节：writeString 校验编码后字节数，超长时在发送链路统一截断
        // （不切断多字节字符），确保 writeString 永不抛异常；所有发送点均经由本方法
        String message = truncateByUtf8Bytes(payload.message(), 128);
        if (message != payload.message()) {
            payload = new MatchStatusPayload(payload.type(), message, payload.redKills(), payload.blueKills());
        }
        ServerPlayNetworking.send(player, payload);
    }

    public static void sendHandshakeRequest(ServerPlayerEntity player) {
        ServerPlayNetworking.send(player, new HandshakeS2CPayload(getModVersion()));
    }

    /** 每秒调用：未收到客户端握手应答则重试，最多重试 2 次 */
    public static void tickHandshake() {
        if (pendingHandshakes.isEmpty()) return;
        var server = Cstmm.getServer();
        if (server == null) return;

        for (Map.Entry<UUID, Integer> entry : new HashMap<>(pendingHandshakes).entrySet()) {
            int retries = entry.getValue();
            if (retries >= MAX_HANDSHAKE_RETRIES) {
                pendingHandshakes.remove(entry.getKey());
                continue;
            }
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(entry.getKey());
            if (player == null) {
                pendingHandshakes.remove(entry.getKey());
                continue;
            }
            pendingHandshakes.put(entry.getKey(), retries + 1);
            sendHandshakeRequest(player);
        }
    }

    public static void sendConfigSync(ServerPlayerEntity player) {
        String json = buildConfigJson();
        if (json == null) return;
        sendConfigSync(player, json);
    }

    /**
     * 分包发送配置 JSON：按 UTF-8 字节边界切分（writeString 校验的是编码后字节数，
     * 按字符切分在含中文时会超限），规避单包 32767 字节上限（背景图 base64 可能很大）。
     */
    public static void sendConfigSync(ServerPlayerEntity player, String json) {
        List<String> chunks = splitByUtf8Bytes(json, CONFIG_CHUNK_BYTES);
        int totalParts = chunks.size();
        for (int i = 0; i < totalParts; i++) {
            ServerPlayNetworking.send(player, new ConfigSyncPayload(i, totalParts, chunks.get(i)));
        }
    }

    public static void sendPlayerProfile(ServerPlayerEntity player) {
        PlayerProfile profile = PlayerDataManager.getInstance().getProfile(player.getUuid());
        if (profile == null) return;
        String json = GSON.toJson(profile);
        // PlayerProfilePayload 的 writeString 上限为 65536，且校验的是 UTF-8 编码后的字节数
        // （调用点位于 JOIN 回调），超长会抛异常导致背包恢复逻辑被跳过，这里显式防护
        int jsonBytes = json.getBytes(StandardCharsets.UTF_8).length;
        if (jsonBytes >= 65536) {
            Cstmm.LOGGER.warn("[CSTMM - Network] Player profile json too large ({} UTF-8 bytes) for {}, skip sync",
                    jsonBytes, player.getName());
            return;
        }
        ServerPlayNetworking.send(player, new PlayerProfilePayload(json));
    }

    public static void sendOpenConfigScreen(ServerPlayerEntity player) {
        ServerPlayNetworking.send(player, new OpenConfigScreenPayload());
    }

    private static String buildConfigJson() {
        try {
            ConfigManager cm = ConfigManager.getInstance();
            List<MapConfig> maps = cm.getMaps();
            GlobalConfig global = cm.getGlobalConfig();

            // 正在对局中使用的地图 ID 列表，供客户端配置界面阻止删除（M4）
            List<String> inUseMaps = new ArrayList<>();
            for (MatchSession session : MatchManager.getInstance().getAllActiveSessions()) {
                if (session.getPhase() != cn.woshiikun_1145.mcmod.choco.cstmm.data.MatchSession.GamePhase.ENDED) {
                    inUseMaps.add(session.getMapName());
                }
            }

            com.google.gson.JsonObject obj = new com.google.gson.JsonObject();
            obj.add("maps", GSON.toJsonTree(maps));
            obj.add("global", GSON.toJsonTree(global));
            obj.add("inUseMaps", GSON.toJsonTree(inUseMaps));
            return GSON.toJson(obj);
        } catch (Exception e) {
            Cstmm.LOGGER.error("[CSTMM - Network] Failed to build config json", e);
            return null;
        }
    }
}
