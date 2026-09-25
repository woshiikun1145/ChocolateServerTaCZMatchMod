package cn.woshiikun_1145.mcmod.choco.cstmm.network.payload;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * 配置同步（S2C）：核心配置 JSON（{"hash":..,"maps":[..],"global":{..}}，不含 inUseMaps）
 * 过大时分包发送。partIndex 从 0 开始，totalParts 为总分包数；客户端按序拼接后解析。
 * hash 字段用于客户端磁盘缓存比对（哈希握手省带宽）；inUseMaps 由 ConfigMetaPayload 单独下发。
 *
 * 【被谁使用】S2C（服务端→客户端）。服务端 NetworkHandler#sendConfigSync 在玩家 JOIN 哈希握手
 * 不匹配/超时兜底、收到 RequestConfigSyncPayload（哈希不匹配）请求、配置保存后全服广播时分包发送；
 * 客户端 ClientNetworkHandler 注册接收器按 partIndex 重组 → applyConfigSync 解析进 ConfigDataCache
 * 并持久化到磁盘缓存。
 */
public record ConfigSyncPayload(
        int partIndex,
        int totalParts,
        String data
) implements CustomPayload {

    // 【作用】包类型标识（cstmm:config_sync），由 NetworkHandler 注册并由网络层路由
    public static final Id<ConfigSyncPayload> ID = new Id<>(
            Identifier.of("cstmm", "config_sync")
    );

    // 【作用】编解码器：分包序号/总数（VarInt）+ 分片数据（上限 32767 字节）与 PacketByteBuf 互转
    public static final PacketCodec<PacketByteBuf, ConfigSyncPayload> CODEC = PacketCodec.of(
            (payload, buf) -> {
                buf.writeVarInt(payload.partIndex());
                buf.writeVarInt(payload.totalParts());
                buf.writeString(payload.data(), 32767);
            },
            buf -> new ConfigSyncPayload(
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readString(32767)
            )
    );

    @Override
    public Id<ConfigSyncPayload> getId() {
        return ID;
    }
}
