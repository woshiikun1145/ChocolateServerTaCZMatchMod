package cn.woshiikun_1145.mcmod.choco.cstmm.client.screen;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Drawable;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

/**
 * 【作用】配置界面静态文字标签（自 ConfigScreen 内部类提取，逻辑逐字保留）。
 * 【被谁使用】仅 ConfigScreen 使用（各字段标签/表头/灰色提示小字，加入 rightScrollables 随区滚动）。
 */
final class LabelWidget implements Drawable {
    private final int x, y;
    private final Text text;

    /**
     * 默认与 18 像素高的输入框文字垂直对齐
     * （输入框内部文字绘制在 y + (18 - 8) / 2 处）
     */
    public LabelWidget(int x, int y, Text text) {
        this(x, y, text, 18);
    }

    /**
     * alignHeight: 标签要与其对齐的控件高度
     * （输入框传 18，按钮传 20，独立标题传 9 表示不偏移）
     */
    public LabelWidget(int x, int y, Text text, int alignHeight) {
        this.x = x;
        this.y = y + (alignHeight - 8) / 2;
        this.text = text;
    }

    // 【作用】绘制标签文字（灰色带阴影，使用全局字体渲染器）
    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.drawText(MinecraftClient.getInstance().textRenderer, text, x, y, 0xAAAAAA, true);
    }
}
