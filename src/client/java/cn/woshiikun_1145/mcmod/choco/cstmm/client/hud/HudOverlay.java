package cn.woshiikun_1145.mcmod.choco.cstmm.client.hud;

import cn.woshiikun_1145.mcmod.choco.cstmm.client.ClientHandshakeState;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;

/**
 * 【作用】对战信息 HUD 覆盖层：在屏幕中央上方绘制地图名、红蓝双方击杀数、剩余时间与击杀占比色条，
 *         支持进出对局的淡入淡出动画；未握手成功时不渲染。
 * 【被谁使用】CstmmClient#onInitializeClient 调用 init() 注册到 HudRenderCallback；
 *             ClientNetworkHandler 的 HudDataPayload 接收器调用 updateData、DISCONNECT 回调调用 reset。
 */
@Environment(EnvType.CLIENT)
public class HudOverlay implements HudRenderCallback {

    // 饿汉式单例（HudRenderCallback 事件监听器）
    private static final HudOverlay INSTANCE = new HudOverlay();

    // 当前 HUD 数据
    // 地图显示名；空串表示未在对局
    private static String mapName = "";
    // 红队击杀数
    private static int redKills = 0;
    // 蓝队击杀数
    private static int blueKills = 0;
    // 对局剩余秒数；0 表示非计时模式
    private static int remainingSeconds = 0;
    // 当前是否处于对局中
    private static boolean inGame = false;

    // 动画相关
    // HUD 整体透明度系数（0~1），进出对局时逐帧增减实现淡入淡出
    private static float fadeAlpha = 1.0f;

    // 单例，禁止外部实例化
    private HudOverlay() {}

    /**
     * 【作用】将本类注册为 HUD 渲染回调，使 onHudRender 每帧被调用。
     * 【被谁使用】仅被 CstmmClient#onInitializeClient 调用一次。
     */
    public static void init() {
        HudRenderCallback.EVENT.register(INSTANCE);
    }

    /**
     * 【作用】每帧渲染对战信息面板：按淡入淡出系数绘制背景、边框、三行文本与红蓝击杀占比条。
     * 【被谁使用】Fabric HudRenderCallback 事件（init 注册后由渲染循环每帧回调）。
     */
    @Override
    public void onHudRender(DrawContext context, RenderTickCounter tickCounter) {
        // 未与服务端握手成功时不渲染 HUD
        if (!ClientHandshakeState.isHandshaked()) return;

        MinecraftClient client = MinecraftClient.getInstance();
        // 【作用】按是否在对局中逐帧调整透明度，实现 HUD 淡入/淡出
        if (client.player == null || !inGame) {
            // 不在游戏中，淡出
            if (fadeAlpha > 0) {
                fadeAlpha = Math.max(0, fadeAlpha - 0.02f);
            }
            if (fadeAlpha <= 0) return;
        } else {
            // 在游戏中，淡入
            fadeAlpha = Math.min(1.0f, fadeAlpha + 0.02f);
        }

        int screenWidth = client.getWindow().getScaledWidth();

        TextRenderer textRenderer = client.textRenderer;

        // 计算位置（屏幕中央上方）
        int centerX = screenWidth / 2;
        int topY = 20;

        // 绘制背景条（半透明）
        int bgWidth = 220;
        int bgHeight = 70;
        int bgX = centerX - bgWidth / 2;
        int bgY = topY - 10;

        // 半透明背景（淡入淡出时同步变化）
        context.fill(bgX, bgY, bgX + bgWidth, bgY + bgHeight, applyFade(0x88000000));

        // 边框
        context.drawBorder(bgX, bgY, bgWidth, bgHeight, applyFade(0x44FFFFFF));

        int alpha = (int)(fadeAlpha * 255);
        int textColor = 0xFFFFFF | (alpha << 24);
        int goldColor = 0xFFAA00 | (alpha << 24);

        // 第一行：地图名
        String mapDisplay = mapName.isEmpty() ? "等待匹配..." : "⚔ " + mapName;
        Text mapText = Text.literal(mapDisplay);
        int mapWidth = textRenderer.getWidth(mapText);
        context.drawText(textRenderer, mapText, centerX - mapWidth / 2, topY, goldColor, true);

        int secondLineY = topY + 14;

        // 第二行：击杀数
        String killsText = "§c" + redKills + " §7vs §9" + blueKills;
        Text kills = Text.literal(killsText);
        int killsWidth = textRenderer.getWidth(kills);
        context.drawText(textRenderer, kills, centerX - killsWidth / 2, secondLineY, textColor, true);

        // 第三行：剩余时间（仅计时模式）
        if (remainingSeconds > 0) {
            int thirdLineY = secondLineY + 14;
            String timeStr = formatTime(remainingSeconds);
            Text timeText = Text.literal("⏱ " + timeStr);
            int timeWidth = textRenderer.getWidth(timeText);
            context.drawText(textRenderer, timeText, centerX - timeWidth / 2, thirdLineY, goldColor, true);
        }

        // 绘制红蓝队小图标（颜色条）
        int barWidth = 80;
        int barHeight = 4;
        int barY = topY + 50;

        // 红队条（左半，从左向右填充，占比越高越满）
        context.fill(centerX - barWidth, barY, centerX, barY + barHeight, applyFade(0x66FF5555));
        // 蓝队条（右半，从右向左填充，占比越高越满）
        context.fill(centerX, barY, centerX + barWidth, barY + barHeight, applyFade(0x665555FF));

        // 击杀数比例条：红蓝两侧对称，各自按本队击杀占比填充
        int total = redKills + blueKills;
        if (total > 0) {
            int redWidth = (int) ((float) redKills / total * barWidth);
            int blueWidth = (int) ((float) blueKills / total * barWidth);
            if (redWidth > 0) {
                context.fill(centerX - barWidth, barY, centerX - barWidth + redWidth, barY + barHeight, applyFade(0xEEFF3333));
            }
            if (blueWidth > 0) {
                context.fill(centerX + barWidth - blueWidth, barY, centerX + barWidth, barY + barHeight, applyFade(0xEE3333FF));
            }
        }
    }

    /** 将颜色的 alpha 通道按 fadeAlpha 缩放，使背景/边框/色条与文字同步淡入淡出 */
    private static int applyFade(int argb) {
        int a = (int) (((argb >>> 24) & 0xFF) * fadeAlpha);
        return (argb & 0xFFFFFF) | (a << 24);
    }

    // 将秒数格式化为 "分:秒"（两位补零）
    private static String formatTime(int seconds) {
        int minutes = seconds / 60;
        int secs = seconds % 60;
        return String.format("%02d:%02d", minutes, secs);
    }

    // ============ 数据更新方法 ============

    /**
     * 【作用】整体覆盖写入服务端推送的 HUD 对战数据（地图名/击杀数/剩余时间/对局状态）。
     * 【被谁使用】仅被 ClientNetworkHandler 的 HudDataPayload 接收器调用。
     */
    public static void updateData(String name, int red, int blue, int time, boolean game) {
        mapName = name;
        redKills = red;
        blueKills = blue;
        remainingSeconds = time;
        inGame = game;
    }

    /**
     * 【作用】断线时清空 HUD 数据并重置淡出动画状态。
     * 【被谁使用】仅被 ClientNetworkHandler 的 DISCONNECT 回调调用。
     */
    public static void reset() {
        mapName = "";
        redKills = 0;
        blueKills = 0;
        remainingSeconds = 0;
        inGame = false;
        fadeAlpha = 0;
    }

    // 是否处于对局中（当前无调用方，预留查询接口）
    public static boolean isInGame() {
        return inGame;
    }
}