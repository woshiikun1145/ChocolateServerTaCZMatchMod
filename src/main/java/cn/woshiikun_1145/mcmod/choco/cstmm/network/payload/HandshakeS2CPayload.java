package cn.woshiikun_1145.mcmod.choco.cstmm.network.payload;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * 握手请求（S2C）：服务端主动发起，携带服务端模组版本。
 * 客户端本地比对版本后回发 {@link HandshakeC2SPayload}。
 */
public record HandshakeS2CPayload(String serverVersion) implements CustomPayload {
    public static final Id<HandshakeS2CPayload> ID = new Id<>(
            Identifier.of("cstmm", "handshake_s2c")
    );

    public static final PacketCodec<PacketByteBuf, HandshakeS2CPayload> CODEC = PacketCodec.of(
            (payload, buf) -> buf.writeString(payload.serverVersion()),
            buf -> new HandshakeS2CPayload(buf.readString(64))
    );

    @Override
    public Id<HandshakeS2CPayload> getId() {
        return ID;
    }
}
