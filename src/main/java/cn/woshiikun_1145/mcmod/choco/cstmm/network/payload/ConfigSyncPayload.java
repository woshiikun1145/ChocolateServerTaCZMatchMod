package cn.woshiikun_1145.mcmod.choco.cstmm.network.payload;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * 配置同步（S2C）：配置 JSON 过大时分包发送。
 * partIndex 从 0 开始，totalParts 为总分包数；客户端按序拼接后解析。
 */
public record ConfigSyncPayload(
        int partIndex,
        int totalParts,
        String data
) implements CustomPayload {

    public static final Id<ConfigSyncPayload> ID = new Id<>(
            Identifier.of("cstmm", "config_sync")
    );

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
