package cn.woshiikun_1145.mcmod.choco.cstmm.client.render;

import cn.woshiikun_1145.mcmod.choco.cstmm.entity.BombEntity;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;

/**
 * 【作用】C4 实体渲染器（占位外观）：绘制一个缩放后的绿色混凝土方块（lime concrete），
 *         即用户指定的"绿色长方体"占位；正式建模替换时仅改本类（或换自定义模型资源）。
 *         底面贴地：半高平移 + XZ 压扁的扁盒造型，近似 CS 的 C4 箱体。
 * 【被谁使用】CstmmClient（EntityRendererRegistry 注册 cstmm:c4 → 本类）。仅客户端。
 */
@Environment(EnvType.CLIENT)
public class BombEntityRenderer extends EntityRenderer<BombEntity> {

    /** 占位外观 = C4 物品栈（复用 cstmm:item/c4 的 3D 模型，物品栏/手持/实体渲染同一套占位资源） */
    private static final ItemStack PLACEHOLDER_STACK =
            new ItemStack(cn.woshiikun_1145.mcmod.choco.cstmm.item.ModItems.C4);

    private final net.minecraft.client.render.item.ItemRenderer itemRenderer;

    public BombEntityRenderer(EntityRendererFactory.Context ctx) {
        super(ctx);
        this.itemRenderer = ctx.getItemRenderer();
    }

    @Override
    public void render(BombEntity entity, float yaw, float tickDelta, MatrixStack matrices,
                       VertexConsumerProvider vertexConsumers, int light) {
        matrices.push();
        // ItemRenderer 以模型中心 (0.5,0.5,0.5) 对齐原点：上移 0.5 使模型底面 (y=0) 贴住
        // 实体原点（碰撞箱底部），与碰撞盒 0.875×0.375×0.625 完全对齐
        matrices.translate(0.0, 0.5, 0.0);
        BakedModel model = this.itemRenderer.getModel(PLACEHOLDER_STACK, entity.getWorld(), null, 0);
        this.itemRenderer.renderItem(
                PLACEHOLDER_STACK,
                ModelTransformationMode.NONE,
                false,
                matrices,
                vertexConsumers,
                light,
                OverlayTexture.DEFAULT_UV,
                model
        );
        matrices.pop();
        super.render(entity, yaw, tickDelta, matrices, vertexConsumers, light);
    }

    @Override
    public Identifier getTexture(BombEntity entity) {
        // 方块物品渲染走方块图集（getTexture 仅由 super.render 通道要求实现）；
        // 直接用图集 ID 原值，规避 SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE 的弃用警告
        return BLOCK_ATLAS_ID;
    }

    /** 方块图集 ID（= 旧 SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE 的值，1.21.1 中该常量已弃用且无替代） */
    private static final Identifier BLOCK_ATLAS_ID = Identifier.ofVanilla("textures/atlas/blocks.png");
}
