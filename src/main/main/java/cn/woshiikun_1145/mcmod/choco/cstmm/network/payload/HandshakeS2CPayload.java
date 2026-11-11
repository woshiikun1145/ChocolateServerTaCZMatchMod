package cn.woshiikun_1145.mcmod.choco.cstmm.network.payload;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * 握手请求（S2C）：服务端主动发起，携带服务端模组版本。
 * 客户端本地比对版本后回发 {@link HandshakeC2SPayload}。
 *
 * 【被谁使用】S2C（服务端→客户端）。服务端 NetworkHandler#sendHandshakeRequest 在玩家加入服务器时发起，
 * tickHandshake 对未应答玩家每秒重试（最多 2 次）；客户端 ClientNetworkHandler 注册接收器
 * → ClientHandshakeState#onResponse 本地比对版本并回发握手应答。
 */
public record HandshakeS2CPayload(String serverVersion) implements CustomPayload {
    // 【作用】包类型标识（cstmm:handshake_s2c），由 NetworkHandler 注册并由网络层路由
    public static final Id<HandshakeS2CPayload> ID = new Id<>(
            Identifier.of("cstmm", "handshake_s2c")
    );

    // 【作用】编解码器：服务端版本字符串与 PacketByteBuf 互转（读取上限 64 字符）
    public static final PacketCodec<PacketByteBuf, HandshakeS2CPayload> CODEC = PacketCodec.of(
            (payload, buf) -> buf.writeString(payload.serverVersion()),
            buf -> new HandshakeS2CPayload(buf.readString(64))
    );

    @Override
    public Id<HandshakeS2CPayload> getId() {
        return ID;
    }
}
