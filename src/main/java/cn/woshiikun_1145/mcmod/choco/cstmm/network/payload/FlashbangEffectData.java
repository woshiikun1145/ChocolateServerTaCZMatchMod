package cn.woshiikun_1145.mcmod.choco.cstmm.network.payload;

import cn.woshiikun_1145.mcmod.choco.cstmm.api.CSGrenadesAPI;
import cn.woshiikun_1145.mcmod.choco.cstmm.util.GrenadeSettings;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

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
