package cn.woshiikun_1145.mcmod.choco.cstmm.entity;

import net.minecraft.entity.data.TrackedDataHandler;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * 【作用】List&lt;BlockPos&gt; 的实体同步数据序列化器（移植自 CounterStrikeGrenades 的
 *         BlockPosListEntityDataSerializer）：原版用 JSON 字符串承载，移植版按
 *         varInt 数量 + BlockPos.asLong 定长编码，语义等价且更紧凑。
 *         用于把燃烧类手雷的火焰占据方块放进 SynchedEntityData（与原版
 *         spreadBlocksAccessor 同机制）——客户端随实体生成包/同步包天然拿到
 *         火焰方块，不依赖额外的网络包时序。
 * 【被谁使用】AbstractFireGrenadeEntity.SPREAD_BLOCKS（DataTracker.registerData）；
 *           ModEntities.initialize（服务端与客户端初始化时各注册一次，注册顺序
 *           两侧一致保证 wire id 一致）。
 */
public final class BlockPosListDataHandler implements TrackedDataHandler<List<BlockPos>> {

    public static final BlockPosListDataHandler INSTANCE = new BlockPosListDataHandler();

    private static final PacketCodec<RegistryByteBuf, List<BlockPos>> CODEC = PacketCodec.of(
            BlockPosListDataHandler::encode,
            BlockPosListDataHandler::decode);

    private BlockPosListDataHandler() {
    }

    // 【作用】把序列化器注册进全局表（两侧模组初始化各调用一次，先于任何实体创建）
    public static void register() {
        TrackedDataHandlerRegistry.register(INSTANCE);
    }

    private static void encode(List<BlockPos> value, RegistryByteBuf buf) {
        buf.writeVarInt(value.size());
        for (BlockPos pos : value) {
            buf.writeLong(pos.asLong());
        }
    }

    private static List<BlockPos> decode(RegistryByteBuf buf) {
        int count = buf.readVarInt();
        List<BlockPos> list = new ArrayList<>(Math.min(count, 4096));
        for (int i = 0; i < count; i++) {
            list.add(BlockPos.fromLong(buf.readLong()));
        }
        return List.copyOf(list);
    }

    @Override
    public PacketCodec<? super RegistryByteBuf, List<BlockPos>> codec() {
        return CODEC;
    }

    // 【作用】返回原引用即可：列表经 List.copyOf 不可变（与原版 copy=pValue 一致）
    @Override
    public List<BlockPos> copy(List<BlockPos> value) {
        return value;
    }
}
