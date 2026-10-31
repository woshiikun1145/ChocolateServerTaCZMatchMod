package cn.woshiikun_1145.mcmod.choco.cstmm.client;

import cn.woshiikun_1145.mcmod.choco.cstmm.Cstmm;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.hud.HudOverlay;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.network.ClientNetworkHandler;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.render.BombEntityRenderer;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.screen.LastWordsScreen;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.screen.MatchMenuScreen;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.screen.ShopScreen;
import cn.woshiikun_1145.mcmod.choco.cstmm.entity.BombEntity;
import cn.woshiikun_1145.mcmod.choco.cstmm.entity.ModEntities;
import cn.woshiikun_1145.mcmod.choco.cstmm.item.ModItems;
import cn.woshiikun_1145.mcmod.choco.cstmm.manager.BombInteractionManager;
import cn.woshiikun_1145.mcmod.choco.cstmm.network.payload.MatchActionPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.math.Box;
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
    // 安放 C4 按键（默认未绑定）：与原版使用键等效触发安放，键位在 控制 设置中自定义
    public static KeyBinding keyPlantBomb;
    // 拆除 C4 按键（默认未绑定）：与原版使用键等效触发拆除，键位在 控制 设置中自定义
    public static KeyBinding keyDefuseBomb;

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
        // C4 实体渲染器注册（绿色长方体占位外观）
        EntityRendererRegistry.register(ModEntities.C4, BombEntityRenderer::new);

        LOGGER.info("[CSTMM - Client] Client initialized successfully!");
    }

    // 爆破交互按键状态（按住状态翻转时才发包，避免每 tick 刷包）
    private static boolean plantHeld = false;
    private static boolean defuseHeld = false;
    // 安放中/安放完成后仍按着使用键 → 抑制拆除触发，直到松键后重新按下
    // （修复：安放完成瞬间手上的 C4 消失、附近出现炸弹实体，若仍按着右键会立刻误发拆除 start）
    private static boolean suppressDefuseUntilRelease = false;

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

        // 安放/拆除 C4 按键：默认未绑定（UNKNOWN_KEY），与原版使用键（右键）任一按住即触发，
        // 键位可在 控制 → CSTMM 匹配系统 分类下自定义
        keyPlantBomb = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.cstmm.plant_bomb",
                InputUtil.Type.KEYSYM,
                InputUtil.UNKNOWN_KEY.getCode(),
                "category.cstmm"
        ));
        keyDefuseBomb = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.cstmm.defuse_bomb",
                InputUtil.Type.KEYSYM,
                InputUtil.UNKNOWN_KEY.getCode(),
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

            // 【爆破交互】手持 C4 长按右键安放 / 靠近 C4 长按右键拆除（均为使用键）：
            // 按住状态（无界面打开时）翻转时发 start/stop，条件校验与进度累计在服务端
            tickBombInteraction(client);
        });

        LOGGER.info("[CSTMM - Client] Registered key bindings");
    }

    /**
     * 【作用】每客户端 tick 检测爆破交互按键（安放=手持 cstmm:c4 + 右键（使用键）按住；
     *         拆除=3 格内存在 C4 实体 + 右键（使用键）按住），按住状态翻转时发
     *         PLANT_BOMB/DEFUSE_BOMB 的 start/stop 请求。阵营/包点/范围等条件由服务端校验并反馈。
     *         按住期间玩家自行移动（按住移动/跳跃键）也会翻转成 stop → 中断进度；
     *         被他人推动等被动位移不改变按键状态，进度继续累计。
     * 【被谁使用】本类 END_CLIENT_TICK 回调（仅游戏内且握手成功时）。
     */
    private static void tickBombInteraction(net.minecraft.client.MinecraftClient client) {
        var player = client.player;
        if (player == null) {
            plantHeld = false;
            defuseHeld = false;
            suppressDefuseUntilRelease = false;
            return;
        }
        boolean screenOpen = client.currentScreen != null;
        // 安放与拆除触发键：原版使用键（右键）或自定义安放/拆除键（默认未绑定）任一按住即触发
        boolean useHeld = !screenOpen && client.options.useKey.isPressed();
        boolean plantKeyHeld = !screenOpen && keyPlantBomb.isPressed();
        boolean defuseKeyHeld = !screenOpen && keyDefuseBomb.isPressed();
        // 玩家自行移动（按住原版移动/跳跃键）：只有自行移动才中断安放/拆除；
        // 被他人推动、击退等被动位移时玩家没有移动输入，进度照常累计
        boolean selfMoving = !screenOpen && (client.options.forwardKey.isPressed()
                || client.options.backKey.isPressed() || client.options.leftKey.isPressed()
                || client.options.rightKey.isPressed() || client.options.jumpKey.isPressed());

        // 安放：主手手持 C4（已在安放中则切换手持也算）且按住（使用键或自定义安放键）、未自行移动；
        // 已开始后切换手持不中断（plantHeld 保持 start 状态），服务端校验阵营/包点/阶段（自由安放开关放宽）
        boolean planting = (useHeld || plantKeyHeld) && !selfMoving
                && (plantHeld || player.getMainHandStack().isOf(ModItems.C4));
        if (planting != plantHeld) {
            plantHeld = planting;
            ClientPlayNetworking.send(new MatchActionPayload(
                    MatchActionPayload.ActionType.PLANT_BOMB, "", 0, planting ? "start" : "stop"));
            // 按住触发键期间自行移动 → 本地提示中断原因（松键引起的 stop 不提示）
            if (!planting && selfMoving && (useHeld || plantKeyHeld)) {
                player.sendMessage(Text.literal("§c你不能一边移动一边安放炸弹！"), true);
            }
        }
        // 安放期间一直按着触发键 → 抑制拆除，直至全部触发键松开
        // （安放完成后继续按住右键/安放键不触发拆弹，需松开后重新按下）
        if (planting) {
            suppressDefuseUntilRelease = true;
        } else if (!useHeld && !plantKeyHeld && !defuseKeyHeld) {
            suppressDefuseUntilRelease = false;
        }

        // 拆除：附近（3 格）有 C4 实体且（使用键或自定义拆除键）按住；服务端要求 CT 且在拆除距离内。
        // 安放后未松键重按（suppressDefuseUntilRelease）时不触发
        boolean nearBomb = false;
        if (!screenOpen && client.world != null) {
            Box box = player.getBoundingBox().expand(BombInteractionManager.DEFUSE_RANGE_CLIENT);
            nearBomb = !client.world.getEntitiesByClass(BombEntity.class, box, e -> true).isEmpty();
        }
        boolean defusing = (useHeld || defuseKeyHeld) && nearBomb && !suppressDefuseUntilRelease
                && !selfMoving;
        if (defusing != defuseHeld) {
            defuseHeld = defusing;
            ClientPlayNetworking.send(new MatchActionPayload(
                    MatchActionPayload.ActionType.DEFUSE_BOMB, "", 0, defusing ? "start" : "stop"));
            // 按住触发键期间自行移动 → 本地提示中断原因（松键引起的 stop 不提示）
            if (!defusing && selfMoving && (useHeld || defuseKeyHeld)) {
                player.sendMessage(Text.literal("§c你不能一边移动一边拆除炸弹！"), true);
            }
        }
    }
}