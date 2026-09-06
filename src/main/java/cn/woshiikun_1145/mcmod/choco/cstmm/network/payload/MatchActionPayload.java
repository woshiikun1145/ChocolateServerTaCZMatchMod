package cn.woshiikun_1145.mcmod.choco.cstmm.network.payload;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record MatchActionPayload(
        ActionType action,
        String mapName,
        int team,
        String target
) implements CustomPayload {

    public static final Id<MatchActionPayload> ID = new Id<>(
            Identifier.of("cstmm", "match_action")
    );

    public static final PacketCodec<PacketByteBuf, MatchActionPayload> CODEC = PacketCodec.of(
            (payload, buf) -> {
                buf.writeEnumConstant(payload.action());
                buf.writeString(payload.mapName(), 64);
                buf.writeInt(payload.team());
                buf.writeString(payload.target(), 64);
            },
            buf -> new MatchActionPayload(
                    buf.readEnumConstant(ActionType.class),
                    buf.readString(64),
                    buf.readInt(),
                    buf.readString(64)
            )
    );

    @Override
    public Id<MatchActionPayload> getId() {
        return ID;
    }

    public enum ActionType {
        JOIN_QUEUE,
        LEAVE_QUEUE,
        VOTE_YES,
        VOTE_NO,
        VOTE_OVERTIME,
        SELECT_TEAM,
        BUY_ITEM,
        REQUEST_PROFILE,  // 新增
        REQUEST_SHOP      // 请求商店数据（按地图下发，末尾追加保持 ordinal 兼容）
    }
}