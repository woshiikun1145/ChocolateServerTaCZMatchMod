package cn.woshiikun_1145.mcmod.choco.cstmm.network.payload;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

/**
 * 【作用】燃烧类手雷事件广播包（S2C，与 CounterStrikeGrenades 的 FireGrenadeMessage 同构：
 *         仅 type + position）：GROUND_EXPLODED = 落地起火（播放起火音；火焰方块由实体
 *         SynchedEntityData 同步，不走本包）；AIR_EXPLODED = 空中自爆（播放爆燃音 +
 *         客户端在爆心生成 500 个火焰粒子）；EXTINGUISHED_BY_SMOKE = 被烟雾熄灭（播放噗声）。
 * 【被谁使用】AbstractFireGrenadeEntity（服务端三种事件发送方）；
 *           客户端 client/grenade/GrenadeClientPayloads（接收方）。
 */
public record FireGrenadePayload(int type, Vec3d position) implements CustomPayload {

    public static final CustomPayload.Id<FireGrenadePayload> ID =
            new CustomPayload.Id<>(Identifier.of("cstmm", "fire_grenade"));

    /** 事件类型 ordinal：0=落地起火 1=空中自爆 2=被烟雾熄灭（与原 mod MessageType 顺序对应） */
    public static final int TYPE_GROUND_EXPLODED = 0;
    public static final int TYPE_AIR_EXPLODED = 1;
    public static final int TYPE_EXTINGUISHED_BY_SMOKE = 2;

    // 【作用】编解码器：type(varInt) + position(3×double)
    public static final PacketCodec<PacketByteBuf, FireGrenadePayload> CODEC = PacketCodec.of(
            FireGrenadePayload::encode,
            FireGrenadePayload::decode);

    private static void encode(FireGrenadePayload payload, PacketByteBuf buf) {
        buf.writeVarInt(payload.type);
        buf.writeDouble(payload.position.x);
        buf.writeDouble(payload.position.y);
        buf.writeDouble(payload.position.z);
    }

    private static FireGrenadePayload decode(PacketByteBuf buf) {
        int type = buf.readVarInt();
        Vec3d pos = new Vec3d(buf.readDouble(), buf.readDouble(), buf.readDouble());
        return new FireGrenadePayload(type, pos);
    }

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
