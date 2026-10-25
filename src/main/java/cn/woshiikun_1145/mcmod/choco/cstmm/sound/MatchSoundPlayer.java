package cn.woshiikun_1145.mcmod.choco.cstmm.sound;

import cn.woshiikun_1145.mcmod.choco.cstmm.Cstmm;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.MatchSession;
import cn.woshiikun_1145.mcmod.choco.cstmm.manager.MatchManager;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * 【作用】对局播报音效播放器：向单个对局会话内的全体在线玩家**单播**播报类音效
 *         （阵营获胜/平局/拆除语音播报等），定位包发到各玩家自身坐标 = 非定位全音量可闻，
 *         与 C4 爆炸声的单播隔离口径一致——其他对局/对局外玩家听不到。
 *         支持延迟播放（如爆炸声响起 1 秒后再播 T 胜利音效），延迟任务在服务端主线程 tick 推进。
 * 【被谁使用】BombRoundManager（回合胜负/平局音效）、BombInteractionManager（拆除语音播报）、
 *           MatchScheduler（每 tick 推进延迟任务）。仅服务端。
 */
public final class MatchSoundPlayer {

    /** 延迟播放任务（仅服务端主线程访问，无需并发保护） */
    private record ScheduledSound(long dueAtMillis, String sessionId, SoundEvent sound, float volume, float pitch) {}

    private static final List<ScheduledSound> QUEUE = new ArrayList<>();

    private MatchSoundPlayer() {}

    /**
     * 【作用】立即向对局会话内全体在线玩家单播一条播报音效（非定位，各玩家自身坐标全音量）。
     * 【被谁使用】BombRoundManager（全歼/超时胜负音、平局音）、BombInteractionManager（拆除语音播报）。
     */
    public static void playToMatch(MatchSession session, SoundEvent sound, float volume, float pitch) {
        sendToSession(session, sound, volume, pitch);
    }

    /**
     * 【作用】延迟 delayMillis 毫秒后向对局会话单播放报音效（任务在 MatchSoundPlayer#tick 到期执行）。
     *         到期时若对局已结束/不存在则跳过。须在服务端主线程调用（回合结算链路均为主线程）。
     * 【被谁使用】BombRoundManager（C4 爆炸后 1 秒的 T 胜利音、拆除播报后 1 秒的 CT 胜利音）。
     */
    public static void scheduleToMatch(MatchSession session, SoundEvent sound, float volume, float pitch, long delayMillis) {
        QUEUE.add(new ScheduledSound(System.currentTimeMillis() + delayMillis,
                session.getSessionId(), sound, volume, pitch));
    }

    /**
     * 【作用】每服务端 tick 推进延迟播放任务：到期的任务向所属对局在线玩家单播，
     *         对局已结束（ENDED）或已不存在的任务静默丢弃。
     * 【被谁使用】MatchScheduler#onTick（服务端主线程每 tick）。
     */
    public static void tick(MinecraftServer server) {
        if (QUEUE.isEmpty()) return;
        long now = System.currentTimeMillis();
        Iterator<ScheduledSound> it = QUEUE.iterator();
        while (it.hasNext()) {
            ScheduledSound task = it.next();
            if (now < task.dueAtMillis()) continue;
            it.remove();
            MatchSession session = MatchManager.getInstance().getSession(task.sessionId());
            if (session == null || session.getPhase() == MatchSession.GamePhase.ENDED) continue;
            sendToSession(session, task.sound(), task.volume(), task.pitch());
        }
    }

    /** 单播本体：遍历会话在线玩家，向各玩家自身坐标发 PlaySoundS2CPacket（MASTER 分类，非定位）。 */
    private static void sendToSession(MatchSession session, SoundEvent sound, float volume, float pitch) {
        MinecraftServer server = Cstmm.getServer();
        if (server == null) return;
        for (UUID uuid : session.getAllPlayers()) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
            if (player != null) {
                playToPlayer(player, sound, volume, pitch);
            }
        }
    }

    /**
     * 【作用】向单个玩家非定位单播一条音效（MASTER 分类，发包到玩家自身坐标 = 全音量可闻）。
     * 【被谁使用】sendToSession（对局播报）、ModCommands（debug test 播报音效试听）。
     */
    public static void playToPlayer(ServerPlayerEntity player, SoundEvent sound, float volume, float pitch) {
        player.networkHandler.sendPacket(new PlaySoundS2CPacket(
                RegistryEntry.of(sound), SoundCategory.MASTER,
                player.getX(), player.getY(), player.getZ(),
                volume, pitch, player.getRandom().nextLong()));
    }
}
