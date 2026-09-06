package cn.woshiikun_1145.mcmod.choco.cstmm.client.screen;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/**
 * 只允许输入数字（可带负号）的输入框（自 ConfigScreen 内部类提取，逻辑逐字保留）。
 */
final class NumberTextField extends TextFieldWidget {
    public NumberTextField(int x, int y, int width, int height, Text text) {
        super(MinecraftClient.getInstance().textRenderer, x, y, width, height, text);
        this.setTextPredicate(s -> s.isEmpty() || s.matches("-?\\d*"));
    }
}
