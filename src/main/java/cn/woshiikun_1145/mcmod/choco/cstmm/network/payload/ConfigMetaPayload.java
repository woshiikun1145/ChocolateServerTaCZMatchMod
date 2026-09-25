package cn.woshiikun_1145.mcmod.choco.cstmm.network.payload;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * 【作用】配置元数据包（S2C，小包）：携带当前核心配置（maps+global）的 SHA-256 哈希与
 *         inUseMaps（对局使用中地图列表）JSON。哈希握手省带宽的核心：
 *         服务端先发哈希，客户端与自己磁盘缓存比对，一致则只回哈希不再重复接收全量配置。
 *         inUseMaps 从 ConfigSyncPayload 中剥离、随本包单独下发，避免对局开始/结束
 *         频繁改变配置 JSON 哈希导致缓存频繁失效。
 * 【被谁使用】S2C（服务端→客户端）。服务端 NetworkHandler 在玩家 JOIN（哈希握手首包）、
 *         哈希匹配回应、全量同步随行、配置保存广播时发送；客户端 ClientNetworkHandler
 *         注册接收器 → 比对磁盘缓存哈希后决定回报哈希或请求全量，并更新 inUseMaps 缓存。
 */
public record ConfigMetaPayload(
        String configHash,
        String inUseMapsJson
) implements CustomPayload {

    // 【作用】包类型标识（cstmm:config_meta），由 NetworkHandler 注册并由网络层路由
    public static final Id<ConfigMetaPayload> ID = new Id<>(
            Identifier.of("cstmm", "config_meta")
    );

    // 【作用】编解码器：哈希（≤128 字符，SHA-256 hex 为 64）+ inUseMaps JSON（≤32767 字节）与 PacketByteBuf 互转
    public static final PacketCodec<PacketByteBuf, ConfigMetaPayload> CODEC = PacketCodec.of(
            (payload, buf) -> {
                buf.writeString(payload.configHash(), 128);
                buf.writeString(payload.inUseMapsJson(), 32767);
            },
            buf -> new ConfigMetaPayload(
                    buf.readString(128),
                    buf.readString(32767)
            )
    );

    @Override
    public Id<ConfigMetaPayload> getId() {
        return ID;
    }
}
