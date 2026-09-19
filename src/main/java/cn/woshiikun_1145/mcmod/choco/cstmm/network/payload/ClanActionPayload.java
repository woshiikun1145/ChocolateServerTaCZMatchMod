package cn.woshiikun_1145.mcmod.choco.cstmm.network.payload;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * 战队操作（C2S）。text1/text2/badge/number 按动作复用：
 * CREATE: text1=战队名称, text2=缩写, badge=徽标base64, number=成员上限
 * JOIN / REQUEST_DETAIL / REQUEST_LIST(可带查询串): text1=战队名/查询串
 * TRANSFER / KICK: text1=目标玩家名
 * LEAVE / DISBAND / REQUEST_MINE: 全部空闲
 *
 * 大徽标分片上传：badge 超过 MAX_PART_CHARS(30000) 时由客户端拆成 totalParts 片逐包发送，
 * 每片 badge 字段携带对应分片数据；服务端集齐后拼接再执行动作（只有最后一片触发执行）。
 * totalParts==1 时 badge 为完整数据（兼容小徽标单包直发）。
 */
public record ClanActionPayload(
        ClanAction action,
        String text1,
        String text2,
        String badge,
        int number,
        int badgePartIndex,
        int badgeTotalParts
) implements CustomPayload {

    public static final int MAX_PART_CHARS = 30000;
    public static final int MAX_TOTAL_PARTS = 64;

    /** 便捷构造：单包完整徽标（totalParts=1） */
    public static ClanActionPayload single(ClanAction action, String text1, String text2, String badge, int number) {
        return new ClanActionPayload(action, text1, text2, badge == null ? "" : badge, number, 0, 1);
    }

    public static final Id<ClanActionPayload> ID = new Id<>(
            Identifier.of("cstmm", "clan_action")
    );

    public static final PacketCodec<PacketByteBuf, ClanActionPayload> CODEC = PacketCodec.of(
            (payload, buf) -> {
                buf.writeEnumConstant(payload.action());
                buf.writeString(payload.text1(), 192);
                buf.writeString(payload.text2(), 16);
                buf.writeString(payload.badge(), MAX_PART_CHARS);
                buf.writeInt(payload.number());
                buf.writeVarInt(payload.badgePartIndex());
                buf.writeVarInt(payload.badgeTotalParts());
            },
            buf -> new ClanActionPayload(
                    buf.readEnumConstant(ClanAction.class),
                    buf.readString(192),
                    buf.readString(16),
                    buf.readString(MAX_PART_CHARS),
                    buf.readInt(),
                    buf.readVarInt(),
                    buf.readVarInt()
            )
    );

    @Override
    public Id<ClanActionPayload> getId() {
        return ID;
    }

    public enum ClanAction {
        REQUEST_LIST,
        REQUEST_DETAIL,
        REQUEST_MINE,
        CREATE,
        JOIN,
        LEAVE,
        DISBAND,
        TRANSFER,
        KICK,
        EDIT
    }
}
