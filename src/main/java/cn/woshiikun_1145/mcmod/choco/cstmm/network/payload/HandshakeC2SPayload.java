package cn.woshiikun_1145.mcmod.choco.cstmm.network.payload;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * 握手请求（C2S）：客户端加入后发送，携带客户端模组版本
 *
 * 【被谁使用】C2S（客户端→服务端）。客户端 ClientHandshakeState#onResponse 收到服务端
 * HandshakeS2CPayload 并比对版本后回发（ClientPlayNetworking.send）；服务端 NetworkHandler
 * 注册接收器校验版本匹配并移出握手等待集合。
 */
public record HandshakeC2SPayload(String clientVersion) implements CustomPayload {
    // 【作用】包类型标识（cstmm:handshake_c2s），由 NetworkHandler 注册并由网络层路由
    public static final Id<HandshakeC2SPayload> ID = new Id<>(
            Identifier.of("cstmm", "handshake_c2s")
    );

    // 【作用】编解码器：客户端版本字符串与 PacketByteBuf 互转（读取上限 64 字符）
    public static final PacketCodec<PacketByteBuf, HandshakeC2SPayload> CODEC = PacketCodec.of(
            (payload, buf) -> buf.writeString(payload.clientVersion()),
            buf -> new HandshakeC2SPayload(buf.readString(64))
    );

    @Override
    public Id<HandshakeC2SPayload> getId() {
        return ID;
    }
}
