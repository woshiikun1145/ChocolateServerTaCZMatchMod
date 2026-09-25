package cn.woshiikun_1145.mcmod.choco.cstmm.client;

import cn.woshiikun_1145.mcmod.choco.cstmm.Cstmm;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.hud.HudOverlay;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.network.ClientNetworkHandler;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.screen.LastWordsScreen;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.screen.MatchMenuScreen;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.screen.ShopScreen;
import cn.woshiikun_1145.mcmod.choco.cstmm.network.payload.MatchActionPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 【作用】客户端模组主入口：初始化时注册网络包处理器、HUD 渲染器与 4 个游戏内按键，
 *         并在每客户端 tick 轮询按键，驱动打开菜单/商城界面与发送投票包。
 * 【被谁使用】由 fabric.mod.json 客户端入口点声明，Fabric Loader 在客户端启动时调用；
 *             MatchMenuScreen#init 读取本类的 keyOpenMenu/keyOpenShop 显示按键提示。
 */
@Environment(EnvType.CLIENT)
public class CstmmClient implements ClientModInitializer {
    // 客户端日志器，供本类及客户端其他类输出日志
    public static final Logger LOGGER = LoggerFactory.getLogger(Cstmm.MOD_ID + "-client");

    // 打开对战菜单按键（默认分号），本类 tick 触发界面，MatchMenuScreen#init 用于显示按键提示
    public static KeyBinding keyOpenMenu;
    // 打开商城按键（默认单引号），本类 tick 触发 ShopScreen
    public static KeyBinding keyOpenShop;
    // 投票赞成按键（默认 F7），本类 tick 触发发送 VOTE_YES 动作包
    public static KeyBinding keyVoteYes;
    // 投票反对按键（默认 F8），本类 tick 触发发送 VOTE_NO 动作包
    public static KeyBinding keyVoteNo;

    /**
     * 【作用】Fabric 客户端初始化回调：依次注册网络包处理器、按键绑定与 HUD 覆盖层。
     * 【被谁使用】Fabric Loader 依据 fabric.mod.json 客户端入口点在客户端启动时调用，仅此一处。
     */
    @Override
    public void onInitializeClient() {
        LOGGER.info("[CSTMM - Client] Initializing client...");

        ClientNetworkHandler.register();
        registerKeyBindings();
        HudOverlay.init();

        LOGGER.info("[CSTMM - Client] Client initialized successfully!");
    }

    /**
     * 【作用】注册打开菜单/商城、投票赞成/反对 4 个按键绑定，并挂每客户端 tick 轮询回调处理按键。
     * 【被谁使用】仅被本类 onInitializeClient 调用。
     */
    private void registerKeyBindings() {
        keyOpenMenu = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.cstmm.open_menu",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_SEMICOLON,
                "category.cstmm"
        ));

        keyOpenShop = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.cstmm.open_shop",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_APOSTROPHE,
                "category.cstmm"
        ));

        keyVoteYes = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.cstmm.vote_yes",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_F7,
                "category.cstmm"
        ));

        keyVoteNo = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.cstmm.vote_no",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_F8,
                "category.cstmm"
        ));

        // 【作用】每客户端 tick 末尾轮询按键：先检查挂起的崩溃提示，再按握手状态决定是否响应按键
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // "千万别点"挂起崩溃最先检查：tick 在主循环内且与当前屏幕无关，
            // 被其他模组顶号/断线切屏也无法绕过（必须在 player 判空之前）
            LastWordsScreen.throwIfCrashPending();

            if (client == null || client.player == null) return;

            // 未握手成功时禁用全部功能
            if (!ClientHandshakeState.isHandshaked()) {
                if (keyOpenMenu.wasPressed() || keyOpenShop.wasPressed()
                        || keyVoteYes.wasPressed() || keyVoteNo.wasPressed()) {
                    client.player.sendMessage(Text.literal("§c[CSTMM] 未与服务端握手成功，功能不可用"), false);
                }
                return;
            }

            if (keyOpenMenu.wasPressed()) {
                if (client.currentScreen == null) {
                    client.setScreen(new MatchMenuScreen());
                } else if (client.currentScreen instanceof MatchMenuScreen) {
                    client.currentScreen.close();
                }
                return;
            }

            if (keyOpenShop.wasPressed()) {
                if (client.currentScreen == null) {
                    client.setScreen(new ShopScreen());
                } else if (client.currentScreen instanceof ShopScreen) {
                    client.currentScreen.close();
                }
                return;
            }

            // 【作用】按下投票键时构造对应投票动作包并直接发送给服务端
            if (keyVoteYes.wasPressed() && client.player != null) {
                MatchActionPayload payload = new MatchActionPayload(
                        MatchActionPayload.ActionType.VOTE_YES, "", 0, ""
                );
                ClientPlayNetworking.send(payload);
                return;
            }

            if (keyVoteNo.wasPressed() && client.player != null) {
                MatchActionPayload payload = new MatchActionPayload(
                        MatchActionPayload.ActionType.VOTE_NO, "", 0, ""
                );
                ClientPlayNetworking.send(payload);
            }
        });

        LOGGER.info("[CSTMM - Client] Registered key bindings");
    }
}