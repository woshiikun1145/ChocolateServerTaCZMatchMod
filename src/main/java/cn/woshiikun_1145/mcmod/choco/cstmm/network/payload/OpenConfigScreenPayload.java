package cn.woshiikun_1145.mcmod.choco.cstmm.network.payload;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * 服务端 → 客户端：通知客户端打开配置界面
 */
public record OpenConfigScreenPayload() implements CustomPayload {

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