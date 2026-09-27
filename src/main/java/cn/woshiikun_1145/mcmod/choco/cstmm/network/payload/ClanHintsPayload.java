package cn.woshiikun_1145.mcmod.choco.cstmm.network.payload;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * 【作用】战队名提示快照（S2C）：全服全部战队名与其成员名的紧凑 JSON
 *         （{"战队名":["成员1","成员2",...], ...}），供客户端 Tab 补全使用——
 *         /cstmm data get|edit|delete clan 的建议 provider 在客户端执行，
 *         而战队数据只在服务端，必须把提示数据同步到客户端才能出建议。
 * 【被谁使用】S2C（服务端→客户端）。服务端 NetworkHandler 在玩家 JOIN 与战队任何
 * 变更（ClanManager.save 落盘点）时全服广播；客户端 ClientNetworkHandler 注册接收器
 * → CommandHintCache#set 供命令补全读取。
 */
public record ClanHintsPayload(
        String json
) implements CustomPayload {

    // 【作用】包类型标识（cstmm:clan_hints），由 NetworkHandler 注册并由网络层路由
    public static final Id<ClanHintsPayload> ID = new Id<>(
            Identifier.of("cstmm", "clan_hints")
    );

    // 提示 JSON 字节上限（战队名 ≤128 字符 × 数量 + 成员名列表，正常规模远小于此）
    private static final int MAX_HINTS_BYTES = 32767;

    // 【作用】编解码器：提示 JSON（上限 32767 字节）与 PacketByteBuf 互转
    public static final PacketCodec<PacketByteBuf, ClanHintsPayload> CODEC = PacketCodec.of(
            (payload, buf) -> buf.writeString(payload.json(), MAX_HINTS_BYTES),
            buf -> new ClanHintsPayload(buf.readString(MAX_HINTS_BYTES))
    );

    @Override
    public Id<ClanHintsPayload> getId() {
        return ID;
    }
}
