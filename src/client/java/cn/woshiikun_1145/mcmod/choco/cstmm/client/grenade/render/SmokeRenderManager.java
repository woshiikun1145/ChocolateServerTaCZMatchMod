package cn.woshiikun_1145.mcmod.choco.cstmm.client.grenade.render;

import cn.woshiikun_1145.mcmod.choco.cstmm.entity.SmokeGrenadeEntity;
import cn.woshiikun_1145.mcmod.choco.cstmm.util.GrenadeMath;
import cn.woshiikun_1145.mcmod.choco.cstmm.util.GrenadeSettings;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;

import java.util.ArrayList;
import java.util.List;

/**
 * 【作用】烟雾弹客户端渲染管理器（移植自 CounterStrikeGrenades 的 SmokeRenderManager +
 *         SmokeGrenadeEntity 的客户端穿烟逻辑）：等待服务端广播的扩散方块（按距离由近到远
 *         逐步生成烟雾粒子，速率随距离递增）；每 tick 处理箭矢与 TACZ 子弹穿烟打散
 *         （扫掠盒 + 逐步插值防隧穿）；HE 爆炸炸开烟雾按位置直接清理粒子透明度。
 * 【被谁使用】GrenadeClientHooksImpl（onSmokeEmit 触发渲染 / clearSmokeWithinRange /
 *           disperseSmokeByProjectiles 转发）；CstmmClient.register（挂客户端 tick）。
 */
@Environment(EnvType.CLIENT)
public final class SmokeRenderManager {

    private SmokeRenderManager() {
    }

    /** 正在生成粒子的烟雾渲染器列表 */
    private static final List<SmokeRenderer> RENDERERS = new ArrayList<>();

    // 【作用】挂载客户端 tick（START 语义与原 mod 一致）：推进各渲染器、清理完成项、
    //         清理跨世界残留粒子；退出存档/换维加载中（client.world == null）时直接清空
    //         全部烟雾粒子与渲染器（渲染器引用旧世界烟雾实体，继续推进会向空世界投粒子）
    public static void register() {
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (client.world == null) {
                SmokeGrenadeParticle.clearAll();
                RENDERERS.clear();
                return;
            }
            SmokeGrenadeParticle.purgeOfOtherWorlds(client.world);
            for (SmokeRenderer renderer : RENDERERS) {
                renderer.update(client);
            }
            RENDERERS.removeIf(r -> r.done);
        });
    }

    /**
     * 【作用】为一颗已激活烟雾弹创建粒子生成器（等待 spreadBlocks 同步后开始按距离逐格放粒子）。
     * 【被谁使用】GrenadeClientHooksImpl.onSmokeEmit。
     */
    public static void beginRendering(SmokeGrenadeEntity smokeEntity, Vec3d center) {
        RENDERERS.add(new SmokeRenderer(smokeEntity, center));
    }

    /**
     * 【作用】清理指定位置与半径内的烟雾粒子透明度（恢复时间随距离线性增加）。
     *         HE 爆炸（range=HE 半径+2.5）、子弹（BULLET_CLEAR_RANGE）、箭矢（ARROW_CLEAR_RANGE）
     *         三处调用；debugHeader 参数仅为兼容原 mod 调试语义，移植版不再输出逐粒子日志。
     * 【被谁使用】GrenadeClientHooksImpl.clearSmokeWithinRange。
     */
    public static void clearSmokeWithinRange(Vec3d pos, double range) {
        double rangeSqr = range * range;
        for (SmokeGrenadeParticle particle : SmokeGrenadeParticle.ACTIVE) {
            double dx = particle.getXPos() - pos.x;
            double dy = particle.getYPos() - pos.y;
            double dz = particle.getZPos() - pos.z;
            double distSqr = dx * dx + dy * dy + dz * dz;
            if (distSqr < rangeSqr) {
                // 【作用】恢复 tick 数 = TIME_BEFORE_REGENERATE + REGENERATION×(1 - 距离/半径)（越近恢复越慢）
                double dist = Math.sqrt(distSqr);
                particle.opacityTime = (int) GrenadeMath.millisToTicks(GrenadeSettings.SMOKE_TIME_BEFORE_REGENERATE_MS)
                        + (int) GrenadeMath.linearInterpolate(
                        GrenadeMath.millisToTicks(GrenadeSettings.SMOKE_REGENERATION_TIME_MS), 0.0, dist / range);
            }
        }
    }

    // 【作用】每 tick 穿烟打散：箭矢（扫掠盒命中烟雾云 + 插值清理）与 TACZ 子弹（类名反射）
    public static void disperseSmokeByProjectiles(SmokeGrenadeEntity smokeEntity) {
        ClientWorld world = (ClientWorld) smokeEntity.getWorld();
        List<BlockPos> spreadBlocks = smokeEntity.getSpreadBlocks();
        if (spreadBlocks.isEmpty()) {
            return;
        }

        double smokeRadius = GrenadeSettings.SMOKE_RADIUS;
        double smokeFallingHeight = GrenadeSettings.SMOKE_MAX_FALLING_HEIGHT;
        Box smokeCloud = new Box(BlockPos.ofFloored(smokeEntity.getPos()))
                .expand(smokeRadius).expand(0.0, -smokeFallingHeight, 0.0);

        // ===== 原版箭矢（含弩箭）：扫掠盒 + 50cm 步长插值 =====
        double searchRange = 64.0;
        List<PersistentProjectileEntity> arrows = world.getEntitiesByClass(PersistentProjectileEntity.class,
                smokeEntity.getBoundingBox().expand(searchRange),
                arrow -> arrow.getVelocity().lengthSquared() > 0.01);
        for (PersistentProjectileEntity arrow : arrows) {
            Vec3d delta = arrow.getVelocity();
            Box swept = arrow.getBoundingBox().union(arrow.getBoundingBox().offset(delta.negate()));
            if (smokeCloud.intersects(swept)) {
                clearAlongSegment(arrow.getPos(), delta,
                        GrenadeSettings.SMOKE_ARROW_CLEAR_RANGE);
            }
        }

        // ===== TACZ 子弹：按实体类名字符串反射识别（不引入编译依赖，与原 mod 一致） =====
        for (Entity entity : world.getEntities()) {
            Vec3d delta = entity.getVelocity();
            if (delta.lengthSquared() == 0.0) {
                continue;
            }
            Box swept = entity.getBoundingBox().union(entity.getBoundingBox().offset(delta.negate()));
            if (!smokeCloud.intersects(swept)) {
                continue;
            }
            if (!entity.getClass().getName().equals("com.tacz.guns.entity.EntityKineticBullet")) {
                continue;
            }
            clearAlongSegment(entity.getPos(), delta, GrenadeSettings.SMOKE_BULLET_CLEAR_RANGE);
        }
    }

    // 【作用】沿"上一位置→当前位置"的弹道逐 0.5 格插值清理烟雾（防高速弹道隧穿；步数上限 30）
    private static void clearAlongSegment(Vec3d currentPos, Vec3d delta, double clearRange) {
        Vec3d oldPos = currentPos.subtract(delta);
        int steps = Math.min(Math.max((int) (delta.length() / 0.5), 1), 30);
        for (int i = 0; i <= steps; i++) {
            Vec3d interpolated = oldPos.lerp(currentPos, (double) i / steps);
            clearSmokeWithinRange(interpolated, clearRange);
        }
    }

    /**
     * 【作用】单颗烟雾弹的粒子生成器：等 spreadBlocks 同步 → 按距爆心距离排序 →
     *         每 tick 按距离递增速率放粒子（每格 5 个、围绕方块角点 ±0.5 偏移），放完即结束。
     * 【被谁使用】SmokeRenderManager.beginRendering。
     */
    private static final class SmokeRenderer {
        private final SmokeGrenadeEntity smokeEntity;
        private final Vec3d center;
        private boolean done = false;
        private int tickCount = 0;
        private final Random random = Random.create();

        private static final double BLOCK_PER_TICK_DISTANCE_RATIO = 0.5;
        private static final int PARTICLE_PER_BLOCK = 5;

        private List<BlockPos> renderBlockPos = null;

        SmokeRenderer(SmokeGrenadeEntity smokeEntity, Vec3d center) {
            this.smokeEntity = smokeEntity;
            this.center = center;
        }

        // 【作用】逐 tick 推进：超烟雾寿命强制结束；等广播数据；按"最远方块距离×比例"控制每格生成速率
        void update(MinecraftClient client) {
            tickCount++;
            if (tickCount > GrenadeMath.millisToTicks(GrenadeSettings.SMOKE_LIFETIME_MS)) {
                this.done = true;
                return;
            }
            if (this.renderBlockPos == null) {
                List<BlockPos> blocks = this.smokeEntity.getSpreadBlocks();
                if (blocks.isEmpty()) {
                    return; // 等待服务端广播
                }
                BlockPos centerPos = BlockPos.ofFloored(this.center);
                this.renderBlockPos = new ArrayList<>(blocks);
                this.renderBlockPos.sort((a, b) -> Double.compare(
                        a.getSquaredDistance(centerPos), b.getSquaredDistance(centerPos)));
            }
            if (this.renderBlockPos.isEmpty()) {
                this.done = true;
                return;
            }
            int blockPerTick = (int) (this.renderBlockPos.get(0).getSquaredDistance(
                    BlockPos.ofFloored(this.center)) * (1 + BLOCK_PER_TICK_DISTANCE_RATIO)) + 1;
            for (int i = 0; i < blockPerTick; i++) {
                if (this.renderBlockPos.isEmpty()) {
                    this.done = true;
                    return;
                }
                // 【作用】原版以方块角点 (x, y, z) 为基准加 Vec3.offsetRandom(random, 1f)：
                //         每轴 ±0.5 的均匀偏移（围绕角点的 1×1×1 立方体）；先放完 5 粒再移出列表
                Vec3d pos = new Vec3d(
                        this.renderBlockPos.get(0).getX(),
                        this.renderBlockPos.get(0).getY(),
                        this.renderBlockPos.get(0).getZ());
                for (int p = 0; p < PARTICLE_PER_BLOCK; p++) {
                    Vec3d shuffled = pos.add(
                            random.nextDouble() - 0.5,
                            random.nextDouble() - 0.5,
                            random.nextDouble() - 0.5);
                    client.particleManager.addParticle(cn.woshiikun_1145.mcmod.choco.cstmm.particle.ModParticles.SMOKE_PARTICLE,
                            shuffled.x, shuffled.y, shuffled.z, 0.0, 0.0, 0.0);
                }
                this.renderBlockPos.remove(0);
            }
        }
    }
}
