package cn.woshiikun_1145.mcmod.choco.cstmm.network.payload;

import cn.woshiikun_1145.mcmod.choco.cstmm.network.NetworkHandler;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * 【作用】对局 HUD 快照（地图名/红蓝击杀数/剩余秒数/阶段/花名册 JSON），驱动客户端顶部
 *         CS2 风格队伍栏显示；mapName 为空且 inGame=false 表示清空 HUD（对局结束或玩家退出）。
 *         rosterJson 为本局玩家花名册（紧凑 JSON 数组，元素字段见 MatchManager#buildRosterJson）：
 *         uuid/名字/队伍/个人击杀/死亡/上一条命击杀/战队缩写/徽标引用/头像绑定，
 *         供客户端渲染左右两队玩家卡片（血量与存活状态由客户端本地读取，不在包内）。
 * 【被谁使用】S2C（服务端→客户端）。服务端 MatchManager#broadcastHudData 每秒为对局内玩家构造，
 * 经 NetworkHandler#sendHudData（快照去重后）发送；客户端 ClientNetworkHandler 注册接收器
 * → HudOverlay#updateData 更新显示。
 */
public record HudDataPayload(
        String mapName,
        int redKills,
        int blueKills,
        int remainingSeconds,
        boolean inGame,
        int phase,
        String rosterJson
) implements CustomPayload {

    // 【作用】包类型标识（cstmm:hud_data），由 NetworkHandler 注册并由网络层路由
    public static final Id<HudDataPayload> ID = new Id<>(
            Identifier.of("cstmm", "hud_data")
    );

    // 花名册 JSON 字节上限（与 clan_data 一致；服务端构建时已按此截断，此处仅为协议兜底）
    private static final int MAX_ROSTER_BYTES = 65536;

    public static final PacketCodec<PacketByteBuf, HudDataPayload> CODEC = PacketCodec.of(
            (payload, buf) -> {
                // mapName 上限 64 UTF-8 字节：writeString 校验的是编码后字节数，
                // encode 时按字节截断（不切断多字节字符）确保永不抛异常
                buf.writeString(NetworkHandler.truncateByUtf8Bytes(payload.mapName(), 64), 64);
                buf.writeInt(payload.redKills());
                buf.writeInt(payload.blueKills());
                buf.writeInt(payload.remainingSeconds());
                buf.writeBoolean(payload.inGame());
                buf.writeVarInt(payload.phase());
                // 花名册 JSON：发送前按字节截断兜底（正常构建时已远小于上限）
                buf.writeString(NetworkHandler.truncateByUtf8Bytes(
                        payload.rosterJson() == null ? "[]" : payload.rosterJson(), MAX_ROSTER_BYTES), MAX_ROSTER_BYTES);
            },
            buf -> new HudDataPayload(
                    buf.readString(64),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readBoolean(),
                    buf.readVarInt(),
                    buf.readString(MAX_ROSTER_BYTES)
            )
    );

    @Override
    public Id<HudDataPayload> getId() {
        return ID;
    }
}
