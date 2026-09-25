package cn.woshiikun_1145.mcmod.choco.cstmm.client.network;

import cn.woshiikun_1145.mcmod.choco.cstmm.Cstmm;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.ClientHandshakeState;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.cache.BadgeCache;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.cache.ClanCache;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.cache.ConfigDataCache;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.cache.ConfigDiskCache;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.cache.FaceCache;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.cache.QueueStatusCache;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.cache.ShopDataCache;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.hud.HudOverlay;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.screen.ConfigScreen;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.screen.MatchMenuScreen;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.screen.PopupScreen;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.screen.ShopScreen;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.config.GlobalConfig;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.config.MapConfig;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.PlayerProfile;
import cn.woshiikun_1145.mcmod.choco.cstmm.network.NetworkHandler;
import cn.woshiikun_1145.mcmod.choco.cstmm.network.payload.*;
import cn.woshiikun_1145.mcmod.choco.cstmm.util.BlockPosAdapter;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.lang.reflect.Type;
import java.net.InetSocketAddress;
import java.util.List;

/**
 * 【作用】客户端网络中枢：注册全部 S2C 包接收器（HUD 数据、匹配状态、配置同步分包重组、配置元数据
 *         （哈希握手）、握手、商店、战队、徽标、弹窗、队列状态），管理连接生命周期
 *         （JOIN/DISCONNECT/Tick 回调），并提供 C2S 配置请求/更新的发送方法。
 *         配置同步省带宽：JOIN 收到 ConfigMetaPayload（服务端配置哈希）后与本机磁盘缓存
 *         （ConfigDiskCache，按服务器隔离持久化）比对，一致则回报哈希免收全量配置。
 * 【被谁使用】CstmmClient#onInitializeClient 启动时调用 register()；
 *             ConfigScreen#init 与 ConfigScreen 保存回调调用 requestConfigSync/sendConfigUpdate。
 */
@Environment(EnvType.CLIENT)
public class ClientNetworkHandler {

    // 防止重复注册网络接收器的标志位
    private static boolean registered = false;
    // 客户端专用 Gson：注册 BlockPos 适配器以正确序列化出生点位
    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(BlockPos.class, new BlockPosAdapter())
            .create();

    // ===== 配置同步分包重组 =====
    // 已收到的配置 JSON 分片累积缓冲区
    private static final StringBuilder pendingConfigJson = new StringBuilder();
    // 本次同步的总分片数；-1 表示尚未收到首包
    private static int pendingTotalParts = -1;
    // 已收到的分片数
    private static int receivedParts = 0;

    // ===== 配置哈希握手 =====
    // true = 进服后尚未完成配置哈希比对（收到首个 ConfigMetaPayload 时消费置 false）；
    // JOIN/DISCONNECT 时复位，防止后续 meta 包（哈希匹配回应等）重复触发比对请求
    private static boolean pendingConfigHashHandshake = true;

    /**
     * 【作用】注册所有 S2C 包接收器与连接事件回调（JOIN 重置握手状态、DISCONNECT 清空全部
     *         客户端缓存、每 tick 推进握手超时检查），是客户端数据流的唯一入口。
     * 【被谁使用】仅被 CstmmClient#onInitializeClient 调用一次（幂等，重复调用直接返回）。
     */
    public static void register() {
        if (registered) return;
        registered = true;

        // ========== HUD 数据接收 ==========
        ClientPlayNetworking.registerGlobalReceiver(HudDataPayload.ID, (payload, context) -> context.client().execute(() -> HudOverlay.updateData(
                payload.mapName(),
                payload.redKills(),
                payload.blueKills(),
                payload.remainingSeconds(),
                payload.inGame()
        )));

        // ========== 匹配状态接收 ==========
        ClientPlayNetworking.registerGlobalReceiver(MatchStatusPayload.ID, (payload, context) -> context.client().execute(() -> {
            MinecraftClient client = context.client();
            if (client.player != null) {
                client.player.sendMessage(Text.literal(payload.message()), false);
            }
        }));

        // ========== 配置同步接收（分包重组） ==========
        // 【作用】逐片累积配置 JSON；首包重置缓冲，收齐全片后一次性解析应用
        ClientPlayNetworking.registerGlobalReceiver(ConfigSyncPayload.ID, (payload, context) -> context.client().execute(() -> {
            // 首包：重置重组缓冲区并记录总分片数
            if (payload.partIndex() == 0) {
                pendingConfigJson.setLength(0);
                pendingTotalParts = payload.totalParts();
                receivedParts = 0;
            } else if (pendingTotalParts == -1) {
                // 丢失了首包，放弃本次同步，等待下次完整同步
                Cstmm.LOGGER.warn("[CSTMM - ClientNetwork] Config sync missed first part, dropped");
                return;
            }

            pendingConfigJson.append(payload.data());
            receivedParts++;

            // 【作用】收齐全部分片后清空重组状态，并解析应用完整配置 JSON（persist=true：落盘供下次重连复用）
                if (receivedParts >= pendingTotalParts) {
                    String json = pendingConfigJson.toString();
                    pendingConfigJson.setLength(0);
                    pendingTotalParts = -1;
                    receivedParts = 0;
                    applyConfigSync(json, true);
                }
        }));

        // ========== 配置元数据接收（哈希握手 + inUseMaps） ==========
        // 【作用】更新 inUseMaps 缓存；JOIN 握手阶段（首次收到）比对磁盘缓存哈希：
        //        一致 → 用磁盘缓存恢复内存配置并回报哈希（免收全量大 JSON）；不一致 → 清脏本体并请求全量
        ClientPlayNetworking.registerGlobalReceiver(ConfigMetaPayload.ID, (payload, context) -> context.client().execute(() -> {
            ConfigDataCache cache = ConfigDataCache.getInstance();

            // 更新对局使用中地图列表（配置界面删除保护用；JSON 解析失败保留旧值）
            try {
                List<String> inUse = GSON.fromJson(payload.inUseMapsJson(),
                        new TypeToken<List<String>>() {}.getType());
                if (inUse != null) cache.updateInUseMaps(inUse);
            } catch (Exception e) {
                Cstmm.LOGGER.warn("[CSTMM - ClientNetwork] Failed to parse inUseMaps", e);
            }

            // 仅 JOIN 哈希握手阶段（首次收到 meta）触发比对；之后的 meta（哈希匹配回应/全量随行）只更新 inUseMaps
            if (!pendingConfigHashHandshake) return;
            pendingConfigHashHandshake = false;

            String serverHash = payload.configHash() == null ? "" : payload.configHash();
            String localHash = serverHash.isEmpty() ? "" : ConfigDiskCache.loadHash();
            if (!serverHash.isEmpty() && serverHash.equals(localHash)) {
                // 哈希一致：磁盘缓存即服务端当前配置，恢复进内存后回报哈希，服务端将跳过全量下发
                String core = ConfigDiskCache.loadCoreJson();
                if (core != null && applyConfigSync(core, false)) {
                    ClientPlayNetworking.send(new RequestConfigSyncPayload(serverHash));
                    return;
                }
                // 磁盘缓存损坏（解析失败）：按无缓存处理
                Cstmm.LOGGER.warn("[CSTMM - ClientNetwork] Disk config cache matched hash but failed to apply, request full sync");
            }
            // 哈希不一致/无缓存：清掉脏的配置本体（保留刚更新的 inUseMaps），请求全量
            cache.clearConfigBody();
            ClientPlayNetworking.send(new RequestConfigSyncPayload(""));
        }));

        // ========== 打开配置界面 ==========
        ClientPlayNetworking.registerGlobalReceiver(OpenConfigScreenPayload.ID, (payload, context) -> context.client().execute(() -> {
            MinecraftClient client = context.client();
            if (!(client.currentScreen instanceof ConfigScreen)) {
                client.setScreen(new ConfigScreen());
            }
        }));

        // ========== 玩家履历同步 ==========
        ClientPlayNetworking.registerGlobalReceiver(PlayerProfilePayload.ID, (payload, context) -> context.client().execute(() -> {
            try {
                String json = payload.jsonData();
                if (json == null || json.isEmpty()) return;
                // 头像绑定字段不进 PlayerProfile：先取出存 FaceCache 再解析战绩（图片由客户端按绑定自行获取）
                JsonObject profileObj = GSON.fromJson(json, JsonObject.class);
                if (profileObj != null && profileObj.has("avatarType") && !profileObj.get("avatarType").isJsonNull()) {
                    String avatarType = profileObj.get("avatarType").getAsString();
                    String avatarId = profileObj.has("avatarId") && !profileObj.get("avatarId").isJsonNull()
                            ? profileObj.get("avatarId").getAsString() : "";
                    FaceCache.setOwn(avatarType, avatarId);
                } else {
                    FaceCache.setOwn("", "");
                }
                PlayerProfile profile = GSON.fromJson(json, PlayerProfile.class);
                PlayerProfileCache.getInstance().updateProfile(profile);
            } catch (Exception e) {
                Cstmm.LOGGER.error("[CSTMM - ClientNetwork] Failed to parse player profile", e);
            }
        }));

        // ========== 握手请求接收（服务端主动发起） ==========
        ClientPlayNetworking.registerGlobalReceiver(HandshakeS2CPayload.ID, (payload, context) -> context.client().execute(() ->
                ClientHandshakeState.onResponse(payload.serverVersion())
        ));

        // ========== 商店数据接收 ==========
        ClientPlayNetworking.registerGlobalReceiver(ShopDataS2CPayload.ID, (payload, context) -> context.client().execute(() -> {
            ShopDataCache.getInstance().update(payload.eligible(), payload.items());
            // 商店界面已打开时刷新其内容
            if (context.client().currentScreen instanceof ShopScreen screen) {
                screen.refresh();
            }
        }));

        // ========== 加入/断开/Tick ==========
        // 握手由服务端主动发起，客户端只重置本地状态；
        // 同时记录服务器标识（磁盘配置缓存按服务器隔离）并进入配置哈希握手待比对状态
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(() -> {
            ClientHandshakeState.onJoin();
            pendingConfigHashHandshake = true;
            // 服务器标识：地址:端口；单人/局域网拿不到 InetSocketAddress 时统一用 "local"
            String serverKey = "local";
            try {
                if (handler.getConnection().getAddress() instanceof InetSocketAddress addr) {
                    serverKey = addr.getHostString() + ":" + addr.getPort();
                }
            } catch (Exception ignored) {
            }
            ConfigDiskCache.onJoin(serverKey);
            // 切换徽标磁盘缓存子目录（badges/<host_port>，按服务器隔离）后再上报已有徽标
            BadgeCache.onServer(serverKey);
            // 徽标磁盘缓存上报：告知服务端本机已有的徽标 id，服务端跳过分片重发（省带宽）
            List<String> knownBadges = BadgeCache.diskBadgeIds();
            if (!knownBadges.isEmpty()) {
                ClientPlayNetworking.send(new BadgeKnownPayload(String.join(",", knownBadges)));
            }
        }));

        // ========== 战队数据接收 ==========
        ClientPlayNetworking.registerGlobalReceiver(ClanDataPayload.ID, (payload, context) -> context.client().execute(() -> {
            ClanCache.getInstance().update(payload.kind(), payload.json());
            // MINE/DETAIL 引用的徽标若本地缺失（如断线重连后缓存被清），请求服务端补发分片
            ClanCache.ClanInfo clan = ClanCache.getInstance().getMine().clan;
            if (clan != null) BadgeCache.requestIfMissing(clan.badge);
            if (ClanCache.getInstance().getSelectedDetail() != null) {
                BadgeCache.requestIfMissing(ClanCache.getInstance().getSelectedDetail().badge);
            }
        }));

        // ========== 战队徽标分片接收 ==========
        ClientPlayNetworking.registerGlobalReceiver(BadgePayload.ID, (payload, context) -> context.client().execute(() ->
                BadgeCache.apply(payload)
        ));

        // ========== 弹窗通知接收（匹配菜单/配置界面内嵌弹窗，其他情况全局弹窗界面承载） ==========
        ClientPlayNetworking.registerGlobalReceiver(PopupPayload.ID, (payload, context) -> context.client().execute(() -> {
            MinecraftClient client = context.client();
            if (client.currentScreen instanceof MatchMenuScreen screen) {
                screen.showPopup(payload.message());
            } else if (client.currentScreen instanceof ConfigScreen screen) {
                screen.showPopup(payload.message());
            } else {
                // 其他界面/无界面（如关掉菜单后在游戏中）：用全局弹窗界面承载，
                // 确定后返回原界面——不再回退聊天栏（聊天显示丢失弹窗语义且易被刷屏冲走）
                client.setScreen(new PopupScreen(payload.message(), client.currentScreen));
            }
        }));

        // ========== 队列状态接收 ==========
        ClientPlayNetworking.registerGlobalReceiver(QueueStatusPayload.ID, (payload, context) -> context.client().execute(() ->
                QueueStatusCache.getInstance().update(payload.json())));

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ClientHandshakeState.onDisconnect();
            // 重置配置哈希握手状态（下次进服重新比对；磁盘缓存保留，重连可免收全量配置）
            pendingConfigHashHandshake = true;
            // 断线时重置 HUD 显示并清空配置/商店/战队/队列/徽标/头像缓存，避免残留上一服务器的数据
            HudOverlay.reset();
            ConfigDataCache.getInstance().clear();
            ShopDataCache.getInstance().clear();
            ClanCache.getInstance().clear();
            QueueStatusCache.getInstance().clear();
            BadgeCache.clear();
            FaceCache.clear();
            cn.woshiikun_1145.mcmod.choco.cstmm.client.util.UrlImageCache.clear();
            cn.woshiikun_1145.mcmod.choco.cstmm.client.util.FaceImageCache.clear();
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> ClientHandshakeState.tick(client));

        Cstmm.LOGGER.info("[CSTMM - ClientNetwork] Registered client network handlers");
    }

    /**
     * 解析配置 JSON 并更新本地缓存。
     * 【作用】提取哈希 → 应用 maps/global →（persist 时）落盘 ConfigDiskCache 供重连哈希比对；
     *         兼容旧版服务端：JSON 含 inUseMaps 字段时照旧应用，无 hash 字段时不落盘（无法做哈希握手）。
     * 【被谁使用】本类 ConfigSyncPayload 接收器（分片收齐后，persist=true）、
     *           ConfigMetaPayload 接收器（JOIN 哈希命中，用磁盘缓存恢复内存，persist=false）。
     * @return 是否成功应用（磁盘缓存损坏时返回 false，调用方回退为请求全量）
     */
    private static boolean applyConfigSync(String json, boolean persist) {
        try {
            if (json == null || json.isEmpty()) {
                Cstmm.LOGGER.warn("[CSTMM - ClientNetwork] Received empty config sync");
                return false;
            }

            var obj = GSON.fromJson(json, com.google.gson.JsonObject.class);
            if (obj == null) return false;
            Type mapListType = new TypeToken<List<MapConfig>>() {}.getType();
            List<MapConfig> maps = GSON.fromJson(obj.get("maps"), mapListType);
            GlobalConfig global = GSON.fromJson(obj.get("global"), GlobalConfig.class);
            // 配置本体缺失（畸形 JSON）：视为失败，调用方回退请求全量
            if (maps == null || global == null) return false;

            ConfigDataCache cache = ConfigDataCache.getInstance();
            cache.updateMaps(maps);
            cache.updateGlobalConfig(global);

            // 兼容旧版服务端全量 JSON 内嵌的 inUseMaps（新版结构由 ConfigMetaPayload 单独管理）
            if (obj.has("inUseMaps") && obj.get("inUseMaps").isJsonArray()) {
                List<String> inUseMaps = GSON.fromJson(obj.get("inUseMaps"),
                        new TypeToken<List<String>>() {}.getType());
                if (inUseMaps != null) cache.updateInUseMaps(inUseMaps);
            }

            // 提取配置哈希并持久化到磁盘（旧版服务端无 hash 字段则跳过落盘）
            String hash = obj.has("hash") && !obj.get("hash").isJsonNull()
                    ? obj.get("hash").getAsString() : "";
            if (persist) {
                ConfigDiskCache.store(hash, json);
            }

            Cstmm.LOGGER.debug("[CSTMM - ClientNetwork] Config synced: {} maps", maps.size());

            // 刷新匹配菜单
            if (MinecraftClient.getInstance().currentScreen instanceof MatchMenuScreen screen) {
                screen.refreshMaps();
            }
            // 刷新配置界面（如果打开）
            if (MinecraftClient.getInstance().currentScreen instanceof ConfigScreen screen) {
                screen.refresh();
            }
            return true;

        } catch (Exception e) {
            Cstmm.LOGGER.error("[CSTMM - ClientNetwork] Failed to parse config sync", e);
            return false;
        }
    }

    // 发送配置请求（带客户端自检：每秒超过 50 次触发 ConfigSyncRateLimitException 使游戏崩溃）
    /**
     * 【作用】向服务端发送配置同步请求（携带本地磁盘缓存的配置哈希：服务端一致时仅回元数据小包，
     *         免重复下发全量大 JSON），内置滑动窗口限流（1 秒内最多 50 次，超限故意崩溃防滥用）。
     * 【被谁使用】ConfigScreen#init 打开界面时拉取配置；ConfigScreen 保存配置成功后重新拉取。
     */
    public static void requestConfigSync() {
        // 【作用】按 1 秒滑动窗口统计请求次数，超 50 次立即抛异常崩溃（防滥用自毁）
        long now = System.currentTimeMillis();
        if (now - syncRequestWindowStart >= 1000L) {
            syncRequestWindowStart = now;
            syncRequestCount = 0;
        }
        syncRequestCount++;
        if (syncRequestCount > 50) {
            throw new ConfigSyncRateLimitException(
                    "CSTMM config sync request rate exceeded: " + syncRequestCount + " requests/second");
        }
        ClientPlayNetworking.send(new RequestConfigSyncPayload(ConfigDiskCache.loadHash()));
    }

    private static long syncRequestWindowStart = 0L;
    private static int syncRequestCount = 0;

    /**
     * 分包发送 C2S 配置更新。原版对 C2S payload 有 32768 字节硬限制，
     * 且 writeString 校验的是 UTF-8 编码后的字节数，故按 UTF-8 字节边界切分
     * （30000 字节/片，不切断多字节字符），服务端按玩家重组。
     * 【被谁使用】ConfigScreen 提交保存配置时（第 958 行）调用，仅此一处。
     */
    public static void sendConfigUpdate(String json) {
        List<String> chunks = NetworkHandler.splitByUtf8Bytes(json, 30000);
        int totalParts = chunks.size();
        for (int i = 0; i < totalParts; i++) {
            ClientPlayNetworking.send(new ConfigUpdatePayload(i, totalParts, chunks.get(i)));
        }
    }

    // ===== 履历缓存 =====
    /**
     * 【作用】单例缓存当前玩家的履历数据（战绩/段位等），由服务端推送更新。
     * 【被谁使用】本类 PlayerProfilePayload 接收器写入；MatchMenuScreen#getProfile（第 547 行）读取展示。
     */
    public static class PlayerProfileCache {
        private static PlayerProfileCache instance;
        private PlayerProfile cachedProfile;
        // 懒加载单例
        public static PlayerProfileCache getInstance() {
            if (instance == null) instance = new PlayerProfileCache();
            return instance;
        }
        // 覆盖写入服务端推送的履历数据
        public void updateProfile(PlayerProfile profile) { this.cachedProfile = profile; }
        // 读取缓存的履历数据（可能为 null，表示尚未收到）
        public PlayerProfile getProfile() { return cachedProfile; }
    }
}
