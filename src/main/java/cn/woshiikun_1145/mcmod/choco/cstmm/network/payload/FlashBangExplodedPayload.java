package cn.woshiikun_1145.mcmod.choco.cstmm.network.payload;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 【作用】闪光弹爆闪广播包（S2C，移植自 CounterStrikeGrenades 的 FlashBangExplodedMessage）：
 *         服务端爆闪时对全服广播（含爆心位置 + 每个受影响玩家的致盲数据）；
 *         客户端对本地玩家触发白屏 overlay，对其他玩家触发白闪粒子效果，
 *         并按距离播放爆炸音（近/远两种）。
 * 【被谁使用】FlashBangEntity（服务端发送方）；客户端 client/grenade/GrenadeClientPayloads（接收方）。
 */
public record FlashBangExplodedPayload(Vec3d position, List<Affected> affected) implements CustomPayload {

    public static final CustomPayload.Id<FlashBangExplodedPayload> ID =
            new CustomPayload.Id<>(Identifier.of("cstmm", "flashbang_exploded"));

    /**
     * 【作用】单个受影响玩家条目：uuid + 致盲数据。
     * 【被谁使用】本 payload 编解码；客户端判断是否本地玩家。
     */
    public record Affected(UUID uuid, FlashbangEffectData effect) {
    }

    // 【作用】编解码器：position(3×double) + affected(varInt count → uuid + attack/sustain/decay/amount 各 varInt)
    public static final PacketCodec<PacketByteBuf, FlashBangExplodedPayload> CODEC = PacketCodec.of(
            FlashBangExplodedPayload::encode,
            FlashBangExplodedPayload::decode);

    private static void encode(FlashBangExplodedPayload payload, PacketByteBuf buf) {
        buf.writeDouble(payload.position.x);
        buf.writeDouble(payload.position.y);
        buf.writeDouble(payload.position.z);
        buf.writeVarInt(payload.affected.size());
        for (Affected a : payload.affected) {
            buf.writeUuid(a.uuid());
            buf.writeVarInt(a.effect().attack());
            buf.writeVarInt(a.effect().sustain());
            buf.writeVarInt(a.effect().decay());
            buf.writeVarInt(a.effect().amount());
        }
    }

    private static FlashBangExplodedPayload decode(PacketByteBuf buf) {
        Vec3d pos = new Vec3d(buf.readDouble(), buf.readDouble(), buf.readDouble());
        int count = buf.readVarInt();
        List<Affected> affected = new ArrayList<>(Math.min(count, 128));
        for (int i = 0; i < count; i++) {
            UUID uuid = buf.readUuid();
            affected.add(new Affected(uuid,
                    new FlashbangEffectData(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt())));
        }
        return new FlashBangExplodedPayload(pos, affected);
    }

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
