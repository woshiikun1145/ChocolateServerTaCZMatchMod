package cn.woshiikun_1145.mcmod.choco.cstmm.network.payload;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * 战队徽标分片下发（S2C）。badgeId = 徽标 base64 的 SHA-256 前 8 字节 hex（内容寻址，16 字符），
 * 客户端按 id 重组缓存；clan_data JSON 中的 badge 字段携带该 id 而非完整 base64
 * （clan_data 单包 65536 字节上限，大徽标必须分片）。
 * data 每片 ≤30000 字符；totalParts ∈ [1,128]（理论上限约 3.8MB base64）。
 */
public record BadgePayload(
        String badgeId,
        int partIndex,
        int totalParts,
        String data
) implements CustomPayload {

    public static final int MAX_PART_CHARS = 30000;
    public static final int MAX_TOTAL_PARTS = 128;

    public static final Id<BadgePayload> ID = new Id<>(
            Identifier.of("cstmm", "badge")
    );

    public static final PacketCodec<PacketByteBuf, BadgePayload> CODEC = PacketCodec.of(
            (payload, buf) -> {
                buf.writeString(payload.badgeId(), 16);
                buf.writeVarInt(payload.partIndex());
                buf.writeVarInt(payload.totalParts());
                buf.writeString(payload.data(), MAX_PART_CHARS);
            },
            buf -> new BadgePayload(
                    buf.readString(16),
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readString(MAX_PART_CHARS)
            )
    );

    @Override
    public Id<BadgePayload> getId() {
        return ID;
    }
}
