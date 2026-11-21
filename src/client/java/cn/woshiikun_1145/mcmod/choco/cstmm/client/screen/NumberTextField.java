package cn.woshiikun_1145.mcmod.choco.cstmm.client.screen;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/**
 * 【作用】只允许输入数字（可带负号）的输入框（自 ConfigScreen 内部类提取，逻辑逐字保留）。
 * 【被谁使用】仅 ConfigScreen 使用（目标击杀数/时长/人数/时间参数/边界坐标/出生点坐标/价格等数字字段）。
 */
class NumberTextField extends TextFieldWidget {
    // 【作用】构造时设置文本过滤器：仅允许空串或可选负号开头的纯数字
    public NumberTextField(int x, int y, int width, int height, Text text) {
        super(MinecraftClient.getInstance().textRenderer, x, y, width, height, text);
        this.setTextPredicate(s -> s.isEmpty() || s.matches("-?\\d*"));
    }
}

/**
 * 【作用】只允许输入小数/整数（可带负号）的输入框（爆炸模型参数等 double 字段用）。
 * 【被谁使用】仅 ConfigScreen 使用（C4 爆炸模型：衰减距离/伤害/开口面积/背对减伤）。
 *         （原独立类 DecimalTextField 已并入本文件：NumberTextField 的简单变体，
 *         继承后放宽过滤器为整数/小数，构造行为与原类逐字等价。）
 */
final class DecimalTextField extends NumberTextField {
    // 【作用】构造时设置文本过滤器：仅允许空串或可选负号开头的整数/小数
    public DecimalTextField(int x, int y, int width, int height, Text text) {
        super(x, y, width, height, text);
        this.setTextPredicate(s -> s.isEmpty() || s.matches("-?\\d*(\\.\\d*)?"));
    }
}
