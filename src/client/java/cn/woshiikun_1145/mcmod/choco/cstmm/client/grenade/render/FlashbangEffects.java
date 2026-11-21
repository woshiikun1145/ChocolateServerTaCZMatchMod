package cn.woshiikun_1145.mcmod.choco.cstmm.client.grenade.render;

import cn.woshiikun_1145.mcmod.choco.cstmm.network.payload.GrenadePayloads.FlashBangExplodedPayload;
import cn.woshiikun_1145.mcmod.choco.cstmm.network.payload.GrenadePayloads.FlashbangEffectData;
import cn.woshiikun_1145.mcmod.choco.cstmm.sound.ModSounds;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.sound.MovingSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 【作用】闪光弹客户端效果合集：原 FlashbangRingSound / FlashbangParticleEffectRenderer /
 *         FlashbangBlindEffectRenderer 三个独立类合并为一个文件，三者作为 public static
 *         嵌套类保留（类名与 javadoc 原样不变，逻辑逐字保留）。
 * 【被谁使用】GrenadeClientPayloads（爆闪分发）、CstmmClient（register 挂回调）、
 *           SoundSystemMixin（声音屏蔽判定）。
 */
@Environment(EnvType.CLIENT)
public final class FlashbangEffects {

    // 效果合集容器类，禁止实例化
    private FlashbangEffects() {
    }

    /**
     * 【作用】闪光弹致盲耳鸣音（移植自 CounterStrikeGrenades 的 FlashbangRingSound）：
     *         无衰减定位的全局循环音，音量按 attack/sustain/decay（毫秒）三段包络播放，
     *         decay 结束后自动停止。闪光致盲期间被声音屏蔽逻辑豁免（保持可听）。
     *         注：yarn 1.21.1 中可 tick 声音基类为 MovingSoundInstance（= mojmap
     *         AbstractTickableSoundInstance），循环/衰减字段名为 repeat / attenuationType。
     * 【被谁使用】FlashbangBlindEffectRenderer（爆闪时播放）；
     *           SoundSystemMixin 的闪光声音屏蔽豁免判定（instanceof 本类）。
     */
    @Environment(EnvType.CLIENT)
    public static class FlashbangRingSound extends MovingSoundInstance {

        private final int attack;
        private final int sustain;
        private final int decay;
        private final float targetVolume;
        private final Instant startTime = Instant.now();

        public FlashbangRingSound(int attack, int sustain, int decay, float targetVolume) {
            super(ModSounds.FLASHBANG_EXPLOSION_RING.value(), SoundCategory.MASTER,
                    Random.create());
            this.attack = attack;
            this.sustain = sustain;
            this.decay = decay;
            this.targetVolume = targetVolume;
            this.repeat = true;
            this.pitch = 1.0f;
            this.attenuationType = SoundInstance.AttenuationType.NONE;
            this.volume = targetVolume;
        }

        // 【作用】三段包络推进音量：attack 渐入 → sustain 恒定 → decay 渐出 → stop
        @Override
        public void tick() {
            long timeDelta = Duration.between(this.startTime, Instant.now()).toMillis();
            if (timeDelta < attack) {
                this.volume = (timeDelta / (float) attack) * targetVolume;
            } else if (timeDelta < attack + sustain) {
                this.volume = targetVolume;
            } else if (timeDelta < attack + sustain + decay) {
                this.volume = (float) (targetVolume * (1 - (double) (timeDelta - attack - sustain) / decay));
            } else {
                this.setDone();
            }
        }
    }

    /**
     * 【作用】其他玩家被闪光弹致盲时的可见提示（移植自 CounterStrikeGrenades 的
     *         FlashbangParticleEffectRenderer）：在该玩家眼部前方持续生成白色末地烛粒子
     *         （lifetime=1、放大 3 倍），直到其致盲总时长（attack+sustain+decay）结束。
     * 【被谁使用】GrenadeClientPayloads（收到爆闪包且 uuid 非本地玩家）。
     */
    @Environment(EnvType.CLIENT)
    public static final class FlashbangParticleEffectRenderer {

        private FlashbangParticleEffectRenderer() {
        }

        /** key: 玩家 UUID，value: 停止渲染的 Epoch 毫秒时刻 */
        private static final Map<UUID, Long> renderingPlayers = new ConcurrentHashMap<>();

        /**
         * 【作用】登记一个被闪玩家：已在列表中的玩家只延长结束时刻（sustain+decay），
         *         新玩家按 attack+sustain+decay 全长计算。
         * 【被谁使用】GrenadeClientPayloads。
         */
        public static void render(FlashBangExplodedPayload.Affected affected) {
            long total = affected.effect().sustain() + affected.effect().decay();
            renderingPlayers.merge(affected.uuid(),
                    System.currentTimeMillis() + total,
                    (oldEnd, newEnd) -> Math.max(oldEnd, newEnd));
        }

        // 【作用】挂载客户端 tick：清理过期条目并为每个在列玩家生成 1 帧白闪粒子
        public static void register() {
            ClientTickEvents.END_CLIENT_TICK.register(client -> {
                long now = System.currentTimeMillis();
                renderingPlayers.values().removeIf(end -> end < now);
                if (client.world == null || renderingPlayers.isEmpty()) {
                    return;
                }
                for (UUID uuid : renderingPlayers.keySet()) {
                    PlayerEntity player = client.world.getPlayerByUuid(uuid);
                    if (player != null) {
                        createParticleAtPlayer(player);
                    }
                }
            });
        }

        // 【作用】在玩家眼部前方 0.6 格生成单帧放大的末地烛粒子（白光感）
        private static void createParticleAtPlayer(PlayerEntity player) {
            var particleManager = MinecraftClient.getInstance().particleManager;
            var position = player.getEyePos().add(player.getRotationVector().multiply(0.6));
            var speed = player.getVelocity();
            particleManager.addParticle(ParticleTypes.END_ROD,
                    position.x, position.y, position.z,
                    speed.x, speed.y, speed.z);
        }
    }

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
    public static final class FlashbangBlindEffectRenderer {

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
}
