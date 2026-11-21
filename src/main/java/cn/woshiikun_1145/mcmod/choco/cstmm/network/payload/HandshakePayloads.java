package cn.woshiikun_1145.mcmod.choco.cstmm.network.payload;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * 【作用】握手域 payload 容器：集中存放版本握手相关的 S2C/C2S 包定义。
 *         由原 HandshakeS2CPayload.java、HandshakeC2SPayload.java 合并而来，
 *         各嵌套类型行为与网络字节格式与拆分前完全一致（ID/CODEC 未改动）。
 */
public final class HandshakePayloads {

    // 【作用】工具容器，禁止实例化
    private HandshakePayloads() {
    }

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
}
