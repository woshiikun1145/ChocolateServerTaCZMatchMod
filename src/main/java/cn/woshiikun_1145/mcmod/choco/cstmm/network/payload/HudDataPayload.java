package cn.woshiikun_1145.mcmod.choco.cstmm.network.payload;

import cn.woshiikun_1145.mcmod.choco.cstmm.network.NetworkHandler;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record HudDataPayload(
        String mapName,
        int redKills,
        int blueKills,
        int remainingSeconds,
        boolean inGame
) implements CustomPayload {

    public static final Id<HudDataPayload> ID = new Id<>(
            Identifier.of("cstmm", "hud_data")
    );

    public static final PacketCodec<PacketByteBuf, HudDataPayload> CODEC = PacketCodec.of(
            (payload, buf) -> {
                // mapName 上限 64 UTF-8 字节：writeString 校验的是编码后字节数，
                // encode 时按字节截断（不切断多字节字符）确保永不抛异常
                buf.writeString(NetworkHandler.truncateByUtf8Bytes(payload.mapName(), 64), 64);
                buf.writeInt(payload.redKills());
                buf.writeInt(payload.blueKills());
                buf.writeInt(payload.remainingSeconds());
                buf.writeBoolean(payload.inGame());
            },
            buf -> new HudDataPayload(
                    buf.readString(64),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readBoolean()
            )
    );

    @Override
    public Id<HudDataPayload> getId() {
        return ID;
    }
}