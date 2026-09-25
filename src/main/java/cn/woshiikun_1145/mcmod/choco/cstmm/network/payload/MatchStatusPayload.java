package cn.woshiikun_1145.mcmod.choco.cstmm.network.payload;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * 【作用】对局状态广播包（状态类型/提示消息/红蓝比分），驱动客户端的对局提示与比分展示；
 * message 由 NetworkHandler#sendMatchStatus 按 128 UTF-8 字节统一截断，确保 writeString 不越界。
 * 【被谁使用】S2C（服务端→客户端）。服务端 MatchManager#broadcastMatchStatus（开局/倒计时/结束）
 * 与 VoteManager（投票开始/结果）经 NetworkHandler#sendMatchStatus 发送；
 * 客户端 ClientNetworkHandler 注册接收器，将 message 显示到聊天栏。
 */
public record MatchStatusPayload(
        StatusType type,
        String message,
        int redKills,
        int blueKills
) implements CustomPayload {

    // 【作用】包类型标识（cstmm:match_status），由 NetworkHandler 注册并由网络层路由
    public static final Id<MatchStatusPayload> ID = new Id<>(
            Identifier.of("cstmm", "match_status")
    );

    // 【作用】编解码器：枚举 + 消息（上限 128）+ 双方比分与 PacketByteBuf 互转
    public static final PacketCodec<PacketByteBuf, MatchStatusPayload> CODEC = PacketCodec.of(
            (payload, buf) -> {
                buf.writeEnumConstant(payload.type());
                buf.writeString(payload.message(), 128);
                buf.writeInt(payload.redKills());
                buf.writeInt(payload.blueKills());
            },
            buf -> new MatchStatusPayload(
                    buf.readEnumConstant(StatusType.class),
                    buf.readString(128),
                    buf.readInt(),
                    buf.readInt()
            )
    );

    @Override
    public Id<MatchStatusPayload> getId() {
        return ID;
    }

    /**
     * 【作用】对局状态类型，决定消息语义（开局/结束/投票开始/投票结果/倒计时）。
     * 【被谁使用】由 MatchStatusPayload 携带；服务端 MatchManager/VoteManager 构造，客户端仅展示消息文本。
     */
    public enum StatusType {
        MATCH_STARTING,
        MATCH_ENDED,
        VOTE_STARTED,
        VOTE_RESULT,
        COUNTDOWN
    }
}