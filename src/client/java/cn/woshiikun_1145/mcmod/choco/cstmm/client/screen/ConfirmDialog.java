package cn.woshiikun_1145.mcmod.choco.cstmm.client.screen;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/**
 * 配置界面内置确认对话框（自 ConfigScreen 提取，逻辑逐字保留）。
 * 在当前界面内弹出，不暂停游戏；弹出/关闭播放音符盒 harp 音效。
 * onConfirm 为 null 时为提示模式（仅“确定”按钮），否则为确认模式（是/否）。
 * 按钮为手动渲染 + 手动分发（不加入 Screen children），关闭界面重建不影响对话框状态。
 */
final class ConfirmDialog {
    private final Screen owner;
    /** 懒获取 textRenderer：Screen.textRenderer 在 init() 才赋值，构造期传入会是 null（曾致 NPE 崩溃） */
    private final java.util.function.Supplier<TextRenderer> textRendererSupplier;

    private boolean showingConfirm = false;
    private Text confirmTitle = null;
    private Text confirmMessage = null;
    private Runnable confirmAction = null;
    private ButtonWidget confirmYesBtn = null;
    private ButtonWidget confirmNoBtn = null;

    ConfirmDialog(Screen owner, java.util.function.Supplier<TextRenderer> textRendererSupplier) {
        this.owner = owner;
        this.textRendererSupplier = textRendererSupplier;
    }

    /** 每次使用时即时获取（init() 之后恒非 null） */
    private TextRenderer tr() {
        return textRendererSupplier.get();
    }

    boolean isOpen() {
        return showingConfirm;
    }

    void show(Text title, Text message, Runnable onConfirm) {
        this.confirmTitle = title;
        this.confirmMessage = message;
        this.confirmAction = onConfirm;
        this.showingConfirm = true;
        playNoteBlockSound(19); // 弹出：harp note 19

        int dialogWidth = 240;
        int centerX = owner.width / 2;
        int btnY = owner.height / 2 + dialogHeightFor(message) / 2 - 25;
        confirmYesBtn = ButtonWidget.builder(
                Text.literal(onConfirm == null ? "确定" : "是"),
                b -> {
                    // 先捕获回调再关闭弹窗：closeDialog() 会将 confirmAction 置空
                    Runnable action = confirmAction;
                    close();
                    if (action != null) action.run();
                }
        ).dimensions(centerX - dialogWidth / 2 + 15, btnY, 100, 20).build();
        if (onConfirm != null) {
            confirmNoBtn = ButtonWidget.builder(
                    Text.literal("否"),
                    b -> close()
            ).dimensions(centerX + dialogWidth / 2 - 115, btnY, 100, 20).build();
        } else {
            confirmNoBtn = null;
        }
    }

    /** 关闭对话框并播放关闭音效（harp note 12） */
    void close() {
        showingConfirm = false;
        confirmAction = null;
        owner.setFocused(null);
        playNoteBlockSound(12);
    }

    void render(DrawContext context, int mouseX, int mouseY, float delta) {
        TextRenderer textRenderer = tr();
        context.fill(0, 0, owner.width, owner.height, 0xC8000000);
        int dialogWidth = 240;
        int dialogHeight = dialogHeightFor(confirmMessage);
        int x = owner.width / 2 - dialogWidth / 2;
        int y = owner.height / 2 - dialogHeight / 2;
        context.fill(x, y, x + dialogWidth, y + dialogHeight, 0xFF212121);
        context.drawBorder(x, y, dialogWidth, dialogHeight, 0xFF555555);
        context.drawCenteredTextWithShadow(textRenderer, confirmTitle, owner.width / 2, y + 10, 0xFFFFFF);

        // 消息自动换行
        int lineY = y + 26;
        for (OrderedText line : textRenderer.wrapLines(confirmMessage, 220)) {
            context.drawTextWithShadow(textRenderer, line, x + 12, lineY, 0xFFAAAAAA);
            lineY += 12;
        }

        confirmYesBtn.render(context, mouseX, mouseY, delta);
        if (confirmNoBtn != null) confirmNoBtn.render(context, mouseX, mouseY, delta);
    }

    void handleMouseClicked(double mouseX, double mouseY, int button) {
        if (confirmYesBtn != null) confirmYesBtn.mouseClicked(mouseX, mouseY, button);
        if (confirmNoBtn != null) confirmNoBtn.mouseClicked(mouseX, mouseY, button);
    }

    void handleMouseReleased(double mouseX, double mouseY, int button) {
        if (confirmYesBtn != null) confirmYesBtn.mouseReleased(mouseX, mouseY, button);
        if (confirmNoBtn != null) confirmNoBtn.mouseReleased(mouseX, mouseY, button);
    }

    /** 对话框打开时屏蔽其他按键（如关闭界面的 ESC）；ESC 仅关闭对话框且不播放音效 */
    boolean handleKeyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            showingConfirm = false;
            confirmAction = null;
            owner.setFocused(null);
        }
        return true;
    }

    /**
     * 播放音符盒音效。
     * note 范围 0~24，pitch = 2^((note-12)/12)（note 12 为基准音高 1.0）
     */
    private void playNoteBlockSound(int note) {
        float pitch = (float) Math.pow(2.0, (note - 12) / 12.0);
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_HARP.value(), 1.0F, pitch);
        }
    }

    /** 根据消息行数计算对话框高度（消息按 220px 宽度自动换行） */
    private int dialogHeightFor(Text message) {
        int lines = tr().wrapLines(message, 220).size();
        return 62 + lines * 12;
    }
}
