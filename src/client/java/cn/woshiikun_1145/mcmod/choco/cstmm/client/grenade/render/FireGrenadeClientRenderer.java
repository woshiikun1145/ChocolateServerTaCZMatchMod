package cn.woshiikun_1145.mcmod.choco.cstmm.client.grenade.render;

import cn.woshiikun_1145.mcmod.choco.cstmm.entity.AbstractFireGrenadeEntity;
import cn.woshiikun_1145.mcmod.choco.cstmm.entity.IncendiaryEntity;
import cn.woshiikun_1145.mcmod.choco.cstmm.entity.SmokeGrenadeEntity;
import cn.woshiikun_1145.mcmod.choco.cstmm.util.GrenadeSettings;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;

/**
 * 【作用】燃烧类手雷的逐 tick 火焰粒子渲染（移植自 CounterStrikeGrenades 的
 *         FireGrenadeEffectRenderer）：按 spreadBlocks 随机取方块顶面生成粒子
 *         （20% 烟雾 / 燃烧弹用魂火或 50% 概率 / 其余小火焰；莫洛托夫用常规火焰）；
 *         粒子位于烟雾覆盖下时跳过（被烟雾压制的火焰不显示）。
 *         与原 mod 的差异：yarn 的 ParticleManager.addParticle 不返回粒子实例，
 *         无法覆盖 lifetime——统一使用原版默认寿命（视觉近似）。
 * 【被谁使用】GrenadeClientHooksImpl.renderFireGrenade（由 AbstractFireGrenadeEntity
 *           客户端 tick 分支经 GrenadeHooks 驱动）。
 */
@Environment(EnvType.CLIENT)
public final class FireGrenadeClientRenderer {

    private FireGrenadeClientRenderer() {
    }

    private static final Random RANDOM = Random.create();

    // 【作用】逐 tick 渲染：粒子数量 = FIRE_RANGE²×密度 × spreadBlocks 数 / FIRE_RANGE²（原 mod 公式）
    public static void renderOne(AbstractFireGrenadeEntity grenade) {
        var spreadBlocks = grenade.getSpreadBlocks();
        if (spreadBlocks.isEmpty()) {
            return;
        }
        ClientWorld world = (ClientWorld) grenade.getWorld();
        int fireRange = GrenadeSettings.FIRE_RANGE;
        int particlePerTick = (fireRange * fireRange * GrenadeSettings.INCENDIARY_PARTICLE_DENSITY)
                * spreadBlocks.size() / (fireRange * fireRange);

        Vec3d grenadePos = grenade.getPos();
        boolean soulFire = grenade instanceof IncendiaryEntity;

        for (int i = 0; i < particlePerTick; i++) {
            // 【作用】随机取一个火焰方块，取其顶面随机点
            BlockPos block = spreadBlocks.get(RANDOM.nextInt(spreadBlocks.size()));
            Vec3d pos = new Vec3d(
                    block.getX() + RANDOM.nextDouble(),
                    block.getY() + 1.0,
                    block.getZ() + RANDOM.nextDouble());
            // 【作用】烟雾覆盖下不生成火焰粒子
            if (isPositionInSmoke(world, pos.add(0.0, 0.5, 0.0))) {
                continue;
            }

            var particleManager = MinecraftClient.getInstance().particleManager;
            double roll = RANDOM.nextDouble();
            var particleType = roll < 0.2
                    ? ParticleTypes.SMOKE
                    : roll < 0.5
                    ? (soulFire ? ParticleTypes.SOUL_FIRE_FLAME : ParticleTypes.FLAME)
                    : ParticleTypes.SMALL_FLAME;
            particleManager.addParticle(particleType, pos.x, pos.y, pos.z, 0.0, 0.1, 0.0);
        }
    }

    // 【作用】判断某位置是否处于烟雾覆盖下（遍历客户端烟雾实体的扩散方块缓存）
    private static boolean isPositionInSmoke(ClientWorld world, Vec3d pos) {
        BlockPos blockPos = BlockPos.ofFloored(pos);
        double smokeRadius = GrenadeSettings.SMOKE_RADIUS;
        double smokeFallingHeight = GrenadeSettings.SMOKE_MAX_FALLING_HEIGHT;
        for (SmokeGrenadeEntity smoke : world.getEntitiesByClass(SmokeGrenadeEntity.class,
                new Box(blockPos).expand(smokeRadius * 2.0,
                        smokeFallingHeight + smokeRadius * 2.0,
                        smokeRadius * 2.0),
                e -> true)) {
            if (smoke.getSpreadBlocks().contains(blockPos)) {
                return true;
            }
        }
        return false;
    }
}
