package cn.woshiikun_1145.mcmod.choco.cstmm.network.payload;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * 【作用】客户端徽标缓存上报（C2S）：客户端进服后把本机磁盘缓存中已有的徽标 id 列表
 *         （逗号分隔的 16 字符 hex，最多 {@link #MAX_IDS} 个）告知服务端，
 *         服务端将其加入该玩家的"已下发"集合，后续 MINE/DETAIL 引用这些徽标时
 *         跳过分片重发（只发 16 字符 id 引用）——徽标磁盘缓存省带宽的服务端感知半环。
 *         谎报无害：客户端声称已有却无缓存时只是自己界面显示占位（自伤行为）。
 * 【被谁使用】C2S（客户端→服务端）。客户端 ClientNetworkHandler 的 JOIN 回调
 *         （BadgeCache#diskBadgeIds 列目录收集）；服务端 NetworkHandler 注册接收器 →
 *         逐个校验 hex 格式后加入 sentBadges。
 */
public record BadgeKnownPayload(
        String badgeIds
) implements CustomPayload {

    /** 单次上报的徽标 id 数量上限（客户端截断 + 服务端校验双重防护） */
    public static final int MAX_IDS = 256;
    /** 编码后字符串上限：256 个 id × 17 字符（16 hex + 逗号）= 4352 字符，余量放宽 */
    private static final int MAX_WIRE_CHARS = 8192;

    // 【作用】包类型标识（cstmm:badge_known），由 NetworkHandler 注册并由网络层路由
    public static final Id<BadgeKnownPayload> ID = new Id<>(
            Identifier.of("cstmm", "badge_known")
    );

    // 【作用】编解码器：逗号分隔的徽标 id 列表字符串与 PacketByteBuf 互转
    public static final PacketCodec<PacketByteBuf, BadgeKnownPayload> CODEC = PacketCodec.of(
            (payload, buf) -> buf.writeString(payload.badgeIds(), MAX_WIRE_CHARS),
            buf -> new BadgeKnownPayload(buf.readString(MAX_WIRE_CHARS))
    );

    @Override
    public Id<BadgeKnownPayload> getId() {
        return ID;
    }
}
