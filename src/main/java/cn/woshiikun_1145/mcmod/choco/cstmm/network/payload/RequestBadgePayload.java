package cn.woshiikun_1145.mcmod.choco.cstmm.network.payload;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * 徽标缓存缺失请求（C2S）：客户端收到 clan_data 时若本地没有该 badgeId 的完整数据，
 * 发送本包请求服务端重新下发全部分片（覆盖断线重连后客户端缓存丢失的场景）。
 *
 * 【被谁使用】C2S（客户端→服务端）。客户端 BadgeCache#requestIfMissing 在收到 clan_data
 * 引用未知徽标 id 时发送（ClientPlayNetworking.send）；服务端 NetworkHandler 注册接收器
 * → 查表重新下发该徽标的全部分片（BadgePayload）。
 */
public record RequestBadgePayload(String badgeId) implements CustomPayload {

    // 【作用】包类型标识（cstmm:request_badge），由 NetworkHandler 注册并由网络层路由
    public static final Id<RequestBadgePayload> ID = new Id<>(
            Identifier.of("cstmm", "request_badge")
    );

    // 【作用】编解码器：badgeId（上限 16 字符）与 PacketByteBuf 互转
    public static final PacketCodec<PacketByteBuf, RequestBadgePayload> CODEC = PacketCodec.of(
            (payload, buf) -> buf.writeString(payload.badgeId(), 16),
            buf -> new RequestBadgePayload(buf.readString(16))
    );

    @Override
    public Id<RequestBadgePayload> getId() {
        return ID;
    }
}
