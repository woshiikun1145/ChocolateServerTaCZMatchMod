package cn.woshiikun_1145.mcmod.choco.cstmm.network.payload;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record MatchStatusPayload(
        StatusType type,
        String message,
        int redKills,
        int blueKills
) implements CustomPayload {

    public static final Id<MatchStatusPayload> ID = new Id<>(
            Identifier.of("cstmm", "match_status")
    );

    public static final PacketCodec<PacketByteBuf, MatchStatusPayload> CODEC = PacketCodec.of(
            (payload, buf) -> {
                buf.writeEnumConstant(payload.type());
                buf.writeString(payload.message(), 128);
                buf.writeInt(payload.redKills());
                buf.writeInt(payload.blueKills());
            },
            buf -> new MatchStatusPayload(
                    buf.readEnumConstant(StatusType.class),
                    buf.readString(128),
                    buf.readInt(),
                    buf.readInt()
            )
    );

    @Override
    public Id<MatchStatusPayload> getId() {
        return ID;
    }

    public enum StatusType {
        MATCH_STARTING,
        MATCH_ENDED,
        VOTE_STARTED,
        VOTE_RESULT,
        COUNTDOWN
    }
}