package cn.woshiikun_1145.mcmod.choco.cstmm.network.payload;

import cn.woshiikun_1145.mcmod.choco.cstmm.network.NetworkHandler;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * 【作用】对局 HUD 快照（地图名/红蓝击杀数/剩余秒数/是否在局内），驱动客户端顶部 HUD 显示；
 * mapName 为空且 inGame=false 表示清空 HUD（对局结束或玩家退出）。
 * 【被谁使用】S2C（服务端→客户端）。服务端 MatchManager#broadcastHudData 每秒为对局内玩家构造，
 * 经 NetworkHandler#sendHudData（快照去重后）发送；客户端 ClientNetworkHandler 注册接收器
 * → HudOverlay#updateData 更新显示。
 */
public record HudDataPayload(
        String mapName,
        int redKills,
        int blueKills,
        int remainingSeconds,
        boolean inGame
) implements CustomPayload {

    // 【作用】包类型标识（cstmm:hud_data），由 NetworkHandler 注册并由网络层路由
    public static final Id<HudDataPayload> ID = new Id<>(
            Identifier.of("cstmm", "hud_data")
    );

    public static final PacketCodec<PacketByteBuf, HudDataPayload> CODEC = PacketCodec.of(
            (payload, buf) -> {
                // mapName 上限 64 UTF-8 字节：writeString 校验的是编码后字节数，
                // encode 时按字节截断（不切断多字节字符）确保永不抛异常
                buf.writeString(NetworkHandler.truncateByUtf8Bytes(payload.mapName(), 64), 64);
                buf.writeInt(payload.redKills());
                buf.writeInt(payload.blueKills());
                buf.writeInt(payload.remainingSeconds());
                buf.writeBoolean(payload.inGame());
            },
            buf -> new HudDataPayload(
                    buf.readString(64),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readBoolean()
            )
    );

    @Override
    public Id<HudDataPayload> getId() {
        return ID;
    }
}