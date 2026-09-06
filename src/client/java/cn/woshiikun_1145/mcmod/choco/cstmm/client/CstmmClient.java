package cn.woshiikun_1145.mcmod.choco.cstmm.client;

import cn.woshiikun_1145.mcmod.choco.cstmm.Cstmm;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.hud.HudOverlay;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.network.ClientNetworkHandler;
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

@Environment(EnvType.CLIENT)
public class CstmmClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger(Cstmm.MOD_ID + "-client");

    public static KeyBinding keyOpenMenu;
    public static KeyBinding keyOpenShop;
    public static KeyBinding keyVoteYes;
    public static KeyBinding keyVoteNo;

    @Override
    public void onInitializeClient() {
        LOGGER.info("[CSTMM - Client] Initializing client...");

        ClientNetworkHandler.register();
        registerKeyBindings();
        HudOverlay.init();

        LOGGER.info("[CSTMM - Client] Client initialized successfully!");
    }

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

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client == null || client.player == null) return;

            // 未握手成功时禁用全部功能
            if (!ClientHandshakeState.isHandshaked()) {
                if (keyOpenMenu.wasPressed() || keyOpenShop.wasPressed()
                        || keyVoteYes.wasPressed() || keyVoteNo.wasPressed()) {
                    client.player.sendMessage(Text.literal("§c[模组] 未与服务端握手成功，功能不可用"), false);
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