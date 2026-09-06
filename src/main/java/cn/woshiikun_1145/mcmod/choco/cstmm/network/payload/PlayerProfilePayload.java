package cn.woshiikun_1145.mcmod.choco.cstmm.network.payload;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record PlayerProfilePayload(
        String jsonData
) implements CustomPayload {

    public static final Id<PlayerProfilePayload> ID = new Id<>(
            Identifier.of("cstmm", "player_profile")
    );

    public static final PacketCodec<PacketByteBuf, PlayerProfilePayload> CODEC = PacketCodec.of(
            (payload, buf) -> buf.writeString(payload.jsonData(), 65536),
            buf -> new PlayerProfilePayload(buf.readString(65536))
    );

    @Override
    public Id<PlayerProfilePayload> getId() {
        return ID;
    }
}