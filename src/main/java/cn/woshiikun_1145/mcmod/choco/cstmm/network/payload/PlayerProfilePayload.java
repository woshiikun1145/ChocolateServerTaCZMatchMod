package cn.woshiikun_1145.mcmod.choco.cstmm.network.payload;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * 【作用】玩家档案同步包，jsonData 为 PlayerProfile 的 JSON 序列化（战绩/库存/货币等）。
 * 【被谁使用】S2C（服务端→客户端）。服务端 NetworkHandler#sendPlayerProfile 由 EventListener
 * （玩家加入回调）与 PlayerDataManager#syncProfileToPlayer（档案变更、MATCH_ACTION 的
 * REQUEST_PROFILE 请求）触发；客户端 ClientNetworkHandler 注册接收器解析进 PlayerProfileCache。
 */
public record PlayerProfilePayload(
        String jsonData
) implements CustomPayload {

    // 【作用】包类型标识（cstmm:player_profile），由 NetworkHandler 注册并由网络层路由
    public static final Id<PlayerProfilePayload> ID = new Id<>(
            Identifier.of("cstmm", "player_profile")
    );

    // 【作用】编解码器：JSON 字符串（上限 65536 字节）与 PacketByteBuf 互转
    public static final PacketCodec<PacketByteBuf, PlayerProfilePayload> CODEC = PacketCodec.of(
            (payload, buf) -> buf.writeString(payload.jsonData(), 65536),
            buf -> new PlayerProfilePayload(buf.readString(65536))
    );

    @Override
    public Id<PlayerProfilePayload> getId() {
        return ID;
    }
}