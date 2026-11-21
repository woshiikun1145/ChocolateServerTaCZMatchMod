package cn.woshiikun_1145.mcmod.choco.cstmm.particle;

import cn.woshiikun_1145.mcmod.choco.cstmm.Cstmm;
import net.minecraft.particle.SimpleParticleType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

/**
 * 【作用】模组粒子类型注册表（命名空间 cstmm）：cstmm:smoke_particle = 烟雾弹烟雾粒子
 *         （移植自 CounterStrikeGrenades 的 SMOKE_PARTICLE）。粒子 JSON 定义位于
 *         assets/cstmm/particles/smoke_particle.json，客户端工厂在
 *         client/grenade/render/SmokeGrenadeParticle（经 ParticleFactoryRegistry 注册）。
 * 【被谁使用】Cstmm.onInitialize（启动时注册，须在注册表冻结前）；
 *           客户端 SmokeRenderManager（按烟雾方块生成粒子，经 world.addParticle 触发）。
 */
public class ModParticles {

    /** 烟雾弹烟雾粒子（客户端自定义 Particle 类渲染，带透明度衰减/恢复语义）；
     *  匿名子类绕过 yarn 下 SimpleParticleType 的 protected 构造器（mojmap 下为 public）。 */
    public static final SimpleParticleType SMOKE_PARTICLE =
            Registry.register(Registries.PARTICLE_TYPE, Identifier.of("cstmm", "smoke_particle"),
                    new SimpleParticleType(true) {
                    });

    /**
     * 【作用】粒子类型注册入口（必须在注册表冻结前调用）：静态字段加载即完成注册。
     * 【被谁使用】Cstmm.onInitialize（ModGuardian 包装，异常时进入保护模式）。
     */
    public static void initialize() {
        Cstmm.LOGGER.info("[CSTMM - ModParticles] Particle registered: cstmm:smoke_particle");
    }
}
