package cn.woshiikun_1145.mcmod.choco.cstmm.client;

import cn.woshiikun_1145.mcmod.choco.cstmm.Cstmm;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import cn.woshiikun_1145.mcmod.choco.cstmm.network.payload.HandshakeC2SPayload;

/**
 * 客户端与服务端的握手状态。
 * 握手由服务端主动发起：服务端发送握手请求（携带服务端版本），
 * 客户端本地比对版本后回发应答。未安装本模组的服务端不会发起请求，
 * 客户端保留超时提示作为兜底。
 * 【被谁使用】ClientNetworkHandler（握手应答/断线/JOIN/tick 回调）、CstmmClient#registerKeyBindings
 *             （按键门禁）、HudOverlay#init、MatchMenuScreen 与 ConfigScreen（显示版本号）。
 */
@Environment(EnvType.CLIENT)
public class ClientHandshakeState {

    /** 服务端始终无响应（服务端未装模组）时的兜底提示时间（毫秒） */
    private static final long TIMEOUT_MS = 8000;

    // 是否已完成握手（版本匹配），volatile 供网络线程写、客户端线程读
    private static volatile boolean handshaked = false;
    // 是否已给出超时/版本不匹配提示，避免重复提示
    private static boolean timeoutNotified = false;
    // 握手结果聊天提示是否已展示过（服务端重试握手时去重）
    private static boolean handshakeMessageShown = false;
    // 玩家加入服务器的时间戳，用于超时判断；0 表示未加入
    private static long joinTime = 0;

    // 工具类，禁止实例化
    private ClientHandshakeState() {}

    // 握手是否成功，作为客户端全部功能的开关
    public static boolean isHandshaked() {
        return handshaked;
    }

    /**
     * 玩家加入服务器时调用：重置状态并记录时间。
     * 【被谁使用】ClientNetworkHandler#register（ClientPlayConnectionEvents.JOIN 回调，方法引用）。
     */
    public static void onJoin() {
        handshaked = false;
        timeoutNotified = false;
        handshakeMessageShown = false;
        joinTime = System.currentTimeMillis();
    }

    /**
     * 断开连接时调用：清空状态。
     * 【被谁使用】ClientNetworkHandler#register（ClientPlayConnectionEvents.DISCONNECT 回调）。
     */
    public static void onDisconnect() {
        handshaked = false;
        timeoutNotified = false;
        handshakeMessageShown = false;
        joinTime = 0;
    }

    /**
     * 收到服务端握手请求：本地比对版本，并回发应答（服务端据此停止重试）。
     * 【被谁使用】ClientNetworkHandler 的握手请求包处理器（HandshakeS2CPayload 回调）。
     */
    public static void onResponse(String serverVersion) {
        String clientVersion = getClientVersion();
        boolean compatible = clientVersion.equals(serverVersion);

        if (compatible) {
            handshaked = true;
            timeoutNotified = false;
        } else {
            handshaked = false;
            timeoutNotified = true; // 版本不匹配，无需再走超时提示
        }

        // 无论界面状态如何都回发应答，避免服务端无意义重试
        ClientPlayNetworking.send(new HandshakeC2SPayload(clientVersion));

        // 去重：服务端重试握手会重复触发本回调，结果提示只发一次
        if (handshakeMessageShown) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        if (compatible) {
            handshakeMessageShown = true;
            client.player.sendMessage(Text.literal("§a[CSTMM] 与服务端握手成功（版本" + serverVersion + "），功能已启用"), false);
        } else {
            handshakeMessageShown = true;
            client.player.sendMessage(Text.literal(
                    "§c[CSTMM] 与服务端握手失败：版本不匹配（服务端 v" + serverVersion + "），功能已禁用"), false);
        }
    }

    /**
     * 每客户端 tick 调用：超时未握手则提示（仅服务端未装模组时会发生）。
     * 【被谁使用】ClientNetworkHandler#register（ClientTickEvents.END_CLIENT_TICK 回调）。
     */
    public static void tick(MinecraftClient client) {
        if (handshaked || timeoutNotified || joinTime == 0) return;
        if (client.player == null) return;

        if (System.currentTimeMillis() - joinTime > TIMEOUT_MS) {
            timeoutNotified = true;
            client.player.sendMessage(Text.literal(
                    "§c[CSTMM] 未与服务端握手成功，功能已禁用（服务端可能未安装本模组或版本不匹配）"), false);
        }
    }

    /**
     * 读取当前模组版本号（来自 jar 元数据，与握手版本一致），供界面显示复用。
     * 【被谁使用】本类 onResponse 版本比对；MatchMenuScreen、ConfigScreen 界面显示版本。
     */
    public static String getClientVersion() {
        return FabricLoader.getInstance()
                .getModContainer(Cstmm.MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }
}
