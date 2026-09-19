package cn.woshiikun_1145.mcmod.choco.cstmm.network.payload;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * 队列状态快照（S2C）：own（自己队列状态）+ clan（自己战队匹配状态）+ maps（每张地图队列状态）。
 * 由客户端"队列"标签页每秒请求一次（request_queue_status，服务端限频 2 次/秒/玩家）。
 */
public record QueueStatusPayload(
        String json
) implements CustomPayload {

    public static final Id<QueueStatusPayload> ID = new Id<>(
            Identifier.of("cstmm", "queue_status")
    );

    public static final PacketCodec<PacketByteBuf, QueueStatusPayload> CODEC = PacketCodec.of(
            (payload, buf) -> buf.writeString(payload.json(), 65536),
            buf -> new QueueStatusPayload(buf.readString(65536))
    );

    @Override
    public Id<QueueStatusPayload> getId() {
        return ID;
    }
}
