package cn.woshiikun_1145.mcmod.choco.cstmm.network.payload;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * 【作用】诱饵弹枪声广播包（S2C，移植自 CounterStrikeGrenades 的 DecoyShotMessage）：
 *         服务端按生成的时间戳逐发广播，客户端定位实体后播放枪声
 *         （优先级：自定义音效 > TACZ 枪械音效 > 原版动物声 fallback）并生成枪口焰粒子。
 * 【被谁使用】DecoyGrenadeEntity.fireShot（发送方，广播给全服玩家）；
 *           客户端 GrenadeClientPayloads（接收方）。
 */
public record DecoyShotPayload(int entityId, String gunId, String customSound) implements CustomPayload {

    public static final CustomPayload.Id<DecoyShotPayload> ID =
            new CustomPayload.Id<>(Identifier.of("cstmm", "decoy_shot"));

    // 【作用】编解码器：entityId(varInt) + gunId(字符串) + customSound（boolean 有无 + 可选字符串）
    public static final PacketCodec<PacketByteBuf, DecoyShotPayload> CODEC = PacketCodec.of(
            DecoyShotPayload::encode, DecoyShotPayload::decode);

    private static void encode(DecoyShotPayload payload, PacketByteBuf buf) {
        buf.writeVarInt(payload.entityId);
        buf.writeString(payload.gunId);
        buf.writeBoolean(payload.customSound != null);
        if (payload.customSound != null) {
            buf.writeString(payload.customSound);
        }
    }

    private static DecoyShotPayload decode(PacketByteBuf buf) {
        int entityId = buf.readVarInt();
        String gunId = buf.readString();
        String customSound = buf.readBoolean() ? buf.readString() : null;
        return new DecoyShotPayload(entityId, gunId, customSound);
    }

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
