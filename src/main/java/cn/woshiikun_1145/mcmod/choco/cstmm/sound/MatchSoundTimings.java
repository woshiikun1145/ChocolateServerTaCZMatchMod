package cn.woshiikun_1145.mcmod.choco.cstmm.sound;

/**
 * 【作用】对局播报音效时序常量（毫秒）：集中管理对局内各播报音效之间的延迟，
 *         /cstmm debug test 的模拟序列同样从本类取值——调整任一常量，试听时序与实战时序同步生效。
 * 【被谁使用】BombRoundManager（引信起爆延迟、爆炸/拆除后的胜负音延迟）、
 *           ModCommands（debug test 模拟序列）。仅服务端。
 */
public final class MatchSoundTimings {

    /** C4 引信音时长 = 倒计时归零到真正爆炸的延迟（须与 c4_detonation.ogg 实际时长一致，替换音效时同步调整） */
    public static final long DETONATION_LEAD_MILLIS = 1910;

    /** 爆炸声（c4_explosion）响起到 T 回合胜利音（t_win）的延迟 */
    public static final long EXPLOSION_WIN_DELAY_MILLIS = 1000;

    /** 拆除语音播报（c4_defused_announce）响起到 CT 回合胜利音（ct_win）的延迟 */
    public static final long DEFUSED_WIN_DELAY_MILLIS = 2000;

    private MatchSoundTimings() {}
}
