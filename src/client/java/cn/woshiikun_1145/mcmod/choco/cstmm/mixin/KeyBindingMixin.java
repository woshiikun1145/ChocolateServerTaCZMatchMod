package cn.woshiikun_1145.mcmod.choco.cstmm.mixin;

import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 【作用】原版键位冲突修复：KeyBinding.setKeyPressed/onKeyPressed 只通过 KEY_TO_BINDINGS
 *         （按键 → 单个绑定）分发按键事件——同一按键绑定多个动作时（如自定义安放/拆除键与
 *         原版使用键都绑右键），注册顺序靠后的键位覆盖靠前的，被覆盖者永远收不到按压状态
 *         （表现为"键位设置不生效"或"原版右键失效"）。
 *         本 mixin 改为遍历全部注册键位，所有绑定到该按键的键位同步按压/释放/计数，
 *         使自定义键与原版使用键绑同一按键时互不失效。
 * 【被谁使用】chocolateservertaczmatchmod.mixins.json（client 列表）。仅客户端。
 */
@Mixin(KeyBinding.class)
public abstract class KeyBindingMixin {

    @Inject(method = "setKeyPressed", at = @At("HEAD"), cancellable = true)
    private static void cstmm$setKeyPressedAll(InputUtil.Key key, boolean pressed, CallbackInfo ci) {
        if (cstmm$applyToAll(key, pressed, false)) {
            ci.cancel();
        }
    }

    @Inject(method = "onKeyPressed", at = @At("HEAD"), cancellable = true)
    private static void cstmm$onKeyPressedAll(InputUtil.Key key, CallbackInfo ci) {
        if (cstmm$applyToAll(key, true, true)) {
            ci.cancel();
        }
    }

    /**
     * 向所有绑定到 key 的键位分发按键事件：pressed=true 时按压，false 时释放；
     * countPress=true 时同时累加按压计数（等价原版 onKeyPressed，供 wasPressed 消费）。
     * @return 是否有键位命中（命中时取消原版的单绑定分发逻辑）
     */
    private static boolean cstmm$applyToAll(InputUtil.Key key, boolean pressed, boolean countPress) {
        boolean matched = false;
        for (KeyBinding binding : KeyBindingAccessor.cstmm$getKeysById().values()) {
            if (((KeyBindingAccessor) binding).cstmm$getBoundKey().equals(key)) {
                binding.setPressed(pressed);
                if (countPress) {
                    KeyBindingAccessor accessor = (KeyBindingAccessor) binding;
                    accessor.cstmm$setTimesPressed(accessor.cstmm$getTimesPressed() + 1);
                }
                matched = true;
            }
        }
        return matched;
    }
}
