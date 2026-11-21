package cn.woshiikun_1145.mcmod.choco.cstmm.mixin;

import cn.woshiikun_1145.mcmod.choco.cstmm.client.cache.ClientConfig;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.hud.HudOverlay;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 【作用】对局中隐藏玩家头顶名牌：客户端配置（ClientConfig.hideNameTags）开启且当前处于
 *         爆破对局中（HudOverlay.isBombMatchInGame）时，在 PlayerEntityRenderer
 *         的名牌渲染入口（renderLabelIfPresent，含计分板队伍前缀/装饰）直接取消，
 *         玩家头顶不再显示任何名牌内容；仅影响玩家（其他实体的名牌不受影响）。
 *         说明：本项目的 fabric-api 0.115.6+1.21.1 未提供 RenderNameTagEvent，
 *         故以客户端 Mixin 实现（事件取消语义等价于 HEAD 注入取消整个名牌渲染）。
 * 【被谁使用】chocolateservertaczmatchmod.mixins.json（client 列表）。仅客户端。
 */
@Mixin(PlayerEntityRenderer.class)
public abstract class PlayerEntityRendererMixin {

    @Inject(method = "renderLabelIfPresent(Lnet/minecraft/client/network/AbstractClientPlayerEntity;"
                    + "Lnet/minecraft/text/Text;Lnet/minecraft/client/util/math/MatrixStack;"
                    + "Lnet/minecraft/client/render/VertexConsumerProvider;IF)V",
            at = @At("HEAD"), cancellable = true)
    private void cstmm$hideNameTagInBombMatch(AbstractClientPlayerEntity player, Text text,
                                              MatrixStack matrices, VertexConsumerProvider vertexConsumers,
                                              int light, float tickDelta, CallbackInfo ci) {
        if (ClientConfig.isHideNameTags() && HudOverlay.isBombMatchInGame()) {
            ci.cancel();
        }
    }
}
