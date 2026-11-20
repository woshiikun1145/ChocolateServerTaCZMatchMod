package cn.woshiikun_1145.mcmod.choco.cstmm.client.grenade.render;

import cn.woshiikun_1145.mcmod.choco.cstmm.network.payload.FlashbangEffectData;
import cn.woshiikun_1145.mcmod.choco.cstmm.sound.ModSounds;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

import java.time.Duration;
import java.time.Instant;

/**
 * 【作用】本地玩家闪光弹致盲白屏 overlay（移植自 CounterStrikeGrenades 的
 *         FlashbangBlindEffectRenderer）：attack（白屏渐入）→ sustain（全白）→
 *         decay（渐弱）三段毫秒时序，绘制全屏白色半透明矩形；重复触发按当前阶段
 *         取 max/重置（原 mod 语义）；同时播放耳鸣循环音（FlashbangRingSound）。
 *         声音屏蔽：致盲期间非豁免音效音量置 0（经 SoundSystemMixin 的
 *         getAdjustedVolume 注入点调用 {@link #isSuppressed(SoundInstance)}）。
 * 【被谁使用】GrenadeClientPayloads（收到 cstmm:flashbang_exploded 且 uuid 为本地玩家）；
 *           CstmmClient（register 挂 HudRenderCallback）；SoundSystemMixin（屏蔽判定）。
 *           与原 mod 的差异：overlay 用 HudRenderCallback（fabric-rendering-v1）替代
 *           RenderLevelStageEvent 的 Tesselator 绘制，效果等价、实现更简单。
 */
@Environment(EnvType.CLIENT)
public final class FlashbangBlindEffectRenderer {

    private FlashbangBlindEffectRenderer() {
    }

    /** 时序状态机阶段（IDLE = 未被闪） */
    private enum RenderState { IDLE, ATTACK, SUSTAIN, DECAY }

    private static int effectAttack = 0;
    private static int effectSustain = 0;
    private static int effectDecay = 0;
    private static int effectAmount = 0;
    private static Instant renderStartTime = Instant.now();
    private static RenderState renderState = RenderState.IDLE;

    /**
     * 【作用】收到爆闪数据时更新/重置状态机并播放耳鸣音（data.position 由爆心提供，
     *         耳鸣音量按 SoundTypes.FLASHBANG_RING 口径 0.03/80 计算）。
     * 【被谁使用】GrenadeClientPayloads。
     */
    public static void render(FlashbangEffectData data, Vec3d flashPos) {
        switch (renderState) {
            case IDLE -> {
                renderStartTime = Instant.now();
                effectAttack = data.attack();
                effectSustain = data.sustain();
                effectDecay = data.decay();
                effectAmount = data.amount();
                // 【作用】关键修复：必须离开 IDLE，否则 HUD 回调会因 IDLE 早退而永不绘制白屏、
                //         声音屏蔽判定也永不生效（原 mod 由事件处理器推进状态，移植版状态机同款语义）
                renderState = RenderState.ATTACK;
            }
            case ATTACK, SUSTAIN -> {
                // 【作用】连续爆闪：各段时长与亮度取最大值（叠加不重置）
                effectAttack = Math.max(effectAttack, data.attack());
                effectSustain = Math.max(effectSustain, data.sustain());
                effectDecay = Math.max(effectDecay, data.decay());
                effectAmount = Math.max(effectAmount, data.amount());
            }
            case DECAY -> {
                // 【作用】衰减期再次爆闪：从新的 attack 起点重新计时
                renderStartTime = Instant.now().minus(Duration.ofMillis(data.attack()));
                effectAttack = data.attack();
                effectSustain = data.sustain();
                effectDecay = data.decay();
            }
        }
        playRingSound(data, flashPos);
    }

    /**
     * 【作用】声音屏蔽判定（SoundSystemMixin 注入点调用）：致盲非 IDLE 阶段且
     *         不在豁免列表（耳鸣循环音 / 原版 UI 按键音 / 闪光爆炸近远音）时返回 true。
     * 【被谁使用】client/mixin/SoundSystemMixin（getAdjustedVolume RETURN 注入）。
     */
    public static boolean isSuppressed(SoundInstance sound) {
        if (renderState == RenderState.IDLE) {
            return false;
        }
        if (sound instanceof FlashbangRingSound) {
            return false;
        }
        Identifier id = sound.getId();
        if (id == null) {
            return false;
        }
        // 【作用】豁免：原版 UI 按键音（保留基本操作反馈）
        Identifier uiClick = net.minecraft.sound.SoundEvents.UI_BUTTON_CLICK.value().getId();
        if (id.equals(uiClick)) {
            return false;
        }
        // 【作用】豁免：闪光弹自己的爆炸音（近/远）
        if (id.equals(ModSounds.FLASHBANG_EXPLODE.value().getId())
                || id.equals(ModSounds.FLASHBANG_EXPLODE_DISTANT.value().getId())) {
            return false;
        }
        return true;
    }

    // 【作用】挂载 HUD 覆盖层渲染回调（CstmmClient 调一次；每帧按状态机推进透明度）
    public static void register() {
        HudRenderCallback.EVENT.register((context, tickCounter) -> {
            if (renderState == RenderState.IDLE) {
                return;
            }
            ClientPlayerEntity player = MinecraftClient.getInstance().player;
            if (player == null || player.isSpectator()) {
                return;
            }
            long timeDelta = Duration.between(renderStartTime, Instant.now()).toMillis();
            int opacity;
            if (timeDelta < effectAttack) {
                opacity = (int) ((double) timeDelta / effectAttack * effectAmount);
                renderState = RenderState.ATTACK;
            } else if (timeDelta < (long) effectSustain + effectAttack) {
                opacity = effectAmount;
                renderState = RenderState.SUSTAIN;
            } else if (timeDelta < (long) effectDecay + effectSustain + effectAttack) {
                opacity = effectAmount
                        - (int) (((double) timeDelta - effectAttack - effectSustain) / effectDecay * effectAmount);
                renderState = RenderState.DECAY;
            } else {
                clean();
                return;
            }
            // 【作用】全屏白屏覆盖（opacity 0-255 ARGB）
            if (opacity > 0) {
                var window = MinecraftClient.getInstance().getWindow();
                context.fill(0, 0, window.getScaledWidth(), window.getScaledHeight(),
                        (Math.min(opacity, 255) << 24) | 0xFFFFFF);
            }
        });
    }

    // 【作用】重置到未致盲状态
    private static void clean() {
        effectAttack = 0;
        effectDecay = 0;
        effectSustain = 0;
        effectAmount = 0;
        renderState = RenderState.IDLE;
    }

    // 【作用】播放耳鸣循环音（音量按与爆心距离衰减：FLASHBANG_RING 口径 0.03/80）
    private static void playRingSound(FlashbangEffectData data, Vec3d flashPos) {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null) {
            return;
        }
        double distance = Math.sqrt(player.squaredDistanceTo(flashPos));
        // FLASHBANG_RING：initialVolume 0.03 / hearableRange 80.0
        double volume = Math.max(0.0, 0.03 * (1 - distance / 80.0));
        MinecraftClient.getInstance().getSoundManager().play(
                new FlashbangRingSound(0, data.sustain(), data.decay() + 300, (float) volume));
    }
}
