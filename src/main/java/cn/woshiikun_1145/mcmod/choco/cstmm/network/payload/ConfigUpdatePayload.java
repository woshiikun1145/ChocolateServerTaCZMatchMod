package cn.woshiikun_1145.mcmod.choco.cstmm.network.payload;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * C2S 配置更新包（分包）。
 * 原版对 C2S payload 有 32768 字节硬限制，含 base64 背景图的配置 JSON 必须分包发送，
 * 由服务端按玩家重组。分包大小与 ConfigSyncPayload 一致（30000 字符）。
 */
public record ConfigUpdatePayload(
        int partIndex,
        int totalParts,
        String data
) implements CustomPayload {

    public static final Id<ConfigUpdatePayload> ID = new Id<>(
            Identifier.of("cstmm", "config_update")
    );

    public static final PacketCodec<PacketByteBuf, ConfigUpdatePayload> CODEC = PacketCodec.of(
            (payload, buf) -> {
                buf.writeVarInt(payload.partIndex());
                buf.writeVarInt(payload.totalParts());
                buf.writeString(payload.data(), 32767);
            },
            buf -> new ConfigUpdatePayload(
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readString(32767)
            )
    );

    @Override
    public Id<ConfigUpdatePayload> getId() {
        return ID;
    }
}
