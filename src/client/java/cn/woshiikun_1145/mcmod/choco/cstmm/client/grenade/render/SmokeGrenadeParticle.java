package cn.woshiikun_1145.mcmod.choco.cstmm.client.grenade.render;

import cn.woshiikun_1145.mcmod.choco.cstmm.util.GrenadeSettings;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleTextureSheet;
import net.minecraft.client.particle.SpriteProvider;
import net.minecraft.client.particle.SpriteBillboardParticle;
import net.minecraft.client.world.ClientWorld;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 【作用】烟雾弹烟雾粒子（移植自 CounterStrikeGrenades 的 SmokeGrenadeParticle）：
 *         无重力零速度、按烟雾寿命设定生命周期、4 倍放大、半透明渲染；
 *         opacityTime > 0 时强制全透明（被子弹/箭矢/HE 打散），归零后恢复不透明。
 *         注：yarn 1.21.1 中带贴图的粒子基类为 SpriteBillboardParticle（= mojmap
 *         TextureSheetParticle），渲染类型枚举为 ParticleTextureSheet。
 * 【被谁使用】内部 Factory（ParticleFactoryRegistry 注册，cstmm:smoke_particle 触发）；
 *           SmokeRenderManager（透明度重置与粒子遍历）。
 */
@Environment(EnvType.CLIENT)
public class SmokeGrenadeParticle extends SpriteBillboardParticle {

    /** 当前存活的全部烟雾粒子（打散判定遍历用；CopyOnWriteArrayList 保证渲染线程安全遍历） */
    public static final List<SmokeGrenadeParticle> ACTIVE = new CopyOnWriteArrayList<>();

    /** 打散倒计时（tick）：>0 时全透明，归零恢复不透明（原 mod 语义一致） */
    public int opacityTime = 0;

    public SmokeGrenadeParticle(ClientWorld world, double x, double y, double z) {
        super(world, x, y, z, 0.0, 0.0, 0.0);
        this.gravityStrength = 0f;
        this.velocityX = 0;
        this.velocityY = 0;
        this.velocityZ = 0;
        this.maxAge = (int) (GrenadeSettings.SMOKE_LIFETIME_MS / 50.0);
        this.scale(4.0f);
        ACTIVE.add(this);
    }

    // 【作用】每 tick：透明度倒计时推进；死亡（超时）时从 ACTIVE 列表移除
    @Override
    public void tick() {
        super.tick();
        if (this.opacityTime > 0) {
            this.alpha = 0f;
            this.opacityTime--;
        } else {
            this.alpha = 1f;
        }
        if (this.dead) {
            ACTIVE.remove(this);
        }
    }

    @Override
    public ParticleTextureSheet getType() {
        return ParticleTextureSheet.PARTICLE_SHEET_TRANSLUCENT;
    }

    // 【作用】公开粒子坐标（Particle 的 x/y/z 为 protected，跨类打包判定需要读取）
    public double getXPos() {
        return x;
    }

    public double getYPos() {
        return y;
    }

    public double getZPos() {
        return z;
    }

    /**
     * 【作用】烟雾粒子工厂（SpriteProvider 由 ParticleFactoryRegistry 注入，取粒子贴图集）。
     * 【被谁使用】CstmmClient（ParticleFactoryRegistry 注册 cstmm:smoke_particle）。
     */
    @Environment(EnvType.CLIENT)
    public static class Factory implements net.minecraft.client.particle.ParticleFactory<net.minecraft.particle.SimpleParticleType> {
        private final SpriteProvider spriteProvider;

        public Factory(SpriteProvider spriteProvider) {
            this.spriteProvider = spriteProvider;
        }

        @Override
        public Particle createParticle(net.minecraft.particle.SimpleParticleType type, ClientWorld world,
                                        double x, double y, double z, double velocityX, double velocityY, double velocityZ) {
            SmokeGrenadeParticle particle = new SmokeGrenadeParticle(world, x, y, z);
            particle.setSprite(this.spriteProvider);
            return particle;
        }
    }

    // 【作用】关服/断线/退出存档时清空静态列表（防止泄漏与旧世界引用）；由 SmokeRenderManager
    //         register 的 tick 在 client.world == null（标题界面/换维加载中）时兜底调用
    public static void clearAll() {
        for (SmokeGrenadeParticle particle : ACTIVE) {
            particle.markDead();
        }
        ACTIVE.clear();
    }

    // 【作用】把当前世界实例不匹配的粒子踢出列表（换世界后旧粒子引用失效）。
    //         注意：CopyOnWriteArrayList 的迭代器不支持 remove()（必然抛 UnsupportedOperationException，
    //         曾在换维度/重连且场上有烟雾粒子残留时崩溃客户端），须用 COW 原生支持的 removeIf
    static void purgeOfOtherWorlds(ClientWorld currentWorld) {
        ACTIVE.removeIf(particle -> particle.world != currentWorld);
    }
}
