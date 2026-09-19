package cn.woshiikun_1145.mcmod.choco.cstmm.client.screen;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * 全局弹窗界面：服务端弹窗到达时若当前不在匹配菜单/配置界面，用本界面承载弹窗
 * （替代旧版"回退聊天栏"——弹窗语义不应随当前界面缺失而丢失）。
 * 样式与 MatchMenuScreen 内嵌弹窗一致（遮罩 + 对话框 + 确定按钮），
 * 确定后返回打开弹窗前的界面（previous 为 null 则回到游戏画面）。
 */
@Environment(EnvType.CLIENT)
public class PopupScreen extends Screen {

    private final String message;
    /** 打开弹窗前的界面（可为 null = 游戏画面），确定后返回 */
    private final Screen previous;

    /** "确定"按钮区域（render 每帧计算，mouseClicked 使用） */
    private int[] btnRect;

    public PopupScreen(String message, Screen previous) {
        super(Text.literal("提示"));
        this.message = message;
        this.previous = previous;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        // 留空：不绘制原版模糊背景（否则弹窗文字会被糊掉，同 MatchMenuScreen）
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, this.width, this.height, 0xA0000000);

        int boxW = Math.min(300, this.width - 40);
        List<OrderedText> lines = this.textRenderer.wrapLines(Text.literal(message), boxW - 24);
        int boxH = 52 + lines.size() * 12;
        int bx = (this.width - boxW) / 2;
        int by = (this.height - boxH) / 2;
        context.fill(bx, by, bx + boxW, by + boxH, 0xFF212121);
        context.drawBorder(bx, by, boxW, boxH, 0xFFDAA520);
        context.drawCenteredTextWithShadow(this.textRenderer, "§6提示", this.width / 2, by + 8, 0xFFFFFF);
        int ly = by + 26;
        for (OrderedText line : lines) {
            context.drawTextWithShadow(this.textRenderer, line, bx + 12, ly, 0xFFE0E0E0);
            ly += 12;
        }
        int btnW = 90, btnH = 20;
        int btnX = bx + (boxW - btnW) / 2;
        int btnY = by + boxH - btnH - 10;
        boolean hover = mouseX >= btnX && mouseX <= btnX + btnW && mouseY >= btnY && mouseY <= btnY + btnH;
        context.fill(btnX, btnY, btnX + btnW, btnY + btnH, hover ? 0xFF4E4E4E : 0xFF3A3A3A);
        context.drawBorder(btnX, btnY, btnW, btnH, 0xFF666666);
        context.drawCenteredTextWithShadow(this.textRenderer, "§f确定", btnX + btnW / 2, btnY + 6, 0xFFFFFF);
        btnRect = new int[]{btnX, btnY, btnW, btnH};
    }

    /** 关闭弹窗并回到打开前的界面（null = 游戏画面）；覆盖 Screen.close() 使 ESC 默认关闭路径也返回原界面 */
    @Override
    public void close() {
        if (this.client != null) this.client.setScreen(previous);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // 仅 ESC/回车确认关闭；其余按键全吞（弹窗期间不应有穿透交互，同 MatchMenuScreen 弹窗分支）
        if (keyCode == GLFW.GLFW_KEY_ESCAPE || keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            close();
        }
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 仅"确定"按钮关闭，其余点击全吞
        if (btnRect != null) {
            int[] r = btnRect;
            if (mouseX >= r[0] && mouseX <= r[0] + r[2] && mouseY >= r[1] && mouseY <= r[1] + r[3]) {
                close();
            }
        }
        return true;
    }
}
