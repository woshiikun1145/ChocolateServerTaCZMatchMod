package cn.woshiikun_1145.mcmod.choco.cstmm.network.payload;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * C2S 配置更新包（分包）。
 * 原版对 C2S payload 有 32768 字节硬限制，含 base64 背景图的配置 JSON 必须分包发送，
 * 由服务端按玩家重组。分包大小与 ConfigSyncPayload 一致（30000 字符）。
 *
 * 【被谁使用】C2S（客户端→服务端）。客户端 ClientNetworkHandler#sendConfigUpdate 在配置界面
 * 保存时按 UTF-8 字节边界分包发送（ClientPlayNetworking.send）；服务端 NetworkHandler 注册
 * 接收器 → handleConfigUpdatePart 按玩家重组并保存配置（成功后回发 PopupPayload 提示）。
 */
public record ConfigUpdatePayload(
        int partIndex,
        int totalParts,
        String data
) implements CustomPayload {

    // 【作用】包类型标识（cstmm:config_update），由 NetworkHandler 注册并由网络层路由
    public static final Id<ConfigUpdatePayload> ID = new Id<>(
            Identifier.of("cstmm", "config_update")
    );

    // 【作用】编解码器：分包序号/总数（VarInt）+ 分片数据（上限 32767 字节）与 PacketByteBuf 互转
    public static final PacketCodec<PacketByteBuf, ConfigUpdatePayload> CODEC = PacketCodec.of(
            (payload, buf) -> {
                buf.writeVarInt(payload.partIndex());
                buf.writeVarInt(payload.totalParts());
                buf.writeString(payload.data(), 32767);
            },
            buf -> new ConfigUpdatePayload(
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readString(32767)
            )
    );

    @Override
    public Id<ConfigUpdatePayload> getId() {
        return ID;
    }
}
