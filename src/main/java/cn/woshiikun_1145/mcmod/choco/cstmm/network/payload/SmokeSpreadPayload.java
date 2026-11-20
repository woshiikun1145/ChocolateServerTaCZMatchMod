package cn.woshiikun_1145.mcmod.choco.cstmm.network.payload;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * 【作用】烟雾弹发烟方块广播包（S2C，替代原 mod 的 BlockPosList 自定义实体数据同步器）：
 *         服务端烟雾激活时计算扩散方块（含下沉/坍塌形状），一次性对全服广播；
 *         客户端按实体 ID 定位烟雾实体，缓存方块列表供粒子渲染与子弹/箭矢穿烟判定使用。
 * 【被谁使用】SmokeGrenadeEntity（服务端激活时发送方）；客户端 client/grenade/GrenadeClientPayloads
 *           （接收方，写回 SmokeGrenadeEntity 的客户端缓存）。
 */
public record SmokeSpreadPayload(int entityId, List<BlockPos> blocks) implements CustomPayload {

    public static final CustomPayload.Id<SmokeSpreadPayload> ID =
            new CustomPayload.Id<>(Identifier.of("cstmm", "smoke_spread"));

    // 【作用】编解码器：entityId(varInt) + blocks(varInt count → 每块 BlockPos.asLong 8 字节，紧凑高效)
    public static final PacketCodec<PacketByteBuf, SmokeSpreadPayload> CODEC = PacketCodec.of(
            SmokeSpreadPayload::encode,
            SmokeSpreadPayload::decode);

    private static void encode(SmokeSpreadPayload payload, PacketByteBuf buf) {
        buf.writeVarInt(payload.entityId);
        buf.writeVarInt(payload.blocks.size());
        for (BlockPos pos : payload.blocks) {
            buf.writeLong(pos.asLong());
        }
    }

    private static SmokeSpreadPayload decode(PacketByteBuf buf) {
        int entityId = buf.readVarInt();
        int count = buf.readVarInt();
        List<BlockPos> blocks = new ArrayList<>(Math.min(count, 4096));
        for (int i = 0; i < count; i++) {
            blocks.add(BlockPos.fromLong(buf.readLong()));
        }
        return new SmokeSpreadPayload(entityId, blocks);
    }

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
