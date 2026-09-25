package cn.woshiikun_1145.mcmod.choco.cstmm.network.payload;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * 队列状态快照（S2C）：own（自己队列状态）+ clan（自己战队匹配状态）+ maps（每张地图队列状态）。
 * 由客户端"队列"标签页每秒请求一次（request_queue_status，服务端限频 2 次/秒/玩家）。
 *
 * 【被谁使用】S2C（服务端→客户端）。服务端 NetworkHandler 处理 RequestQueueStatusPayload 订阅时
 * 立即回发，并由 pushQueueStatusToSubscribers 在队列变化时推送给订阅者（QueueManager 触发）；
 * 客户端 ClientNetworkHandler 注册接收器 → QueueStatusCache#update 供"队列"页渲染。
 */
public record QueueStatusPayload(
        String json
) implements CustomPayload {

    // 【作用】包类型标识（cstmm:queue_status），由 NetworkHandler 注册并由网络层路由
    public static final Id<QueueStatusPayload> ID = new Id<>(
            Identifier.of("cstmm", "queue_status")
    );

    // 【作用】编解码器：队列状态 JSON（上限 65536 字节）与 PacketByteBuf 互转
    public static final PacketCodec<PacketByteBuf, QueueStatusPayload> CODEC = PacketCodec.of(
            (payload, buf) -> buf.writeString(payload.json(), 65536),
            buf -> new QueueStatusPayload(buf.readString(65536))
    );

    @Override
    public Id<QueueStatusPayload> getId() {
        return ID;
    }
}
