package cn.woshiikun_1145.mcmod.choco.cstmm.network.payload;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * 全服履历排行（S2C）：紧凑 JSON（{"players":[{"name":..,"kills":..,"deaths":..,"matches":..,"wins":..},...]}）
 * 过大时分包发送，分包结构与 ConfigSyncPayload 相同（partIndex 从 0 开始，totalParts 为总数）。
 * 排序方式（K/D、胜率、总击杀）由客户端本地选择，服务端不做排序。
 *
 * 【被谁使用】S2C（服务端→客户端）。服务端 NetworkHandler#sendLeaderboard 在收到
 * MatchActionPayload 的 REQUEST_LEADERBOARD 动作（履历页打开）时分包发送；
 * 客户端 ClientNetworkHandler 注册接收器按 partIndex 重组 → LeaderboardCache 供履历页排行渲染。
 */
public record LeaderboardPayload(
        int partIndex,
        int totalParts,
        String data
) implements CustomPayload {

    // 【作用】包类型标识（cstmm:leaderboard），由 NetworkHandler 注册并由网络层路由
    public static final Id<LeaderboardPayload> ID = new Id<>(
            Identifier.of("cstmm", "leaderboard")
    );

    // 【作用】编解码器：分包序号/总数（VarInt）+ 分片数据（上限 32767 字节）与 PacketByteBuf 互转
    public static final PacketCodec<PacketByteBuf, LeaderboardPayload> CODEC = PacketCodec.of(
            (payload, buf) -> {
                buf.writeVarInt(payload.partIndex());
                buf.writeVarInt(payload.totalParts());
                buf.writeString(payload.data(), 32767);
            },
            buf -> new LeaderboardPayload(
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readString(32767)
            )
    );

    @Override
    public Id<LeaderboardPayload> getId() {
        return ID;
    }
}
