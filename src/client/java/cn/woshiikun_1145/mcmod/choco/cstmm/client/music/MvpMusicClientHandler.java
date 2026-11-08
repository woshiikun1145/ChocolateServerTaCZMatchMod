package cn.woshiikun_1145.mcmod.choco.cstmm.client.music;

import cn.woshiikun_1145.mcmod.choco.cstmm.Cstmm;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.ClientHandshakeState;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.cache.ClientConfig;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.cache.ConfigDataCache;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.hud.HudOverlay;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 【作用】MVP 音乐客户端流程编排：
 *         收到 MvpMusicPayload → 延迟（避开胜利音效）→ 从全局配置的 mvpMusicUrl 加载 index 曲目清单
 *         → 按 music_name 找到曲目 → 死亡竞赛模式在聊天栏输出
 *         “正在播放【玩家】的MVP音乐【作者 - 音乐名】”，爆破模式在 HUD 信息栏下方显示同一文案
 *         → 确保音乐文件已缓存（MurmurHash3 校验）→ JLayer 播放。
 *         清单尚未加载完时短时轮询重试；资源/清单不可用时静默降级（只记日志）。
 * 【被谁使用】ClientNetworkHandler 的 MvpMusicPayload 接收器（切回客户端主线程后调用）。
 */
public final class MvpMusicClientHandler {

    /** 清单加载等待重试（总计约 4 秒） */
    private static final int MAX_INDEX_RETRIES = 8;
    private static final long INDEX_RETRY_MS = 500;
    /** 爆破模式 HUD 提示显示时长 */
    private static final long HUD_HINT_MS = 5000;

    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "cstmm-mvp-scheduler");
        t.setDaemon(true);
        return t;
    });

    private MvpMusicClientHandler() {}

    /**
     * 【作用】收到服务端 MVP 音乐通知：按 delayMs 延迟后执行（死亡竞赛模式通常 0）。
     * 【被谁使用】ClientNetworkHandler 的 MvpMusicPayload 接收器。
     */
    public static void onPayload(String mvpName, String musicName, int delayMs, boolean bombMode) {
        if (mvpName == null || mvpName.isEmpty() || musicName == null || musicName.isEmpty()) return;
        int delay = Math.max(0, delayMs);
        if (delay == 0) {
            begin(mvpName, musicName, bombMode, 0);
        } else {
            SCHEDULER.schedule(() -> begin(mvpName, musicName, bombMode, 0), delay, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * 【作用】准备周期：加载清单并查找曲目；未加载完则延时重试，完成后展示提示并播放。
     * 【被谁使用】onPayload（调度线程）。
     */
    private static void begin(String mvpName, String musicName, boolean bombMode, int attempt) {
        if (!ClientHandshakeState.isHandshaked()) return;
        String url = ConfigDataCache.getInstance().getGlobalConfig().getMvpMusicUrl();
        if (url.isEmpty()) {
            Cstmm.LOGGER.debug("[CSTMM - MvpMusic] mvpMusicUrl not configured, skip");
            return;
        }
        MvpMusicIndex.ensureLoaded(url);

        MvpMusicIndex.Track track = MvpMusicIndex.find(musicName);
        if (track == null) {
            if (MvpMusicIndex.isLoading() && attempt < MAX_INDEX_RETRIES) {
                SCHEDULER.schedule(() -> begin(mvpName, musicName, bombMode, attempt + 1),
                        INDEX_RETRY_MS, TimeUnit.MILLISECONDS);
                return;
            }
            Cstmm.LOGGER.warn("[CSTMM - MvpMusic] Music '{}' not found in index ({})",
                    musicName, MvpMusicIndex.getError());
            return;
        }
        present(mvpName, track, bombMode);
    }

    /** 【作用】展示提示（死亡竞赛附聊天栏播报）并触发缓存下载与播放（切回客户端主线程）。 */
    private static void present(String mvpName, MvpMusicIndex.Track track, boolean bombMode) {
        String musicLine = track.author() + " - " + track.musicName();
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> {
            if (client.player == null) return;
            if (bombMode) {
                HudOverlay.showMvpHint(mvpName, musicLine, track, HUD_HINT_MS);
            } else {
                client.player.sendMessage(
                        Text.literal("正在播放【" + mvpName + "】的MVP音乐【" + musicLine + "】"), false);
                HudOverlay.showMvpHint(mvpName, musicLine, track, HUD_HINT_MS);
            }
            if (!ClientConfig.isMvpMusicEnabled()) return;
            MvpMusicCache.ensureMusic(track, path -> client.execute(() ->
                    MvpMusicPlayer.play(path, track.display(), ClientConfig.getMvpMusicVolume())));
        });
    }

    /** 【作用】断线清理：停止播放、清除 HUD 提示、清空清单内存与下载运行时状态（磁盘保留）。 */
    public static void onDisconnect() {
        MvpMusicPlayer.stop();
        HudOverlay.clearMvpHint();
        MvpMusicIndex.clear();
        MvpMusicCache.clearRuntime();
    }
}
