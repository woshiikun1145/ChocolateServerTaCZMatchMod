package cn.woshiikun_1145.mcmod.choco.cstmm.network.payload;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record RequestConfigSyncPayload() implements CustomPayload {
    public static final Id<RequestConfigSyncPayload> ID = new Id<>(
            Identifier.of("cstmm", "request_config_sync")
    );

    public static final PacketCodec<PacketByteBuf, RequestConfigSyncPayload> CODEC = PacketCodec.of(
            (payload, buf) -> {},
            buf -> new RequestConfigSyncPayload()
    );

    @Override
    public Id<RequestConfigSyncPayload> getId() {
        return ID;
    }
}