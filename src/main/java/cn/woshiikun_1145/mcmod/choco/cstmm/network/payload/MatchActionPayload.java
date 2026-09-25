package cn.woshiikun_1145.mcmod.choco.cstmm.network.payload;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * 【作用】匹配/商店/档案等客户端动作包，ActionType 决定服务端 handleMatchAction 的分发分支。
 * 字段按动作复用：mapName=地图 ID（加入队列）或商品索引字符串（购买）；
 * team=队伍偏好（1 红/2 蓝/0 无）；target=匹配模式（加入队列）或空（投票/档案/商店）。
 * 【被谁使用】C2S（客户端→服务端）。客户端 MatchMenuScreen（加入/退出队列、投票、请求档案）、
 * CstmmClient（投票快捷键）、ShopScreen（请求商店/购买物品）发送（ClientPlayNetworking.send）；
 * 服务端 NetworkHandler 注册接收器 → handleMatchAction 分发到 QueueManager/VoteManager 等。
 */
public record MatchActionPayload(
        ActionType action,
        String mapName,
        int team,
        String target
) implements CustomPayload {

    // 【作用】包类型标识（cstmm:match_action），由 NetworkHandler 注册并由网络层路由
    public static final Id<MatchActionPayload> ID = new Id<>(
            Identifier.of("cstmm", "match_action")
    );

    // 【作用】编解码器：动作枚举 + 按动作复用的字段与 PacketByteBuf 互转
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

    /**
     * 【作用】动作类型，决定服务端 handleMatchAction 的分发分支。
     * 【被谁使用】由 MatchActionPayload 携带；客户端 MatchMenuScreen/ShopScreen/CstmmClient 构造，
     * 服务端 NetworkHandler switch 分发。新增值只能末尾追加保持 ordinal 兼容（writeEnumConstant 按序号编码）。
     */
    public enum ActionType {
        JOIN_QUEUE,
        LEAVE_QUEUE,
        VOTE_YES,
        VOTE_NO,
        VOTE_OVERTIME,
        SELECT_TEAM,
        BUY_ITEM,
        REQUEST_PROFILE,  // 新增
        REQUEST_SHOP,     // 请求商店数据（按地图下发，末尾追加保持 ordinal 兼容）
        JOIN_QUICK        // 快速匹配：不再复用 JOIN_QUEUE + "quick" 伪地图 ID，避免与真实地图名冲突（末尾追加保持 ordinal 兼容）
    }
}