package cn.woshiikun_1145.mcmod.choco.cstmm.util;

import cn.woshiikun_1145.mcmod.choco.cstmm.data.MatchSession;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.config.MapConfig;
import cn.woshiikun_1145.mcmod.choco.cstmm.entity.BombEntity;
import cn.woshiikun_1145.mcmod.choco.cstmm.network.NetworkHandler;
import cn.woshiikun_1145.mcmod.choco.cstmm.sound.ModSounds;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.explosion.Explosion;

import java.util.List;

/**
 * 【作用】C4 爆炸伤害计算器（新版公式，MC 20 HP 口径，参数按图可配）：
 *         d1 = -m·l² + h（抛物线距离衰减，m=衰减系数、h=l=0 基础伤害）；
 *         p  = 基础掩体减伤率（l<lA 满额 1；lA≤l<lB 线性衰减 (lB-l)/(lB-lA)；l≥lB 为 0）；
 *         eCR = 爆炸接触率：实体碰撞箱未被方块阻挡部分的表面积占比（原版爆炸暴露判定，
 *               位于爆心且完全无遮挡 = 100%），修正 eCRB：eCR≥0.5 时 1/eCR，否则 0.5；
 *         背对爆心固定减伤 dB；最终 d = floor(d1·p·eCRB − dB)，最小 0。
 *         爆炸本体不破坏地形（CS 语义），仅视觉与音效。
 * 【被谁使用】BombRoundManager#detonateBomb（C4 倒计时归零时调用）。仅服务端。
 */
public class C4DamageCalculator {

    private C4DamageCalculator() {}

    /**
     * 【作用】对局内 C4 爆炸入口：以安放位置为中心对**对局全员**结算伤害，播放无破坏爆炸
     *         视觉/音效，广播爆炸提示。
     * 【被谁使用】BombRoundManager#detonateBomb。仅服务端。
     */
    public static void detonate(ServerWorld world, MatchSession session, MapConfig config) {
        MapConfig.BombSettings settings = config.getBombSettings();
        int[] pos = session.getPlantedPos();
        if (pos == null) return;
        Vec3d center = new Vec3d(pos[0] + 0.5, pos[1] + 0.5, pos[2] + 0.5);

        explodeAt(world, center, settings, session);

        // 广播不含包点位置（与安放广播口径一致）
        NetworkHandler.broadcastToMatch(session, "§c*** C4 已爆炸！ ***");
    }

    /**
     * 【作用】自由炸弹自爆入口（对局外安放、matchId 为空的 BombEntity 倒计时归零）：
     *         以实体位置为中心对**世界中全部玩家**结算伤害（使用默认炸弹参数），
     *         播放无破坏爆炸视觉，随后自毁实体。
     * 【被谁使用】BombEntity#tick（fuseTicks 归零，仅服务端侧）。仅服务端。
     */
    public static void detonateFree(BombEntity bomb) {
        explodeAt((ServerWorld) bomb.getWorld(), bomb.getPos(),
                new MapConfig.BombSettings(), null);
        bomb.discard();
    }

    /**
     * 爆炸结算本体：纯自定义爆炸表现（不调用 createExplosion——去除原版爆炸声与原版伤害，
     * 方块不破坏），按文档模型结算伤害 + 自定义击退。
     * 伤害对象：session 非空 = 对局全员（对局内炸弹）；null = 世界中全部玩家（自由炸弹）。
     */
    private static void explodeAt(ServerWorld world, Vec3d center, MapConfig.BombSettings settings,
                                  MatchSession session) {
        // 伤害对象先收集：session 非空 = 对局全员（对局内炸弹）；null = 世界中全部玩家（自由炸弹）
        List<ServerPlayerEntity> targets = new java.util.ArrayList<>();
        if (session != null) {
            for (var uuid : session.getAllPlayers()) {
                ServerPlayerEntity p = world.getServer().getPlayerManager().getPlayer(uuid);
                if (p != null) targets.add(p);
            }
        } else {
            targets.addAll(world.getPlayers());
        }

        // 爆炸表现：粒子 + 自定义轰鸣（可闻半径可配 explosionSoundRange，音量 = 半径/16），不触发原版爆炸声
        spawnExplosionParticles(world, center);
        if (session != null) {
            // 对局内炸弹：爆炸声仅单播给本对局玩家（跨对局隔离——地图 A 的对局玩家即使在
            // 地图 B 附近也听不到地图 B 对局炸弹的爆炸声；可闻半径仍由客户端按音量衰减）
            net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket soundPacket =
                    new net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket(
                            net.minecraft.registry.entry.RegistryEntry.of(ModSounds.C4_EXPLOSION.value()),
                            net.minecraft.sound.SoundCategory.BLOCKS,
                            center.x, center.y, center.z,
                            settings.getExplosionSoundRange() / 16.0f, 1.0f, world.getRandom().nextLong());
            for (ServerPlayerEntity p : targets) {
                p.networkHandler.sendPacket(soundPacket);
            }
        } else {
            // 自由炸弹（对局外）：保持世界广播，谁在附近谁听
            world.playSound(null, center.x, center.y, center.z,
                    ModSounds.C4_EXPLOSION.value(), net.minecraft.sound.SoundCategory.BLOCKS,
                    settings.getExplosionSoundRange() / 16.0f, 1.0f);
        }

        for (ServerPlayerEntity player : targets) {
            if (player.isDead() || player.isSpectator()) continue;

            applyKnockback(player, center);

            int damage = calculate(world, center, player, settings);
            if (damage <= 0) continue;

            DamageSource source = world.getDamageSources().explosion(null, null);
            player.damage(source, damage);
            player.sendMessage(Text.literal("§c受到 C4 爆炸伤害：" + damage), false);
        }
    }

    /** 爆炸粒子：中心大爆云 + 周围散布爆云与烟尘（服务端 spawnParticles 直接下发）。 */
    private static void spawnExplosionParticles(ServerWorld world, Vec3d center) {
        world.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, center.x, center.y, center.z, 1, 0, 0, 0, 0);
        world.spawnParticles(ParticleTypes.EXPLOSION, center.x, center.y, center.z, 6, 1.5, 1.0, 1.5, 0.1);
        world.spawnParticles(ParticleTypes.POOF, center.x, center.y, center.z, 12, 1.0, 0.8, 1.0, 0.15);
    }

    /** 爆炸击退：距离爆心越近推力越大（16 格内线性衰减），带向上分量（补回原版爆炸的击退感）。 */
    private static void applyKnockback(ServerPlayerEntity player, Vec3d center) {
        Vec3d away = player.getPos().add(0, 0.9, 0).subtract(center);
        double d = away.length();
        if (d >= 16) return;
        Vec3d dir = d < 0.01 ? new Vec3d(0, 1, 0) : away.multiply(1.0 / d);
        double power = 1.1 * (1 - d / 16.0);
        player.addVelocity(dir.x * power, dir.y * power * 0.6 + 0.15, dir.z * power);
        player.velocityModified = true; // 服务端手动改速度后需同步客户端
    }

    /**
     * 【作用】计算单个玩家的爆炸伤害（新版公式，向下取整，最小 0）：
     *         d1 = -m·l² + h → 掩体减伤率 p → 接触率 eCRB → 背对减 dB。
     */
    public static int calculate(ServerWorld world, Vec3d center, ServerPlayerEntity player,
                                MapConfig.BombSettings s) {
        // l：玩家到爆心的欧氏距离（玩家脚部位置）
        double l = player.getPos().distanceTo(center);

        // ① 基础伤害 d1 = -m·l² + h（抛物线衰减）
        double d1 = -s.getExplosionM() * l * l + s.getExplosionH();

        // ② 基础掩体减伤率 p（纯距离函数：l<lA 满额；lA≤l<lB 线性衰减；l≥lB 为 0）
        double p;
        if (l < s.getExplosionLA()) {
            p = 1;
        } else if (l < s.getExplosionLB()) {
            p = (s.getExplosionLB() - l) / (s.getExplosionLB() - s.getExplosionLA());
        } else {
            p = 0;
        }

        // ③ 爆炸接触率 eCR：实体碰撞箱未被方块阻挡部分的表面积占比（原版爆炸暴露判定：
        //    对碰撞箱网格采样点逐点向爆心 raycast，统计未被阻挡的比例；位于爆心且完全
        //    无遮挡时 = 100%，被方块完全遮挡时趋近 0）
        //    修正 eCRB：eCR ≥ 0.5 → eCRB = 1/eCR；eCR < 0.5 → eCRB = 0.5
        double eCR = Explosion.getExposure(center, player);
        double eCRB = eCR >= 0.5 ? 1.0 / eCR : 0.5;

        // ④ 最终伤害 d = d1·p·eCRB − dB（背对爆心时减 dB），向下取整且最小 0
        double d = d1 * p * eCRB;
        if (isFacingAway(player, center)) {
            d -= s.getExplosionBackDamage();
        }
        return Math.max(0, (int) Math.floor(d));
    }

    /** 玩家是否背对爆心：视线方向与"玩家→爆心"方向点积 < 0（视线远离爆心）。 */
    private static boolean isFacingAway(ServerPlayerEntity player, Vec3d center) {
        Vec3d look = player.getRotationVec(1.0F);
        Vec3d toBomb = center.subtract(player.getPos()).normalize();
        return look.dotProduct(toBomb) < 0;
    }
}
