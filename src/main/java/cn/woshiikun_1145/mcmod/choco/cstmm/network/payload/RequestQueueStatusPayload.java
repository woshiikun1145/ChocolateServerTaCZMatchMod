package cn.woshiikun_1145.mcmod.choco.cstmm.network.payload;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * 队列状态订阅（C2S，空数据载荷）。
 * subscribe=true：加入订阅集并立即回发一次快照；false：退出订阅集。
 * 之后队列发生变化时服务端主动向订阅者推送 queue_status——不再需要客户端每秒轮询。
 */
public record RequestQueueStatusPayload(
        boolean subscribe
) implements CustomPayload {

    public static final Id<RequestQueueStatusPayload> ID = new Id<>(
            Identifier.of("cstmm", "request_queue_status")
    );

    public static final PacketCodec<PacketByteBuf, RequestQueueStatusPayload> CODEC = PacketCodec.of(
            (payload, buf) -> buf.writeBoolean(payload.subscribe()),
            buf -> new RequestQueueStatusPayload(buf.readBoolean())
    );

    @Override
    public Id<RequestQueueStatusPayload> getId() {
        return ID;
    }
}
