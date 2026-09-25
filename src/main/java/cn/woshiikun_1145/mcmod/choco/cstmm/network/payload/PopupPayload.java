package cn.woshiikun_1145.mcmod.choco.cstmm.network.payload;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * 服务端弹窗通知（S2C）：替代部分聊天栏提示（如"配置已保存！"/战队加入结果），
 * 客户端在当前界面内弹出对话框；无界面时回退聊天栏。
 *
 * 【被谁使用】S2C（服务端→客户端）。服务端 NetworkHandler#sendPopup 在战队创建/加入/退出成功、
 * 配置保存成功等时机发送；客户端 ClientNetworkHandler 注册接收器——匹配菜单/配置界面内嵌弹窗，
 * 其他场景用 PopupScreen 全局弹窗界面承载。
 */
public record PopupPayload(
        String message
) implements CustomPayload {

    // 【作用】包类型标识（cstmm:popup），由 NetworkHandler 注册并由网络层路由
    public static final Id<PopupPayload> ID = new Id<>(
            Identifier.of("cstmm", "popup")
    );

    // 【作用】编解码器：弹窗消息（上限 256 字符）与 PacketByteBuf 互转
    public static final PacketCodec<PacketByteBuf, PopupPayload> CODEC = PacketCodec.of(
            (payload, buf) -> buf.writeString(payload.message(), 256),
            buf -> new PopupPayload(buf.readString(256))
    );

    @Override
    public Id<PopupPayload> getId() {
        return ID;
    }
}
