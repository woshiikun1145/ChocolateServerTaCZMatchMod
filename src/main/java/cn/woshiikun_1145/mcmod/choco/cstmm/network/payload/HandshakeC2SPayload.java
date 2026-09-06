package cn.woshiikun_1145.mcmod.choco.cstmm.network.payload;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * 握手请求（C2S）：客户端加入后发送，携带客户端模组版本
 */
public record HandshakeC2SPayload(String clientVersion) implements CustomPayload {
    public static final Id<HandshakeC2SPayload> ID = new Id<>(
            Identifier.of("cstmm", "handshake_c2s")
    );

    public static final PacketCodec<PacketByteBuf, HandshakeC2SPayload> CODEC = PacketCodec.of(
            (payload, buf) -> buf.writeString(payload.clientVersion()),
            buf -> new HandshakeC2SPayload(buf.readString(64))
    );

    @Override
    public Id<HandshakeC2SPayload> getId() {
        return ID;
    }
}
