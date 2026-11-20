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

    // ===== 手雷系统音效（移植自 CounterStrikeGrenades，命名空间改为 cstmm） =====

    /** 手雷通用碰撞声（所有手雷落地/弹开共用，音量按距离衰减由客户端计算） */
    public static final RegistryEntry.Reference<SoundEvent> GRENADE_HIT =
            register("grenade.hit", SoundEvent.of(Identifier.of("cstmm", "grenade.hit")));

    /** 手雷通用投掷出手的破风声 */
    public static final RegistryEntry.Reference<SoundEvent> GRENADE_THROW =
            register("grenade.throw", SoundEvent.of(Identifier.of("cstmm", "grenade.throw")));

    /** HE 手雷拔销/持握音 */
    public static final RegistryEntry.Reference<SoundEvent> HEGRENADE_DRAW =
            register("hegrenade.draw", SoundEvent.of(Identifier.of("cstmm", "hegrenade.draw")));

    /** HE 手雷弹跳碰撞声（比通用碰撞声更金属） */
    public static final RegistryEntry.Reference<SoundEvent> HEGRENADE_BOUNCE =
            register("hegrenade.bounce", SoundEvent.of(Identifier.of("cstmm", "hegrenade.bounce")));

    /** HE 手雷爆炸声（近距离） */
    public static final RegistryEntry.Reference<SoundEvent> HEGRENADE_EXPLODE =
            register("hegrenade.explode", SoundEvent.of(Identifier.of("cstmm", "hegrenade.explode")));

    /** HE 手雷爆炸声（远距离回响） */
    public static final RegistryEntry.Reference<SoundEvent> HEGRENADE_EXPLODE_DISTANT =
            register("hegrenade.explode_distant", SoundEvent.of(Identifier.of("cstmm", "hegrenade.explode_distant")));

    /** 闪光弹拔销/持握音 */
    public static final RegistryEntry.Reference<SoundEvent> FLASHBANG_DRAW =
            register("flashbang.draw", SoundEvent.of(Identifier.of("cstmm", "flashbang.draw")));

    /** 闪光弹爆炸声（近距离） */
    public static final RegistryEntry.Reference<SoundEvent> FLASHBANG_EXPLODE =
            register("flashbang.explode", SoundEvent.of(Identifier.of("cstmm", "flashbang.explode")));

    /** 闪光弹爆炸声（远距离） */
    public static final RegistryEntry.Reference<SoundEvent> FLASHBANG_EXPLODE_DISTANT =
            register("flashbang.explode_distant", SoundEvent.of(Identifier.of("cstmm", "flashbang.explode_distant")));

    /** 闪光弹致盲后的耳鸣声（FlashbangRingSound 循环播放） */
    public static final RegistryEntry.Reference<SoundEvent> FLASHBANG_EXPLOSION_RING =
            register("flashbang.explosion_ring", SoundEvent.of(Identifier.of("cstmm", "flashbang.explosion_ring")));

    /** 烟雾弹拔销/持握音 */
    public static final RegistryEntry.Reference<SoundEvent> SMOKE_GRENADE_DRAW =
            register("smokegrenade.draw", SoundEvent.of(Identifier.of("cstmm", "smokegrenade.draw")));

    /** 烟雾弹开始发烟声（近距离） */
    public static final RegistryEntry.Reference<SoundEvent> SMOKE_EMIT =
            register("smokegrenade.smoke_emmit", SoundEvent.of(Identifier.of("cstmm", "smokegrenade.smoke_emmit")));

    /** 烟雾弹开始发烟声（远距离嘶嘶回响） */
    public static final RegistryEntry.Reference<SoundEvent> SMOKE_EXPLODE_DISTANT =
            register("smokegrenade.smoke_explode_distant", SoundEvent.of(Identifier.of("cstmm", "smokegrenade.smoke_explode_distant")));

    /** 燃烧类手雷拔销/持握音（燃烧弹与莫洛托夫共用，原 mod 无 molotov.draw） */
    public static final RegistryEntry.Reference<SoundEvent> INCENDIARY_DRAW =
            register("incendiary.draw", SoundEvent.of(Identifier.of("cstmm", "incendiary.draw")));

    /** 燃烧类手雷投掷出手的破风声 */
    public static final RegistryEntry.Reference<SoundEvent> INCENDIARY_THROW =
            register("incendiary.throw", SoundEvent.of(Identifier.of("cstmm", "incendiary.throw")));

    /** 燃烧类手雷碰撞声 */
    public static final RegistryEntry.Reference<SoundEvent> INCENDIARY_BOUNCE =
            register("incendiary.bounce", SoundEvent.of(Identifier.of("cstmm", "incendiary.bounce")));

    /** 燃烧弹落地起火声（近距离） */
    public static final RegistryEntry.Reference<SoundEvent> INCENDIARY_EXPLODE =
            register("incendiary.explode", SoundEvent.of(Identifier.of("cstmm", "incendiary.explode")));

    /** 燃烧类手雷空中自爆爆燃声（近距离） */
    public static final RegistryEntry.Reference<SoundEvent> INCENDIARY_EXPLODE_AIR =
            register("incendiary.explode_air", SoundEvent.of(Identifier.of("cstmm", "incendiary.explode_air")));

    /** 燃烧弹落地起火声（远距离回响） */
    public static final RegistryEntry.Reference<SoundEvent> INCENDIARY_EXPLODE_DISTANT =
            register("incendiary.explode_distant", SoundEvent.of(Identifier.of("cstmm", "incendiary.explode_distant")));

    /** 燃烧类手雷被烟雾/水浇灭的噗声 */
    public static final RegistryEntry.Reference<SoundEvent> INCENDIARY_POP =
            register("incendiary.pop", SoundEvent.of(Identifier.of("cstmm", "incendiary.pop")));

    /** 诱饵弹拔销/持握音 */
    public static final RegistryEntry.Reference<SoundEvent> DECOY_DRAW =
            register("decoy.draw", SoundEvent.of(Identifier.of("cstmm", "decoy.draw")));

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
