package cn.woshiikun_1145.mcmod.choco.cstmm.client.grenade.input;

import cn.woshiikun_1145.mcmod.choco.cstmm.entity.GrenadeType;
import cn.woshiikun_1145.mcmod.choco.cstmm.item.CounterStrikeGrenadeItem;
import cn.woshiikun_1145.mcmod.choco.cstmm.network.payload.GrenadePayloads.GrenadeThrownPayload;
import cn.woshiikun_1145.mcmod.choco.cstmm.util.GrenadeMath;
import cn.woshiikun_1145.mcmod.choco.cstmm.util.GrenadeSettings;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import org.lwjgl.glfw.GLFW;

import java.time.Duration;
import java.time.Instant;

/**
 * 【作用】手雷投掷输入状态机（1:1 移植自 CounterStrikeGrenades 的 ThrowActionHandler）：
 *         每 tick 轮询 GLFW 鼠标按键；按"当前按住组合 → 目标投掷速度"做平滑过渡
 *         （THROW_TYPE_TRANSIENT_TIME_MS 内线性插值），松开时按瞬时速度发起投掷：
 *         左键=强投（1.3）、右键=弱投（0.4）、双键=中投（1.0）；
 *         速度 = (玩家速度×speedFactor + 视线方向×throwSpeed).length()，
 *         投掷后本地物品进入冷却并发送 GrenadeThrownPayload（C2S）。
 * 【被谁使用】CstmmClient（register 挂 ClientTickEvents.END_CLIENT_TICK）；
 *           服务端 NetworkHandler.handleGrenadeThrown（接收后生成实体）。
 */
@Environment(EnvType.CLIENT)
public final class ThrowActionHandler {

    private ThrowActionHandler() {
    }

    private static Instant grenadeLastThrow = Instant.now();
    private static boolean primaryButtonPressed = false;
    private static boolean secondaryButtonPressed = false;
    private static Double throwSpeedTransientTarget = null;
    private static Double throwSpeedTransientOrigin = null;
    private static Double currentThrowSpeed = null;
    private static Instant transientBeginTime = Instant.now();
    private static int previousSlot = -1;
    private static boolean screenOpened = false;
    private static boolean buttonPressedWhenScreenOpen = false;

    // 【作用】挂载客户端 tick（END 语义与原 mod 一致）
    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(ThrowActionHandler::onClientTick);
    }

    // 【作用】状态机主流程：屏幕打开处理 → 冷却门 → 按键组合 → 平滑过渡 → 松开投掷
    private static void onClientTick(MinecraftClient client) {
        boolean leftPressed = getButtonState(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        boolean rightPressed = getButtonState(GLFW.GLFW_MOUSE_BUTTON_RIGHT);

        // 【作用】打开任何界面（背包/聊天/菜单）时清理蓄力进度，关屏瞬间的按键也不触发投掷
        if (client.currentScreen != null) {
            screenOpened = true;
            if (primaryButtonPressed || secondaryButtonPressed) {
                cleanProgress();
                buttonPressedWhenScreenOpen = true;
            }
            return;
        } else if (screenOpened) {
            buttonPressedWhenScreenOpen = leftPressed || rightPressed;
            screenOpened = false;
            return;
        }
        if (buttonPressedWhenScreenOpen) {
            if (!(leftPressed || rightPressed)) {
                buttonPressedWhenScreenOpen = false;
            }
            return;
        }

        ClientPlayerEntity player = client.player;
        if (player == null) {
            return;
        }
        int selectedSlot = player.getInventory().selectedSlot;
        ItemStack itemInHand = player.getMainHandStack();

        // 【作用】仅手持手雷且槽位未切换时推进状态机（切槽即清进度）
        if (itemInHand.getItem() instanceof CounterStrikeGrenadeItem && previousSlot == selectedSlot) {
            if (Duration.between(grenadeLastThrow, Instant.now()).toMillis()
                    > GrenadeSettings.GRENADE_THROW_COOLDOWN_MS) {
                // 【作用】按键组合变化 → 设置新的平滑过渡目标速度（原 mod 状态机 1:1）
                if (!primaryButtonPressed && !secondaryButtonPressed) {
                    if (leftPressed && rightPressed) {
                        setNewTransientTarget(GrenadeSettings.THROW_SPEED_MODERATE, GrenadeSettings.THROW_SPEED_MODERATE);
                    } else if (leftPressed) {
                        setNewTransientTarget(GrenadeSettings.THROW_SPEED_STRONG, GrenadeSettings.THROW_SPEED_STRONG);
                    } else if (rightPressed) {
                        setNewTransientTarget(GrenadeSettings.THROW_SPEED_WEAK, GrenadeSettings.THROW_SPEED_WEAK);
                    }
                } else if (primaryButtonPressed && !secondaryButtonPressed) {
                    if (leftPressed && rightPressed) {
                        setNewTransientTarget(GrenadeSettings.THROW_SPEED_MODERATE, null);
                    } else if (!leftPressed && rightPressed) {
                        setNewTransientTarget(GrenadeSettings.THROW_SPEED_WEAK, null);
                    }
                } else if (!primaryButtonPressed && secondaryButtonPressed) {
                    if (leftPressed && rightPressed) {
                        setNewTransientTarget(GrenadeSettings.THROW_SPEED_MODERATE, null);
                    } else if (leftPressed && !rightPressed) {
                        setNewTransientTarget(GrenadeSettings.THROW_SPEED_STRONG, null);
                    }
                } else {
                    if (leftPressed && !rightPressed) {
                        setNewTransientTarget(GrenadeSettings.THROW_SPEED_STRONG, null);
                    } else if (!leftPressed && rightPressed) {
                        setNewTransientTarget(GrenadeSettings.THROW_SPEED_WEAK, null);
                    }
                }
                updateCurrentSpeed();
                // 【作用】双键都已松开 → 按当前瞬时速度投掷（原 mod 仅在从按下到松开瞬间触发）
                if (!leftPressed && !rightPressed) {
                    if (primaryButtonPressed || secondaryButtonPressed) {
                        GrenadeType type = ((CounterStrikeGrenadeItem) itemInHand.getItem()).grenadeType;
                        throwAction(currentThrowSpeed != null ? currentThrowSpeed : 0.0, type, player);
                        setItemCooldown(player);
                        cleanProgress();
                        grenadeLastThrow = Instant.now();
                    }
                }
                primaryButtonPressed = leftPressed;
                secondaryButtonPressed = rightPressed;
            }
        } else {
            primaryButtonPressed = false;
            secondaryButtonPressed = false;
            cleanProgress();
        }
        previousSlot = selectedSlot;
    }

    // 【作用】清空蓄力/过渡状态
    private static void cleanProgress() {
        primaryButtonPressed = false;
        secondaryButtonPressed = false;
        currentThrowSpeed = null;
        throwSpeedTransientTarget = null;
        throwSpeedTransientOrigin = null;
    }

    // 【作用】设置新的过渡目标（origin=null 时从当前瞬时速度出发）
    private static void setNewTransientTarget(double speedTarget, Double speedOrigin) {
        throwSpeedTransientOrigin = speedOrigin != null ? speedOrigin : currentThrowSpeed;
        throwSpeedTransientTarget = speedTarget;
        transientBeginTime = Instant.now();
    }

    // 【作用】按过渡时长线性插值出当前瞬时速度
    private static void updateCurrentSpeed() {
        if (throwSpeedTransientOrigin == null || throwSpeedTransientTarget == null) {
            return;
        }
        double progress = Math.min(1.0, Duration.between(transientBeginTime, Instant.now()).toMillis()
                / (double) GrenadeSettings.THROW_TYPE_TRANSIENT_TIME_MS);
        currentThrowSpeed = GrenadeMath.linearInterpolate(throwSpeedTransientOrigin, throwSpeedTransientTarget, progress);
    }

    // 【作用】读 GLFW 鼠标按键状态
    private static boolean getButtonState(int button) {
        long window = MinecraftClient.getInstance().getWindow().getHandle();
        return GLFW.glfwGetMouseButton(window, button) == GLFW.GLFW_PRESS;
    }

    // 【作用】发起投掷：speed = (玩家速度×playerSpeedFactor + 视线×throwSpeed).length()，
    //         发送 GrenadeThrownPayload（服务端校验手持后生成实体并扣减）
    private static void throwAction(double throwSpeed, GrenadeType grenadeType, ClientPlayerEntity player) {
        double speedFactor = (throwSpeed - GrenadeSettings.THROW_SPEED_WEAK)
                / (GrenadeSettings.THROW_SPEED_STRONG - GrenadeSettings.THROW_SPEED_WEAK);
        double playerSpeedFactor = GrenadeMath.linearInterpolate(
                GrenadeSettings.PLAYER_SPEED_FACTOR_WEAK,
                GrenadeSettings.PLAYER_SPEED_FACTOR_STRONG,
                speedFactor);

        double speed = player.getVelocity().multiply(playerSpeedFactor)
                .add(player.getRotationVector().normalize().multiply(GrenadeMath.linearInterpolate(
                        GrenadeSettings.THROW_SPEED_WEAK,
                        GrenadeSettings.THROW_SPEED_STRONG,
                        speedFactor)))
                .length();

        ClientPlayNetworking.send(new GrenadeThrownPayload(
                speed, grenadeType, player.getEyePos(), player.getPitch(), player.getYaw(),
                readDecoyCustomSound(player.getMainHandStack(), grenadeType)));
    }

    // 【作用】诱饵弹自定义音效（阶段 3）：从物品 "DecoySound" 自定义数据组件读取
    //         （原版读 NBT tag，移植为 1.21.1 自定义数据组件），非诱饵弹恒为 null
    private static String readDecoyCustomSound(ItemStack stack, GrenadeType grenadeType) {
        if (grenadeType != GrenadeType.DECOY_GRENADE) {
            return null;
        }
        NbtComponent customData = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (customData == null) {
            return null;
        }
        NbtCompound tag = customData.copyNbt();
        return tag.contains("DecoySound", NbtElement.STRING_TYPE) ? tag.getString("DecoySound") : null;
    }

    // 【作用】本地物品冷却（全部手雷槽位同置，与原 mod 一致；服务端另有冷却镜像兜底）
    private static void setItemCooldown(ClientPlayerEntity player) {
        int cooldownTicks = (int) (GrenadeSettings.GRENADE_THROW_COOLDOWN_MS / 1000.0 * 20);
        for (ItemStack stack : player.getInventory().main) {
            if (stack.getItem() instanceof CounterStrikeGrenadeItem) {
                player.getItemCooldownManager().set(stack.getItem(), cooldownTicks);
            }
        }
    }
}
