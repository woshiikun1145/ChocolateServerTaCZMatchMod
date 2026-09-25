package cn.woshiikun_1145.mcmod.choco.cstmm.network.payload;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * 战队数据（S2C）。kind: MINE（我的战队状态）/ LIST（随机战队列表）/ DETAIL（选中战队详情）。
 * json 为对应快照；操作类反馈（成功/失败原因）由服务端直接发聊天消息，不走本包。
 *
 * 【被谁使用】S2C（服务端→客户端）。服务端 NetworkHandler#sendClanData 响应 ClanActionPayload
 * 的各请求（LIST/DETAIL/MINE），sendClanMineTo 在战队变更时主动推送 MINE；
 * 客户端 ClientNetworkHandler 注册接收器 → ClanCache#update 缓存并检查徽标缺失。
 */
public record ClanDataPayload(
        String kind,
        String json
) implements CustomPayload {

    // 【作用】包类型标识（cstmm:clan_data），由 NetworkHandler 注册并由网络层路由
    public static final Id<ClanDataPayload> ID = new Id<>(
            Identifier.of("cstmm", "clan_data")
    );

    // 【作用】编解码器：kind（上限 16 字符）+ 快照 JSON（上限 65536 字节）与 PacketByteBuf 互转
    public static final PacketCodec<PacketByteBuf, ClanDataPayload> CODEC = PacketCodec.of(
            (payload, buf) -> {
                buf.writeString(payload.kind(), 16);
                buf.writeString(payload.json(), 65536);
            },
            buf -> new ClanDataPayload(
                    buf.readString(16),
                    buf.readString(65536)
            )
    );

    @Override
    public Id<ClanDataPayload> getId() {
        return ID;
    }
}
