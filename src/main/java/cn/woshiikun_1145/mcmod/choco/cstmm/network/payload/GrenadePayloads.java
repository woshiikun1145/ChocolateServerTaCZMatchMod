package cn.woshiikun_1145.mcmod.choco.cstmm.network.payload;

import cn.woshiikun_1145.mcmod.choco.cstmm.api.CSGrenadesAPI;
import cn.woshiikun_1145.mcmod.choco.cstmm.entity.GrenadeType;
import cn.woshiikun_1145.mcmod.choco.cstmm.util.GrenadeSettings;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 【作用】手雷域 payload 容器：集中存放闪光弹爆闪、致盲数据、烟雾扩散、燃烧类手雷事件、
 *         诱饵弹枪声与手雷投掷请求相关包/数据。
 *         由原 FlashBangExplodedPayload.java、FlashbangEffectData.java、SmokeSpreadPayload.java、
 *         FireGrenadePayload.java、DecoyShotPayload.java、GrenadeThrownPayload.java 合并而来，
 *         各嵌套类型行为与网络字节格式与拆分前完全一致（ID/CODEC 未改动）。
 */
public final class GrenadePayloads {

    // 【作用】工具容器，禁止实例化
    private GrenadePayloads() {
    }

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

    /**
     * 【作用】单个玩家的闪光弹致盲效果数据（移植自 CounterStrikeGrenades 的 FlashbangEffectData）：
     *         attack = 白屏进入时长（ms，固定 20）、sustain = 全白持续（ms）、
     *         decay = 渐弱持续（ms）、amount = 峰值亮度（固定 255）。
     *         服务端按"视角夹角 → 四档时长 × 距离因子 × 遮挡因子"计算。
     * 【被谁使用】FlashBangEntity（爆闪时服务端计算每个玩家数据）、
     *           FlashBangExplodedPayload（下行载荷字段）、客户端 FlashbangBlindEffectRenderer
     *           与 FlashbangParticleEffectRenderer（消费数据）。
     */
    public record FlashbangEffectData(int attack, int sustain, int decay, int amount) {

        /** 完全致盲时长占总时长比例（原 mod 常量） */
        private static final double FULL_BLIND_RATIO = GrenadeSettings.FLASH_FULL_BLIND_RATIO;

        /**
         * 【作用】服务端计算某玩家被某颗闪光弹致盲的数据：
         *         夹角 0-53°/53-72°/72-101°/101-180° 四档时长（max 递减到 min），
         *         乘以距离因子（1 - (d/range)^exponent）与遮挡因子（被墙完全遮挡=0）；
         *         sustain=全白时长、decay=渐弱时长（毫秒）。
         * 【被谁使用】FlashBangEntity.calculateAffectedPlayers（服务端爆闪时）。
         */
        public static FlashbangEffectData create(World world, Vec3d flashbangPos, PlayerEntity player) {
            Vec3d playerToFlash = flashbangPos.subtract(player.getPos());
            double distance = playerToFlash.length();
            double angle = Math.toDegrees(Math.acos(MathHelper.clamp(
                    player.getRotationVector().normalize().dotProduct(playerToFlash.normalize()), -1.0, 1.0)));

            double distanceFactor = getDistanceFactor(distance);
            double blockingFactor = getBlockingFactor(world, flashbangPos, player);

            double maxDuration = GrenadeSettings.FLASH_MAX_DURATION_S;
            double minDuration = GrenadeSettings.FLASH_MIN_DURATION_S;

            // 【作用】四档总时长（按原 mod 比例：100% / 75% / 37.5% / min）与全白时长（按档位取全白比例或 10%）
            int tier = tierIndex(angle);
            double totalEffectTime = switch (tier) {
                case 0 -> maxDuration;
                case 1 -> maxDuration * 0.75;
                case 2 -> maxDuration * 0.375;
                default -> minDuration;
            };
            double fullBlindTime = switch (tier) {
                case 0 -> maxDuration * FULL_BLIND_RATIO;
                case 1 -> maxDuration * 0.75 * FULL_BLIND_RATIO;
                case 2 -> maxDuration * 0.375 * FULL_BLIND_RATIO;
                default -> minDuration * 0.1;
            };
            totalEffectTime = Math.max(0.0, totalEffectTime * distanceFactor * blockingFactor);
            fullBlindTime = Math.max(0.0, fullBlindTime * distanceFactor * blockingFactor);

            // 【作用】上报玩家致盲状态（原版 CSGrenadesAPI.setPlayerFlashed：本方法仅服务端调用，
            //         与原版 FlashBangExplodedMessage 中 if (!level.isClientSide) 守卫等价），
            //         供其他模组经 CSGrenadesAPI.isPlayerFlashed 查询
            int totalEffectTimeInTicks = (int) (totalEffectTime * 20);
            if (totalEffectTimeInTicks > 0) {
                CSGrenadesAPI.setPlayerFlashed(player, totalEffectTimeInTicks);
            }

            return new FlashbangEffectData(
                    20,
                    (int) (fullBlindTime * 1000),
                    (int) ((totalEffectTime - fullBlindTime) * 1000),
                    255);
        }

        // 【作用】按夹角返回档位索引（0..3）
        private static int tierIndex(double angle) {
            if (angle <= 53.0) return 0;
            if (angle <= 72.0) return 1;
            if (angle <= 101.0) return 2;
            return 3;
        }

        // 【作用】距离因子：(1 - (d/range)^exponent) 截断到 [0,1]
        private static double getDistanceFactor(double distance) {
            double range = GrenadeSettings.FLASH_EFFECTIVE_RANGE;
            double ratio = MathHelper.clamp(distance / range, 0.0, 1.0);
            return Math.max(1.0 - Math.pow(ratio, GrenadeSettings.FLASH_DISTANCE_DECAY_EXPONENT), 0.0);
        }

        // 【作用】遮挡因子：从玩家眼部到爆心做视觉射线，MISS（视线无阻挡）= 1，被挡 = 0
        private static double getBlockingFactor(World world, Vec3d flashbangPos, PlayerEntity player) {
            HitResult result = world.raycast(new RaycastContext(
                    player.getEyePos(), flashbangPos,
                    RaycastContext.ShapeType.VISUAL, RaycastContext.FluidHandling.NONE, player));
            return result.getType() == HitResult.Type.MISS ? 1.0 : 0.0;
        }
    }

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
}
