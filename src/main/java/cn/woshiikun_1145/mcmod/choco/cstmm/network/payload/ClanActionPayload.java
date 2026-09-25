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
 * 徽标上限：base64 解码后 ≤ 48KiB（ClanManager.validateBadge），URL 徽标直接填 URL 不分片。
 *
 * 【被谁使用】C2S（客户端→服务端）。客户端 ClanTabPanel#sendAction 在战队页操作
 * （创建/加入/退出/解散/转让/踢人/编辑/请求列表/详情/我的）时分包发送；
 * 服务端 NetworkHandler 注册接收器（ServerPlayNetworking.registerGlobalReceiver），
 * 经 handleClanActionMaybeChunked 重组分片后由 handleClanAction 分发到 ClanManager。
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

    // 【作用】单分片最大字符数：客户端上传拆片与服务端解码校验共用
    public static final int MAX_PART_CHARS = 30000;
    /** 48KiB 徽标 base64（≤65536 字符）每片 30000 字符时最多 3 片（服务端校验解码后大小） */
    public static final int MAX_TOTAL_PARTS = 3;

    /** 便捷构造：单包完整徽标（totalParts=1） */
    // 【被谁使用】ClanTabPanel#sendAction（≤30000 字符单包直发）与 NetworkHandler#handleClanActionMaybeChunked（分片拼齐后重建单包）
    public static ClanActionPayload single(ClanAction action, String text1, String text2, String badge, int number) {
        return new ClanActionPayload(action, text1, text2, badge == null ? "" : badge, number, 0, 1);
    }

    // 【作用】包类型标识（cstmm:clan_action），由 NetworkHandler 注册并由网络层路由
    public static final Id<ClanActionPayload> ID = new Id<>(
            Identifier.of("cstmm", "clan_action")
    );

    // 【作用】编解码器：按动作复用字段与 PacketByteBuf 互转，各字符串上限与解码端一致
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

    /**
     * 【作用】战队操作类型，决定服务端 handleClanAction 的分发分支。
     * 【被谁使用】由 ClanActionPayload 携带；客户端 ClanTabPanel 构造动作，服务端 NetworkHandler switch 分发。
     */
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
