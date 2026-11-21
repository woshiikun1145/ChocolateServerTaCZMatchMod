package cn.woshiikun_1145.mcmod.choco.cstmm.util;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;

/**
 * 【作用】手雷系统数学工具（移植自 CounterStrikeGrenades 的 Utils.kt，仅保留 phase 1
 *         实际用到的函数）。全部为纯函数，无副作用。
 * 【被谁使用】CounterStrikeGrenadeEntity（弹跳方向吸附）、HEGrenadeEntity（伤害计算）、
 *           SmokeGrenadeEntity（发烟随机游走）、客户端 ThrowActionHandler（速度插值）。
 */
public final class GrenadeMath {

    private GrenadeMath() {
    }

    /**
     * 【作用】把速度向量吸附到最接近的坐标轴方向（用于手雷击中实体后的反弹方向判定）。
     *         注意：与原 mod 一致，X/Z 轴的映射按 1.20.1 原逻辑保留（NORTH/SOUTH 与
     *         EAST/WEST 的对应关系未"修正"，不改语义）。
     * 【被谁使用】CounterStrikeGrenadeEntity.onEntityHit。
     */
    public static Direction snapToAxis(Vec3d vec) {
        double absX = Math.abs(vec.x);
        double absY = Math.abs(vec.y);
        double absZ = Math.abs(vec.z);
        if (absX >= absY && absX >= absZ) {
            return vec.x > 0 ? Direction.NORTH : Direction.SOUTH;
        }
        if (absY >= absX && absY >= absZ) {
            return vec.y > 0 ? Direction.UP : Direction.DOWN;
        }
        return vec.z > 0 ? Direction.EAST : Direction.WEST;
    }

    /** 线性插值：from + (to - from) * t */
    public static double linearInterpolate(double from, double to, double t) {
        return from + (to - from) * t;
    }

    /** 毫秒 → tick（50ms 一 tick） */
    public static double millisToTicks(double millis) {
        return millis / 50.0;
    }

    /** 毫秒 → tick（整数口径） */
    public static long millisToTicks(long millis) {
        return millis / 50;
    }

    /** tick 数 → 秒 */
    public static double ticksToSeconds(double ticks) {
        return ticks / 20.0;
    }

    /**
     * 【作用】在以 center 为球心、radius 为半径的球体内均匀随机取一点（拒绝采样）。
     * 【被谁使用】阶段 2 燃烧弹火焰方块随机化。
     */
    public static Vec3d getRandomLocationFromSphere(Vec3d center, double radius) {
        Random random = Random.create();
        while (true) {
            Vec3d delta = new Vec3d(
                    random.nextDouble() * radius * 2 - radius,
                    random.nextDouble() * radius * 2 - radius,
                    random.nextDouble() * radius * 2 - radius);
            if (delta.length() < radius) {
                return center.add(delta);
            }
        }
    }

    /**
     * 【作用】方块顶面随机取点（火焰/烟雾表面随机点，阶段 2 用）。
     * 【被谁使用】阶段 2 燃烧弹火焰效果。
     */
    public static Vec3d getRandomLocationFromBlockSurface(BlockPos position) {
        Random random = Random.create();
        double x = random.nextDouble();
        double z = random.nextDouble();
        return new Vec3d(position.getX() + x, position.getY() + 1.0, position.getZ() + z);
    }

    /**
     * 【作用】水平距离（忽略 Y 轴）。
     * 【被谁使用】烟雾遮挡相关判定。
     */
    public static double horizontalDistanceTo(Vec3d a, Vec3d b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
