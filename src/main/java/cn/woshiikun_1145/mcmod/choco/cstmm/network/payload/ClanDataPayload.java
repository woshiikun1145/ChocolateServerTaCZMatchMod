package cn.woshiikun_1145.mcmod.choco.cstmm.network.payload;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * 战队数据（S2C）。kind: MINE（我的战队状态）/ LIST（随机战队列表）/ DETAIL（选中战队详情）。
 * json 为对应快照；操作类反馈（成功/失败原因）由服务端直接发聊天消息，不走本包。
 */
public record ClanDataPayload(
        String kind,
        String json
) implements CustomPayload {

    public static final Id<ClanDataPayload> ID = new Id<>(
            Identifier.of("cstmm", "clan_data")
    );

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
