package cn.woshiikun_1145.mcmod.choco.cstmm.client.network;

import cn.woshiikun_1145.mcmod.choco.cstmm.Cstmm;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.ClientHandshakeState;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.cache.BadgeCache;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.cache.ClanCache;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.cache.ConfigDataCache;
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
import java.util.ArrayList;
import java.util.List;

@Environment(EnvType.CLIENT)
public class ClientNetworkHandler {

    private static boolean registered = false;
    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(BlockPos.class, new BlockPosAdapter())
            .create();

    // ===== 配置同步分包重组 =====
    private static final StringBuilder pendingConfigJson = new StringBuilder();
    private static int pendingTotalParts = -1;
    private static int receivedParts = 0;

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
        ClientPlayNetworking.registerGlobalReceiver(ConfigSyncPayload.ID, (payload, context) -> context.client().execute(() -> {
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

            if (receivedParts >= pendingTotalParts) {
                String json = pendingConfigJson.toString();
                pendingConfigJson.setLength(0);
                pendingTotalParts = -1;
                receivedParts = 0;
                applyConfigSync(json);
            }
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
        // 握手由服务端主动发起，客户端只重置本地状态
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(
                ClientHandshakeState::onJoin));

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
            // 断线时重置 HUD 显示并清空配置/商店/战队/队列/徽标缓存，避免残留上一服务器的数据
            HudOverlay.reset();
            ConfigDataCache.getInstance().clear();
            ShopDataCache.getInstance().clear();
            ClanCache.getInstance().clear();
            QueueStatusCache.getInstance().clear();
            BadgeCache.clear();
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> ClientHandshakeState.tick(client));

        Cstmm.LOGGER.info("[CSTMM - ClientNetwork] Registered client network handlers");
    }

    /** 解析完整配置 JSON 并更新本地缓存 */
    private static void applyConfigSync(String json) {
        try {
            if (json == null || json.isEmpty()) {
                Cstmm.LOGGER.warn("[CSTMM - ClientNetwork] Received empty config sync");
                return;
            }

            var obj = GSON.fromJson(json, com.google.gson.JsonObject.class);
            Type mapListType = new TypeToken<List<MapConfig>>() {}.getType();
            List<MapConfig> maps = GSON.fromJson(obj.get("maps"), mapListType);
            GlobalConfig global = GSON.fromJson(obj.get("global"), GlobalConfig.class);
            List<String> inUseMaps = GSON.fromJson(obj.get("inUseMaps"),
                    new TypeToken<List<String>>() {}.getType());

            ConfigDataCache cache = ConfigDataCache.getInstance();
            cache.updateMaps(maps);
            cache.updateGlobalConfig(global);
            cache.updateInUseMaps(inUseMaps != null ? inUseMaps : new ArrayList<>());

            Cstmm.LOGGER.debug("[CSTMM - ClientNetwork] Config synced: {} maps",
                    maps != null ? maps.size() : 0);

            // 刷新匹配菜单
            if (MinecraftClient.getInstance().currentScreen instanceof MatchMenuScreen screen) {
                screen.refreshMaps();
            }
            // 刷新配置界面（如果打开）
            if (MinecraftClient.getInstance().currentScreen instanceof ConfigScreen screen) {
                screen.refresh();
            }

        } catch (Exception e) {
            Cstmm.LOGGER.error("[CSTMM - ClientNetwork] Failed to parse config sync", e);
        }
    }

    // 发送配置请求（带客户端自检：每秒超过 50 次触发 ConfigSyncRateLimitException 使游戏崩溃）
    public static void requestConfigSync() {
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
        ClientPlayNetworking.send(new RequestConfigSyncPayload());
    }

    private static long syncRequestWindowStart = 0L;
    private static int syncRequestCount = 0;

    /**
     * 分包发送 C2S 配置更新。原版对 C2S payload 有 32768 字节硬限制，
     * 且 writeString 校验的是 UTF-8 编码后的字节数，故按 UTF-8 字节边界切分
     * （30000 字节/片，不切断多字节字符），服务端按玩家重组。
     */
    public static void sendConfigUpdate(String json) {
        List<String> chunks = NetworkHandler.splitByUtf8Bytes(json, 30000);
        int totalParts = chunks.size();
        for (int i = 0; i < totalParts; i++) {
            ClientPlayNetworking.send(new ConfigUpdatePayload(i, totalParts, chunks.get(i)));
        }
    }

    // ===== 履历缓存 =====
    public static class PlayerProfileCache {
        private static PlayerProfileCache instance;
        private PlayerProfile cachedProfile;
        public static PlayerProfileCache getInstance() {
            if (instance == null) instance = new PlayerProfileCache();
            return instance;
        }
        public void updateProfile(PlayerProfile profile) { this.cachedProfile = profile; }
        public PlayerProfile getProfile() { return cachedProfile; }
    }
}
