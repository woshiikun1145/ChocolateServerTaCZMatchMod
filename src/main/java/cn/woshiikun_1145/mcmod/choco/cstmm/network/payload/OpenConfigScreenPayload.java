package cn.woshiikun_1145.mcmod.choco.cstmm.network.payload;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * 服务端 → 客户端：通知客户端打开配置界面
 *
 * 【被谁使用】S2C（服务端→客户端）。服务端 ModCommands 的 /cstmm config 子命令（权限等级 2）
 * 与 NetworkHandler#sendOpenConfigScreen 发送；客户端 ClientNetworkHandler 注册接收器
 * → 当前不在 ConfigScreen 时打开之。
 */
public record OpenConfigScreenPayload() implements CustomPayload {

    // 【作用】包类型标识（cstmm:open_config_screen），由 NetworkHandler 注册并由网络层路由
    public static final Id<OpenConfigScreenPayload> ID = new Id<>(
            Identifier.of("cstmm", "open_config_screen")
    );

    public static final PacketCodec<PacketByteBuf, OpenConfigScreenPayload> CODEC = PacketCodec.of(
            (payload, buf) -> {
                // 无数据
            },
            buf -> new OpenConfigScreenPayload()
    );

    @Override
    public Id<OpenConfigScreenPayload> getId() {
        return ID;
    }
}