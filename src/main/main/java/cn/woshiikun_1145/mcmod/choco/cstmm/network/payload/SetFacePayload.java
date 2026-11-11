package cn.woshiikun_1145.mcmod.choco.cstmm.network.payload;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * 【作用】客户端设置/清除自己头像绑定的 C2S 包：avatarType 为平台（"qq"/"bili"），
 *         avatarId 为账号数字 ID（QQ号 / B站 UID），空 avatarType 表示清除头像。
 *         服务端只存绑定不存图片直链——头像图片由各客户端按绑定自行获取
 *         （QQ 拼接 qlogo 直链 / B站经 uapis 解析 face 字段）。
 * 【被谁使用】C2S（客户端→服务端）。客户端 PersonalizeTabPanel（保存/清除头像按钮）发送；
 *             服务端 NetworkHandler 注册接收器 → PlayerDataManager.setAvatarBinding（写入档案文件
 *             config/cstmm/data/players/<uuid>.json 的 avatarType/avatarId 字段，进服即建档）。
 */
public record SetFacePayload(
        String avatarType,
        String avatarId
) implements CustomPayload {

    // 【作用】包类型标识（cstmm:set_face），由 NetworkHandler 注册并由网络层路由
    public static final Id<SetFacePayload> ID = new Id<>(
            Identifier.of("cstmm", "set_face")
    );

    // 【作用】编解码器：平台（最长 16 字符，空串 = 清除）+ 账号 ID（最长 32 字符）
    public static final PacketCodec<PacketByteBuf, SetFacePayload> CODEC = PacketCodec.of(
            (payload, buf) -> {
                buf.writeString(payload.avatarType(), 16);
                buf.writeString(payload.avatarId(), 32);
            },
            buf -> new SetFacePayload(buf.readString(16), buf.readString(32))
    );

    @Override
    public Id<SetFacePayload> getId() {
        return ID;
    }
}
