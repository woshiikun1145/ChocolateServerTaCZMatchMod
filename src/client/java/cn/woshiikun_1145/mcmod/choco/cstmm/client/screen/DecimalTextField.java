package cn.woshiikun_1145.mcmod.choco.cstmm.client.screen;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/**
 * 【作用】只允许输入小数/整数（可带负号）的输入框（爆炸模型参数等 double 字段用）。
 * 【被谁使用】仅 ConfigScreen 使用（C4 爆炸模型：衰减距离/伤害/开口面积/背对减伤）。
 */
final class DecimalTextField extends TextFieldWidget {
    // 【作用】构造时设置文本过滤器：仅允许空串或可选负号开头的整数/小数
    public DecimalTextField(int x, int y, int width, int height, Text text) {
        super(MinecraftClient.getInstance().textRenderer, x, y, width, height, text);
        this.setTextPredicate(s -> s.isEmpty() || s.matches("-?\\d*(\\.\\d*)?"));
    }
}
