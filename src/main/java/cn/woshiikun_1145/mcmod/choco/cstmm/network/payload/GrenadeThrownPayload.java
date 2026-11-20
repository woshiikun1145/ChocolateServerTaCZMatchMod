package cn.woshiikun_1145.mcmod.choco.cstmm.network.payload;

import cn.woshiikun_1145.mcmod.choco.cstmm.entity.GrenadeType;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

/**
 * 【作用】手雷投掷请求包（C2S，移植自 CounterStrikeGrenades 的 GrenadeThrownMessage）：
 *         客户端 ThrowActionHandler 在鼠标按键松开时发起（左=强投/右=弱投/双键=中投，
 *         速度为平滑过渡后的瞬时值）；服务端校验手持物品后生成对应手雷实体并扣减数量。
 *         阶段 3 新增 customSound：诱饵弹自定义音效 ID（客户端从物品 "DecoySound"
 *         自定义数据组件读取，其余手雷恒为 null）。
 * 【被谁使用】客户端 client/grenade/input/ThrowActionHandler（发送方）；
 *           服务端 NetworkHandler（接收方，转主线程处理）。
 */
public record GrenadeThrownPayload(double speed, GrenadeType type, Vec3d position,
                                    float pitch, float yaw, String customSound) implements CustomPayload {

    public static final CustomPayload.Id<GrenadeThrownPayload> ID =
            new CustomPayload.Id<>(Identifier.of("cstmm", "grenade_thrown"));

    // 【作用】编解码器：speed(double) + type(varInt ordinal) + position(3×double) + pitch/yaw(float)
    //         + customSound（boolean 有无 + 可选字符串）
    public static final PacketCodec<PacketByteBuf, GrenadeThrownPayload> CODEC = PacketCodec.of(
            GrenadeThrownPayload::encode,
            GrenadeThrownPayload::decode);

    private static void encode(GrenadeThrownPayload payload, PacketByteBuf buf) {
        buf.writeDouble(payload.speed);
        buf.writeVarInt(payload.type.ordinal());
        buf.writeDouble(payload.position.x);
        buf.writeDouble(payload.position.y);
        buf.writeDouble(payload.position.z);
        buf.writeFloat(payload.pitch);
        buf.writeFloat(payload.yaw);
        buf.writeBoolean(payload.customSound != null);
        if (payload.customSound != null) {
            buf.writeString(payload.customSound);
        }
    }

    private static GrenadeThrownPayload decode(PacketByteBuf buf) {
        double speed = buf.readDouble();
        GrenadeType type = GrenadeType.values()[Math.max(0, buf.readVarInt())];
        Vec3d pos = new Vec3d(buf.readDouble(), buf.readDouble(), buf.readDouble());
        float pitch = buf.readFloat();
        float yaw = buf.readFloat();
        String customSound = buf.readBoolean() ? buf.readString() : null;
        return new GrenadeThrownPayload(speed, type, pos, pitch, yaw, customSound);
    }

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
