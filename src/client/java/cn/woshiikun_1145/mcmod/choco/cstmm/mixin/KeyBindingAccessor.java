package cn.woshiikun_1145.mcmod.choco.cstmm.mixin;

import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * 【作用】KeyBinding 访问器：暴露原版键位注册表（KEYS_BY_ID）、绑定按键（boundKey）与
 *         按压计数（timesPressed），供 KeyBindingMixin 实现同键多绑定的冲突修复。
 * 【被谁使用】KeyBindingMixin（按键事件全量分发）。仅客户端。
 */
@Mixin(KeyBinding.class)
public interface KeyBindingAccessor {

    /** 原版全部已注册键位（id → 键位实例）。 */
    @Accessor("KEYS_BY_ID")
    static Map<String, KeyBinding> cstmm$getKeysById() {
        throw new AssertionError();
    }

    /** 键位当前绑定的按键。 */
    @Accessor("boundKey")
    InputUtil.Key cstmm$getBoundKey();

    /** 键位按压计数（wasPressed 消费）。 */
    @Accessor("timesPressed")
    int cstmm$getTimesPressed();

    /** 键位按压计数写入。 */
    @Accessor("timesPressed")
    void cstmm$setTimesPressed(int value);
}
