package cn.woshiikun_1145.mcmod.choco.cstmm.network.payload;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * 服务端弹窗通知（S2C）：替代部分聊天栏提示（如"配置已保存！"/战队加入结果），
 * 客户端在当前界面内弹出对话框；无界面时回退聊天栏。
 */
public record PopupPayload(
        String message
) implements CustomPayload {

    public static final Id<PopupPayload> ID = new Id<>(
            Identifier.of("cstmm", "popup")
    );

    public static final PacketCodec<PacketByteBuf, PopupPayload> CODEC = PacketCodec.of(
            (payload, buf) -> buf.writeString(payload.message(), 256),
            buf -> new PopupPayload(buf.readString(256))
    );

    @Override
    public Id<PopupPayload> getId() {
        return ID;
    }
}
