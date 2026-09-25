package cn.woshiikun_1145.mcmod.choco.cstmm.client.screen;

import cn.woshiikun_1145.mcmod.choco.cstmm.client.WhyYouClickThis;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/**
 * 【作用】"千万别点"触发后的遗言输入界面（不可取消）。
 * 【被谁使用】MatchMenuScreen#triggerSecretAction 打开本界面；CstmmClient 每 tick 调 throwIfCrashPending 触发崩溃。
 * 任何离开本界面的方式——"发送遗言"按钮、"算了(并不能取消)"按钮、回车、ESC——
 * 都会先以玩家本人身份把遗言发送到聊天栏（等效玩家主动发送，无遗言则不发），
 * 随后抛出 WhyYouClickThis 使游戏崩溃（遗言随异常消息进入崩溃报告）。
 *
 * 防绕过设计：
 * - 崩溃挂起状态是静态字段而非实例字段，且 {@link #removed()} 把"屏幕被强制替换"本身
 *   也视为触发——其他模组强制顶号（setScreen 把玩家顶回游戏界面 / 断线切屏）无法逃单；
 * - 抛出点在客户端 tick 主循环（CstmmClient 每 tick 调用 {@link #throwIfCrashPending()}），
 *   与当前屏幕无关，也不跨任何 GLFW 原生回调边界（在键盘/鼠标回调里直接 throw
 *   会被 IMBlocker 等 JNA 钩子吞成 WARNING）。
 */
@Environment(EnvType.CLIENT)
public class LastWordsScreen extends Screen {

    private static final int DIALOG_WIDTH = 260;
    private static final int DIALOG_HEIGHT = 120;

    /** 全局挂起的崩溃遗言：null = 无挂起。静态持有，屏幕实例被顶掉也不丢失 */
    private static String pendingLastWords = null;

    /** 崩溃触发尝试计数：第 1 次抛异常走原版崩溃流程；若被反崩溃模组（如 Not Enough Crashes）
     * 吞掉，第 2 次起直接 scheduleStop() 强制退出，保证游戏必死且不会每 tick 刷异常 */
    private static int crashAttempts = 0;

    private TextFieldWidget input;

    public LastWordsScreen() {
        super(Text.literal("遗言"));
    }

    /**
     * 【作用】构建遗言输入框与两个按钮（均触发 send，"算了"并不能取消），
     * 对话框按屏幕中心 + 固定尺寸布局。
     */
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

    /** 发送遗言到聊天栏并标记崩溃；实际异常由 CstmmClient 的下一 tick 抛出（本界面唯一的离开方式） */
    private void send() {
        String words = input.getText().trim();
        if (!words.isEmpty()) {
            MatchMenuScreen.sendChatMessage(words);
        }
        markCrash(words);
    }

    /** 标记待触发崩溃（幂等：已挂起时不覆盖首次遗言） */
    private static void markCrash(String words) {
        if (pendingLastWords == null) {
            pendingLastWords = words.isEmpty() ? "（一言不发，无话可说）" : words;
        }
    }

    /**
     * 由 CstmmClient 在每客户端 tick 最先调用：挂起即抛出 WhyYouClickThis。
     * tick 在游戏主循环内执行，与当前屏幕/焦点无关，任何输入钩子模组都无法拦截。
     * 若异常被反崩溃模组吞掉（游戏未死），第二次起改用 scheduleStop() 强制干净退出，
     * 避免"每 tick 重抛异常"砖化客户端并刷爆日志。
     */
    public static void throwIfCrashPending() {
        if (pendingLastWords != null) {
            if (crashAttempts++ > 0) {
                MinecraftClient.getInstance().scheduleStop();
            }
            throw new WhyYouClickThis("玩家点了\"千万别点\"并留下遗言: " + pendingLastWords);
        }
    }

    @Override
    public void removed() {
        super.removed();
        // 屏幕被外部强制替换（其他模组顶号 / 断线切屏）同样视为触发，防止顶掉遗言界面逃单
        markCrash("");
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

    // 不暂停游戏：服务器界面惯例（单人打开时世界继续运行）
    @Override
    public boolean shouldPause() {
        return false;
    }
}
