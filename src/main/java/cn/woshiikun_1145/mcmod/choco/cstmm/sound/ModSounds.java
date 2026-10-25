package cn.woshiikun_1145.mcmod.choco.cstmm.sound;

import cn.woshiikun_1145.mcmod.choco.cstmm.Cstmm;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;

/**
 * 【作用】模组自定义音效注册表（命名空间 cstmm）：
 *         cstmm:c4_beep = C4 电子哔声（短促 1750Hz，占位生成文件）——安放后按剩余时间
 *         控制播放间隔（越接近爆炸响得越快），由 BombRoundManager（对局内）与
 *         BombEntity（自由炸弹）驱动。
 * 【被谁使用】Cstmm.onInitialize（启动时注册，须在注册表冻结前）；BombInteractionManager
 *           （对局内炸弹哔声）、BombEntity（自由炸弹哔声）；音效本体 assets/cstmm/sounds/c4_beep.ogg，
 *           声明在 assets/cstmm/sounds.json。
 */
public class ModSounds {

    /** C4 电子哔声（对局内与自由炸弹共用，紧迫感由播放间隔与音高变化体现） */
    public static final RegistryEntry.Reference<SoundEvent> C4_BEEP =
            register("c4_beep", SoundEvent.of(Identifier.of("cstmm", "c4_beep")));

    /** C4 爆炸轰鸣（低频 + 噪声衰减占位音效，爆炸结算时叠加播放） */
    public static final RegistryEntry.Reference<SoundEvent> C4_EXPLOSION =
            register("c4_explosion", SoundEvent.of(Identifier.of("cstmm", "c4_explosion")));

    /** C4 起爆引信音（倒计时归零后先播 1.5 秒，播完再真正爆炸结算，占位生成文件） */
    public static final RegistryEntry.Reference<SoundEvent> C4_DETONATION =
            register("c4_detonation", SoundEvent.of(Identifier.of("cstmm", "c4_detonation")));

    /** C4 安放完成确认音（双响上行，占位生成文件） */
    public static final RegistryEntry.Reference<SoundEvent> C4_PLANT =
            register("c4_plant", SoundEvent.of(Identifier.of("cstmm", "c4_plant")));

    /** C4 安放进行音（渐升键入声，开始安放时播放，与拆除进行音对应，占位生成文件） */
    public static final RegistryEntry.Reference<SoundEvent> C4_PLANTING =
            register("c4_planting", SoundEvent.of(Identifier.of("cstmm", "c4_planting")));

    /** C4 拆除进行音（低频滴答，开始拆除时播放，占位生成文件） */
    public static final RegistryEntry.Reference<SoundEvent> C4_DEFUSE =
            register("c4_defuse", SoundEvent.of(Identifier.of("cstmm", "c4_defuse")));

    /** C4 拆除完成确认音（上行三音阶，占位生成文件） */
    public static final RegistryEntry.Reference<SoundEvent> C4_DEFUSED =
            register("c4_defused", SoundEvent.of(Identifier.of("cstmm", "c4_defused")));

    /** 炸弹被拆除语音播报（回合播报语音，与 c4_defused 确认音不同，占位生成文件） */
    public static final RegistryEntry.Reference<SoundEvent> C4_DEFUSED_ANNOUNCE =
            register("c4_defused_announce", SoundEvent.of(Identifier.of("cstmm", "c4_defused_announce")));

    /** T 阵营回合胜利音效（爆破模式，占位生成文件） */
    public static final RegistryEntry.Reference<SoundEvent> T_WIN =
            register("t_win", SoundEvent.of(Identifier.of("cstmm", "t_win")));

    /** CT 阵营回合胜利音效（爆破模式，占位生成文件） */
    public static final RegistryEntry.Reference<SoundEvent> CT_WIN =
            register("ct_win", SoundEvent.of(Identifier.of("cstmm", "ct_win")));

    /** 平局音效（加时继续投票决定平局时播放，占位生成文件） */
    public static final RegistryEntry.Reference<SoundEvent> ROUND_DRAW =
            register("round_draw", SoundEvent.of(Identifier.of("cstmm", "round_draw")));

    /** 注册音效事件并返回注册表引用。 */
    private static RegistryEntry.Reference<SoundEvent> register(String name, SoundEvent event) {
        return Registry.registerReference(Registries.SOUND_EVENT, Identifier.of("cstmm", name), event);
    }

    /**
     * 【作用】音效注册入口（必须在注册表冻结前调用）。
     * 【被谁使用】Cstmm.onInitialize（ModGuardian 包装，异常时进入保护模式）。
     */
    public static void initialize() {
        Cstmm.LOGGER.info("[CSTMM - ModSounds] Sound registered: cstmm:c4_beep");
    }
}
