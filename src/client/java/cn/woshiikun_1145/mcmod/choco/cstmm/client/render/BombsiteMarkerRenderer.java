package cn.woshiikun_1145.mcmod.choco.cstmm.client.render;

import cn.woshiikun_1145.mcmod.choco.cstmm.client.cache.ConfigDataCache;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.hud.HudOverlay;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.config.MapConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.font.TextRenderer.TextLayerType;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.List;
import java.util.Locale;

/**
 * 【作用】爆破对局包点位置标记（XaeroMap waypoint 视觉样式）：当前对局地图配置的每个有效包点
 *         在长方体中心上方约 2.5 格处渲染一个始终面向相机的 billboard——彩色方块（白色大写
 *         字母；A 紫色 / B 橙色，其他包点名按首字符哈希配色）+ 方块下方"名称 · 距离"文字；
 *         大小随距离略放大（近距离不遮屏、远距离可辨识），方块/文字经深度测试被地形正常遮挡。
 *         billboard 实现 = 原版名牌同款矩阵链：平移到相机相对坐标 → 乘相机旋转四元数（与
 *         RenderSystem 模型视图中的相机旋转相消，内容屏幕对齐）→ scale(s, -s, s)（s 随距离缩放）。
 *         显示条件：处于爆破对局中（HudOverlay.isBombMatchInGame）且按 HUD 数据的地图 ID 从
 *         ConfigDataCache 匹配到爆破地图配置（维度一致）并存在有效包点；服务端无需改动
 *         （配置已全量同步到客户端）。
 * 【被谁使用】CstmmClient#onInitializeClient 调用 init() 注册到 WorldRenderEvents.AFTER_ENTITIES。
 */
@Environment(EnvType.CLIENT)
public final class BombsiteMarkerRenderer {

    /** 包点标记锚点：包点长方体顶面（两角点 Y 最大值）之上高度（格） */
    private static final double ANCHOR_HEIGHT = 2.5;

    /** 基准缩放（1 文字像素的世界尺寸，与原版名牌 0.025 同量级） */
    private static final float BASE_SCALE = 0.025f;

    /** 方块半边长（文字像素单位，方块 22x22） */
    private static final float HALF_BLOCK = 11f;

    /** 包点配色（RGB）：A 紫色系、B 橙色系 */
    private static final int COLOR_A = 0xB352C7;
    private static final int COLOR_B = 0xFFA631;
    /** 其他包点名首字符哈希的回退配色表 */
    private static final int[] FALLBACK_COLORS = {0x4CAF50, 0x2196F3, 0xE91E63, 0xFFD54F, 0x00BCD4, 0xFF7043};

    private BombsiteMarkerRenderer() {
    }

    /**
     * 【作用】注册包点标记渲染回调（AFTER_ENTITIES：实体渲染后、方块实体渲染前，与实体名牌
     *         同一矩阵栈/模型视图状态，便于复用原版名牌的 billboard 矩阵链）。
     * 【被谁使用】仅被 CstmmClient#onInitializeClient 调用一次。
     */
    public static void init() {
        WorldRenderEvents.AFTER_ENTITIES.register(BombsiteMarkerRenderer::afterEntities);
    }

    /**
     * 【作用】每帧渲染当前对局地图的全部有效包点标记；非爆破对局 / 无匹配地图配置 / 无包点时
     *         直接返回（每帧开销近似为零）。
     * 【被谁使用】WorldRenderEvents.AFTER_ENTITIES（init 注册后由渲染循环每帧回调）。
     */
    private static void afterEntities(WorldRenderContext context) {
        // 仅爆破对局内显示（与名牌隐藏开关共用同一"对局中"查询）
        if (!HudOverlay.isBombMatchInGame()) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) return;
        MatrixStack matrices = context.matrixStack();
        VertexConsumerProvider consumers = context.consumers();
        if (matrices == null || consumers == null) return;

        MapConfig map = resolveMap(client);
        if (map == null) return;
        List<MapConfig.Bombsite> sites = map.getBombsites();
        if (sites.isEmpty()) return;

        TextRenderer textRenderer = client.textRenderer;
        Vec3d camPos = context.camera().getPos();

        for (MapConfig.Bombsite site : sites) {
            // 未配置（两角点全 0）的包点不渲染
            if (site.isUnconfigured()) continue;
            double centerX = (site.getX1() + site.getX2()) / 2.0;
            double centerZ = (site.getZ1() + site.getZ2()) / 2.0;
            double anchorY = Math.max(site.getY1(), site.getY2()) + ANCHOR_HEIGHT;
            // 距离 = 玩家眼睛位置到包点中心的水平距离（1 格 = 1 米，取整显示）
            double dx = centerX - camPos.x;
            double dz = centerZ - camPos.z;
            int distMeters = (int) Math.round(Math.sqrt(dx * dx + dz * dz));
            renderMarker(context, matrices, consumers, textRenderer, camPos,
                    centerX, anchorY, centerZ, distMeters, site);
        }
    }

    /**
     * 【作用】从客户端配置缓存匹配当前对局的地图配置：按 HUD 数据包携带的地图 ID 匹配
     *         （与服务端 maps.json 的地图 id 同一键空间），失败时兜底按展示名匹配；
     *         仅接受爆破图，其余返回 null（不渲染）。
     * 【被谁使用】afterEntities（每帧）。
     */
    private static MapConfig resolveMap(MinecraftClient client) {
        String mapId = HudOverlay.getMapName();
        if (mapId == null || mapId.isEmpty()) return null;
        ConfigDataCache cache = ConfigDataCache.getInstance();
        MapConfig map = cache.getMap(mapId);
        if (map == null) {
            for (MapConfig candidate : cache.getMaps()) {
                if (candidate.getDisplayName().equals(mapId)) {
                    map = candidate;
                    break;
                }
            }
        }
        if (map == null || !map.isBombMap()) return null;
        // 配置声明的维度与当前世界不一致时不渲染（防止跨维错标）
        if (!client.world.getRegistryKey().getValue().toString().equals(map.getDimension())) return null;
        return map;
    }

    /**
     * 【作用】渲染单个包点 billboard：彩色方块（白 1px 描边 + 填充色）+ 白色大写字母 + 下方
     *         "名称 · 距离"文字，全部面向相机（原版名牌矩阵链），尺寸随距离在 1~4 倍间缩放。
     * 【被谁使用】afterEntities（每帧每包点）。
     */
    private static void renderMarker(WorldRenderContext context, MatrixStack matrices,
                                     VertexConsumerProvider consumers, TextRenderer textRenderer,
                                     Vec3d camPos, double x, double y, double z,
                                     int distMeters, MapConfig.Bombsite site) {
        String name = site.getName();
        String letter = name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(Locale.ROOT);
        int color = colorFor(name);
        String label = name + " · " + distMeters + "m";

        matrices.push();
        // 相机相对坐标（事件矩阵栈不含相机平移，需自行减去相机位置）
        matrices.translate(x - camPos.x, y - camPos.y, z - camPos.z);
        // 乘相机旋转四元数：与模型视图中的相机旋转（positionMatrix = 其共轭）相消，
        // 使内容屏幕对齐（= 原版 EntityRenderer#renderLabelIfPresent 同款处理）
        matrices.multiply(context.camera().getRotation());
        // 大小随距离略放大（16m 内 1 倍，最远 4 倍；y 取负与原版名牌一致保证文字正向）
        float scale = BASE_SCALE * MathHelper.clamp(distMeters / 16f, 1f, 4f);
        matrices.scale(scale, -scale, scale);
        Matrix4f matrix = matrices.peek().getPositionMatrix();

        // 方块底衬：先请求 debug quads 缓冲（后画的文字覆盖方块，与原版 DebugRenderer 背景同模式）
        VertexConsumer quads = consumers.getBuffer(RenderLayer.getDebugQuads());
        quad(quads, matrix, -HALF_BLOCK - 1, -HALF_BLOCK - 1, HALF_BLOCK + 1, HALF_BLOCK + 1, 0xFFFFFFFF);
        quad(quads, matrix, -HALF_BLOCK, -HALF_BLOCK, HALF_BLOCK, HALF_BLOCK, 0xE0000000 | color);

        // 白色大写字母（1.5 倍内缩放，居中于方块）
        matrices.push();
        matrices.translate(-textRenderer.getWidth(Text.literal(letter)) * 1.5f / 2f, -6f, 0f);
        matrices.scale(1.5f, 1.5f, 1f);
        textRenderer.draw(Text.literal(letter), 0f, 0f, 0xFFFFFFFF, true,
                matrices.peek().getPositionMatrix(), consumers, TextLayerType.NORMAL, 0,
                LightmapTextureManager.MAX_LIGHT_COORDINATE);
        matrices.pop();

        // 方块下方"名称 · 距离"文字（水平居中）
        textRenderer.draw(Text.literal(label), -textRenderer.getWidth(Text.literal(label)) / 2f,
                HALF_BLOCK + 1f, 0xFFFFFFFF, true, matrix, consumers, TextLayerType.NORMAL, 0,
                LightmapTextureManager.MAX_LIGHT_COORDINATE);
        matrices.pop();
    }

    /** 【作用】向 debug quads 层提交一个 XY 平面矩形四边形（顶点顺序无关，该层禁用面剔除）。 */
    private static void quad(VertexConsumer quads, Matrix4f matrix,
                             float x1, float y1, float x2, float y2, int argb) {
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >>> 16) & 0xFF;
        int g = (argb >>> 8) & 0xFF;
        int b = argb & 0xFF;
        quads.vertex(matrix, x1, y1, 0f).color(r, g, b, a);
        quads.vertex(matrix, x2, y1, 0f).color(r, g, b, a);
        quads.vertex(matrix, x2, y2, 0f).color(r, g, b, a);
        quads.vertex(matrix, x1, y2, 0f).color(r, g, b, a);
    }

    /** 【作用】按包点名取填充色：A 紫色、B 橙色，其余按首字符哈希从回退配色表取色。 */
    private static int colorFor(String name) {
        if ("A".equalsIgnoreCase(name)) return COLOR_A;
        if ("B".equalsIgnoreCase(name)) return COLOR_B;
        String first = name.isEmpty() ? "X" : name.substring(0, 1);
        return FALLBACK_COLORS[Math.floorMod(first.hashCode(), FALLBACK_COLORS.length)];
    }
}
