package cn.woshiikun_1145.mcmod.choco.cstmm.network.payload;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import cn.woshiikun_1145.mcmod.choco.cstmm.network.NetworkHandler;

/**
 * 【作用】MVP 音乐通知包（S2C）：告诉对局内全体玩家本场/本回合的 MVP 是谁、其选择的音乐名，
 *         以及客户端应延迟多久开始播放（爆破模式需避开胜利音效/语音播报）。
 *         音乐文件的 URL 与哈希由客户端按全局配置的 mvpMusicUrl 自行访问 index_file.json 获取，
 *         本包不携带任何资源直链，避免服务端额外流量与 URL 变更时的同步问题。
 * 【被谁使用】服务端 MvpMusicManager 在回合结束/对局结算时广播；
 *             客户端 ClientNetworkHandler 接收后交给 MvpMusicClientHandler 播放与提示。
 */
public record MvpMusicPayload(
        String mvpName,
        String musicName,
        int delayMs,
        // MVP 提示栏（HUD 横幅）显示时长毫秒：服务端按所配对的胜利横幅时长下发，
        // 保证 MVP 栏与获胜信息栏一起出现、一起消失（2026-11-12）；<=0 时客户端回退默认时长
        int durationMs,
        boolean bombMode
) implements CustomPayload {

    // 【作用】包类型标识（cstmm:mvp_music），由 NetworkHandler/ClientNetworkHandler 注册
    public static final Id<MvpMusicPayload> ID = new Id<>(
            Identifier.of("cstmm", "mvp_music")
    );

    // 【作用】编解码器：MVP 名（≤64 字符）+ 音乐名（≤256 字符）+ 延迟毫秒 + 提示栏时长毫秒 + 是否爆破模式
    public static final PacketCodec<PacketByteBuf, MvpMusicPayload> CODEC = PacketCodec.of(
            (payload, buf) -> {
                buf.writeString(NetworkHandler.truncateByUtf8Bytes(payload.mvpName(), 64), 64);
                buf.writeString(NetworkHandler.truncateByUtf8Bytes(payload.musicName(), 256), 256);
                buf.writeVarInt(Math.max(0, payload.delayMs()));
                buf.writeVarInt(Math.max(0, payload.durationMs()));
                buf.writeBoolean(payload.bombMode());
            },
            buf -> new MvpMusicPayload(buf.readString(64), buf.readString(256),
                    buf.readVarInt(), buf.readVarInt(), buf.readBoolean())
    );

    @Override
    public Id<MvpMusicPayload> getId() {
        return ID;
    }
}
