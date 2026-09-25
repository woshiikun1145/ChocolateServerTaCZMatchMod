package cn.woshiikun_1145.mcmod.choco.cstmm.network.payload;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * 【作用】配置同步请求（C2S）：携带客户端本地缓存的核心配置哈希（无缓存/哈希不适用时为空串）。
 *         服务端收到后与自己当前配置哈希比对：一致 → 仅回 ConfigMetaPayload（更新 inUseMaps，
 *         省掉全量 JSON 下发）；不一致 → 全量 ConfigSyncPayload + ConfigMetaPayload。
 * 【被谁使用】C2S（客户端→服务端）。客户端 ClientNetworkHandler 在 JOIN 哈希握手（收到
 *         ConfigMetaPayload 后）与打开/刷新配置界面（ConfigScreen，带每秒 50 次客户端自限）时发送；
 *         服务端 NetworkHandler 注册接收器 → 哈希比对后回发。编解码对空载荷做防御
 *         （旧版客户端发的是无字段空包，读到 0 字节时按空串处理，避免解码越界断连）。
 */
public record RequestConfigSyncPayload(
        String clientHash
) implements CustomPayload {
    // 【作用】包类型标识（cstmm:request_config_sync），由 NetworkHandler 注册并由网络层路由
    public static final Id<RequestConfigSyncPayload> ID = new Id<>(
            Identifier.of("cstmm", "request_config_sync")
    );

    // 【作用】编解码器：客户端缓存哈希（≤128 字符）与 PacketByteBuf 互转；读取端对空载荷防御（兼容旧版客户端空包）
    public static final PacketCodec<PacketByteBuf, RequestConfigSyncPayload> CODEC = PacketCodec.of(
            (payload, buf) -> buf.writeString(payload.clientHash(), 128),
            buf -> new RequestConfigSyncPayload(
                    buf.readableBytes() > 0 ? buf.readString(128) : ""
            )
    );

    @Override
    public Id<RequestConfigSyncPayload> getId() {
        return ID;
    }
}
