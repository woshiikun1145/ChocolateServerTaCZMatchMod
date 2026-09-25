package cn.woshiikun_1145.mcmod.choco.cstmm.network.payload;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * 队列状态订阅（C2S，空数据载荷）。
 * subscribe=true：加入订阅集并立即回发一次快照；false：退出订阅集。
 * 之后队列发生变化时服务端主动向订阅者推送 queue_status——不再需要客户端每秒轮询。
 *
 * 【被谁使用】C2S（客户端→服务端）。客户端 MatchMenuScreen 打开"队列"页时发 subscribe=true、
 * 关闭界面/取消匹配时发 subscribe=false（ClientPlayNetworking.send）；服务端 NetworkHandler
 * 注册接收器维护订阅集，订阅成功时立即回发一次 QueueStatusPayload 快照。
 */
public record RequestQueueStatusPayload(
        boolean subscribe
) implements CustomPayload {

    // 【作用】包类型标识（cstmm:request_queue_status），由 NetworkHandler 注册并由网络层路由
    public static final Id<RequestQueueStatusPayload> ID = new Id<>(
            Identifier.of("cstmm", "request_queue_status")
    );

    // 【作用】编解码器：订阅开关布尔值与 PacketByteBuf 互转
    public static final PacketCodec<PacketByteBuf, RequestQueueStatusPayload> CODEC = PacketCodec.of(
            (payload, buf) -> buf.writeBoolean(payload.subscribe()),
            buf -> new RequestQueueStatusPayload(buf.readBoolean())
    );

    @Override
    public Id<RequestQueueStatusPayload> getId() {
        return ID;
    }
}
