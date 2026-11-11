package cn.woshiikun_1145.mcmod.choco.cstmm.network.payload;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * 【作用】客户端设置/清除自己 MVP 音乐选择的 C2S 包：musicName 为 index_file.json 中的
 *         music_name（空串 = 清除选择）。服务端校验后写入玩家档案（mvpMusicName 字段）并同步档案。
 * 【被谁使用】客户端 PersonalizeTabPanel（选择/清除按钮）发送；
 *             服务端 NetworkHandler 注册接收器 → PlayerDataManager.setMvpMusic。
 */
public record SetMvpMusicPayload(
        String musicName
) implements CustomPayload {

    // 【作用】包类型标识（cstmm:set_mvp_music）
    public static final Id<SetMvpMusicPayload> ID = new Id<>(
            Identifier.of("cstmm", "set_mvp_music")
    );

    // 【作用】编解码器：音乐名（≤256 字符，空串 = 清除）
    public static final PacketCodec<PacketByteBuf, SetMvpMusicPayload> CODEC = PacketCodec.of(
            (payload, buf) -> buf.writeString(payload.musicName(), 256),
            buf -> new SetMvpMusicPayload(buf.readString(256))
    );

    @Override
    public Id<SetMvpMusicPayload> getId() {
        return ID;
    }
}
