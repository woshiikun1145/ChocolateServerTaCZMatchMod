package cn.woshiikun_1145.mcmod.choco.cstmm.client.screen;

import cn.woshiikun_1145.mcmod.choco.cstmm.client.WhyYouClickThis;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/**
 * "千万别点"触发后的遗言输入界面（不可取消）。
 * 任何离开本界面的方式——"发送遗言"按钮、"算了(并不能取消)"按钮、回车、ESC——
 * 都会先以玩家本人身份把遗言发送到聊天栏（等效玩家主动发送，无遗言则不发），
 * 随后抛出 WhyYouClickThis 使游戏崩溃（遗言随异常消息进入崩溃报告）。
 */
@Environment(EnvType.CLIENT)
public class LastWordsScreen extends Screen {

    private static final int DIALOG_WIDTH = 260;
    private static final int DIALOG_HEIGHT = 120;

    private TextFieldWidget input;

    public LastWordsScreen() {
        super(Text.literal("遗言"));
    }

    @Override
    protected void init() {
        super.init();

        int cx = this.width / 2;
        int x = cx - DIALOG_WIDTH / 2;
        int y = this.height / 2 - DIALOG_HEIGHT / 2;

        input = new TextFieldWidget(this.textRenderer, x + 15, y + 45, DIALOG_WIDTH - 30, 18,
                Text.literal("遗言"));
        input.setMaxLength(256);
        this.addDrawableChild(input);
        this.setFocused(input);

        this.addDrawableChild(ButtonWidget.builder(
                        Text.literal("§4发送遗言"),
                        button -> send())
                .dimensions(cx - 101, y + 75, 100, 20).build());
        // 看起来能取消，实际同样发送遗言并崩溃——劝退是不存在的
        this.addDrawableChild(ButtonWidget.builder(
                        Text.literal("§7算了(并不能取消)"),
                        button -> send())
                .dimensions(cx + 1, y + 75, 100, 20).build());
    }

    /** 自绘遮罩，禁用原版模糊：super.render 会调用 renderBackground，
     * 原版默认实现在此之前绘制的内容（对话框提示文字）会被模糊糊掉 */
    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        // 留空：遮罩在 render 中自绘，不走原版背景模糊
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // 半透明暗红遮罩（自绘，无原版模糊）
        context.fill(0, 0, this.width, this.height, 0xC8300000);
        int x = this.width / 2 - DIALOG_WIDTH / 2;
        int y = this.height / 2 - DIALOG_HEIGHT / 2;
        context.fill(x, y, x + DIALOG_WIDTH, y + DIALOG_HEIGHT, 0xFF212121);
        context.drawBorder(x, y, DIALOG_WIDTH, DIALOG_HEIGHT, 0xFF8B0000);
        context.drawCenteredTextWithShadow(this.textRenderer, "§4⚠ 你做了不该做的事 ⚠",
                this.width / 2, y + 12, 0xFFFFFF);
        context.drawCenteredTextWithShadow(this.textRenderer, "§7发送遗言后立即生效，不可撤销",
                this.width / 2, y + 26, 0xAAAAAA);

        super.render(context, mouseX, mouseY, delta);
    }

    /** 发送遗言到聊天栏，随后抛出异常使游戏崩溃（本界面唯一的离开方式） */
    private void send() {
        String words = input.getText().trim();
        if (!words.isEmpty()) {
            MatchMenuScreen.sendChatMessage(words);
        }
        throw new WhyYouClickThis("玩家点了\"千万别点\"并留下遗言: "
                + (words.isEmpty() ? "（一言不发，无话可说）" : words));
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // 回车 / ESC 均视为发送遗言——按 ESC 退出同样无法阻止崩溃
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER
                || keyCode == GLFW.GLFW_KEY_ESCAPE) {
            send();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
