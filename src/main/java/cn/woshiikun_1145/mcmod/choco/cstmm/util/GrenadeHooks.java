package cn.woshiikun_1145.mcmod.choco.cstmm.util;

import cn.woshiikun_1145.mcmod.choco.cstmm.entity.AbstractFireGrenadeEntity;
import cn.woshiikun_1145.mcmod.choco.cstmm.entity.SmokeGrenadeEntity;
import net.minecraft.entity.Entity;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.math.Vec3d;

/**
 * 【作用】手雷系统 common → client 桥：实体/物品的服务端代码在需要触发"仅客户端"效果
 *         （本地音量音效、粒子、屏幕 overlay）时，经本桥静态引用调用客户端实现。
 *         CSTMM 采用 loom split sources，common 代码不能直接 import client 类，
 *         故用接口 + 客户端启动时注入实现的方式解耦（未注入时调用方需判空）。
 * 【被谁使用】客户端 CstmmClient 初始化时执行 {@code GrenadeHooks.client = new GrenadeClientHooksImpl()}；
 *           CounterStrikeGrenadeEntity（碰撞音效）、SmokeGrenadeEntity（发烟/烟雾打散）、
 *           HEGrenadeEntity（爆炸视觉）、CounterStrikeGrenadeItem（拔销音）。
 */
public final class GrenadeHooks {

    private GrenadeHooks() {
    }

    /** 客户端实现注入点；仅客户端环境在初始化时赋值，服务端恒为 null。 */
    public static ClientHooks client = null;

    /**
     * 【作用】客户端手雷效果的接口契约。方法语义见各实现类。
     * 【被谁使用】仅 client 侧实现（client/grenade/GrenadeClientHooksImpl），common 侧调用。
     */
    public interface ClientHooks {
        /**
         * 按手雷与本地玩家的距离播放带衰减音量的实体音效
         * （移植原 mod 的 EntityBoundSoundInstance 距离音量逻辑）。
         */
        void playDistanceSound(Entity entity, SoundEvent sound, double initialVolume, double hearableRange);

        /** HE 手雷爆炸视觉（原 mod HEGrenadeRenderManager.render）。 */
        void onHeExploded(Vec3d pos);

        /** 烟雾弹开始发烟：播放发烟音效并生成烟雾粒子（原 mod SmokeRenderManager.render）。 */
        void onSmokeEmit(SmokeGrenadeEntity entity);

        /**
         * 在烟雾实体内清理指定范围与位置的烟雾粒子透明度（子弹/箭矢/爆炸穿烟），
         * 距离越远恢复越慢。
         */
        void clearSmokeWithinRange(SmokeGrenadeEntity entity, Vec3d pos, double range, boolean debugHeader);

        /**
         * 每 tick 穿烟打散判定：箭矢（扫掠盒插值）与 TACZ 子弹（按实体类名反射，
         * 需 ClientWorld 实体遍历能力，故整体放在客户端实现）。
         */
        void disperseSmokeByProjectiles(SmokeGrenadeEntity entity);

        /**
         * 已起火燃烧类手雷的逐 tick 火焰粒子渲染（按 spreadBlocks 随机取方块顶面生成
         * 火焰/烟雾粒子；燃烧弹用魂火、莫洛托夫用常规火焰）。
         */
        void renderFireGrenade(AbstractFireGrenadeEntity entity);

        /** 手雷物品拔销音（切换到该物品槽位时本地播放一次）。 */
        void playDrawSound(SoundEvent sound);
    }
}
