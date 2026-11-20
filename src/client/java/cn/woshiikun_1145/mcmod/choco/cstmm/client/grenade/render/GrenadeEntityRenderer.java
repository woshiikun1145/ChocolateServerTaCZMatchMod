package cn.woshiikun_1145.mcmod.choco.cstmm.client.grenade.render;

import cn.woshiikun_1145.mcmod.choco.cstmm.entity.CounterStrikeGrenadeEntity;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedModelManager;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;

/**
 * 【作用】手雷投掷物实体渲染器（移植自 CounterStrikeGrenades 的 GrenadeRenderer）：
 *         读取实体的三轴自转角做帧间插值，缩放 0.5 并渲染"投掷形态"物品模型
 *         （*_t 变体，如 cstmm:item/hegrenade_t；找不到时退回物品默认模型）。
 *         与原 mod 的差异：flashbang 的自定义 3D 实体模型（models/entity/flashbang.json，
 *         原 mod 自定义加载器）未移植，三种手雷统一走物品模型渲染，外观差异仅投掷姿态。
 * 【被谁使用】CstmmClient（EntityRendererRegistry 注册 cstmm:hegrenade / flashbang /
 *           smokegrenade → 本类）。仅客户端。
 */
@Environment(EnvType.CLIENT)
public class GrenadeEntityRenderer<T extends CounterStrikeGrenadeEntity> extends EntityRenderer<T> {

    private final ItemRenderer itemRenderer;

    public GrenadeEntityRenderer(EntityRendererFactory.Context ctx) {
        super(ctx);
        this.itemRenderer = ctx.getItemRenderer();
    }

    // 【作用】渲染投掷物：自转插值 → 上移 0.125（视觉中心对齐碰撞中心）→ 三轴旋转 → 缩放 0.5 →
    //         渲染 *_t 投掷模型（缺失/内置渲染器时退回物品默认模型）
    @Override
    public void render(T entity, float yaw, float tickDelta, MatrixStack matrices,
                       VertexConsumerProvider vertexConsumers, int light) {
        // 【作用】燃烧类手雷起火后隐藏投掷物模型（与原版 GrenadeRenderer 一致），火焰由粒子接管
        if (entity instanceof cn.woshiikun_1145.mcmod.choco.cstmm.entity.AbstractFireGrenadeEntity fire
                && fire.isActivated()) {
            return;
        }
        ItemStack stack = entity.getStack();
        if (stack.isEmpty()) {
            return; // 烟雾激活后物品被清空，实体本体不再渲染（粒子接管）
        }

        // 【作用】帧间自转角插值（原 mod rotLerp 同款）
        float visualYRot = MathHelper.lerpAngleDegrees(tickDelta, entity.customYRotO, entity.customYRot);
        float visualXRot = MathHelper.lerpAngleDegrees(tickDelta, entity.customXRotO, entity.customXRot);
        float visualZRot = MathHelper.lerpAngleDegrees(tickDelta, entity.customZRotO, entity.customZRot);

        matrices.push();
        matrices.translate(0.0, 0.125, 0.0);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(visualYRot));
        matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(visualZRot));
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(visualXRot));
        matrices.scale(0.5f, 0.5f, 0.5f);

        // 【作用】定位"投掷形态"模型：item.cstmm.<name> → cstmm:item/<name>_t；
        //         模型缺失/内置渲染器时退回物品默认模型（与原 mod fallback 语义一致）
        String itemName = stack.getItem().getTranslationKey().replaceFirst("item\\.cstmm\\.", "");
        BakedModelManager modelManager = MinecraftClient.getInstance().getBakedModelManager();
        BakedModel thrownModel = modelManager.getModel(Identifier.of("cstmm", "item/" + itemName + "_t"));
        BakedModel model = (thrownModel != null && thrownModel != modelManager.getMissingModel()
                && !thrownModel.isBuiltin())
                ? thrownModel
                : this.itemRenderer.getModel(stack, entity.getWorld(), null, 0);

        this.itemRenderer.renderItem(
                stack,
                ModelTransformationMode.FIXED,
                false,
                matrices,
                vertexConsumers,
                light,
                OverlayTexture.DEFAULT_UV,
                model);

        matrices.pop();
    }

    @Override
    public Identifier getTexture(T entity) {
        // 物品模型渲染走方块图集（getTexture 仅由 super.render 通道要求实现）
        return Identifier.ofVanilla("textures/atlas/blocks.png");
    }
}
