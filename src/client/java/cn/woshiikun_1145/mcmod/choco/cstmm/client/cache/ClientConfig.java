package cn.woshiikun_1145.mcmod.choco.cstmm.client.cache;

import cn.woshiikun_1145.mcmod.choco.cstmm.Cstmm;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 【作用】客户端个性化配置（config/cstmm/client/config.json）：目前仅界面主题色
 *         （6 位 hex RGB，应用到主菜单标题/侧边栏标记/弹窗边框等位置）。
 *         头像绑定不在此存储——头像需要广播给所有玩家显示，绑定（avatarType/avatarId）
 *         由服务端存于档案文件 config/cstmm/data/players/<uuid>.json 并随档案/战队/队列数据下发，
 *         客户端按绑定自行获取图片。
 *         懒加载 + 变更即原子落盘，文件缺失/损坏按默认值兜底。
 * 【被谁使用】PersonalizeTabPanel（主题色切换）、MatchMenuScreen（读取主题色渲染）。
 */
@Environment(EnvType.CLIENT)
public final class ClientConfig {

    /** 默认主题色（模组标志性橙色，6 位 hex RGB） */
    public static final String DEFAULT_THEME_COLOR = "FFAA00";

    /** 模组声音命名空间（模组音效音量倍率只缩放该命名空间的 MC 音效） */
    public static final String MOD_SOUND_NAMESPACE = "cstmm";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // 单例工具类，禁止实例化
    private ClientConfig() {}

    // 内存态（懒加载后与磁盘一致）
    private static boolean loaded = false;
    private static String themeColor = DEFAULT_THEME_COLOR;
    // MVP 音乐开关与音量（独立于游戏音量；0~4，1=100%）
    private static boolean mvpMusicEnabled = true;
    private static float mvpMusicVolume = 0.7f;
    // 模组音效音量倍率（作用于全部 cstmm 命名空间 MC 音效，经 SoundSystemMixin 缩放；0~4，1=100%）
    private static float soundEffectsVolume = 1.0f;
    // MVP 资源下载线程数（1~32，默认 3；可在个性化页"控制"区调整）
    private static int downloadThreads = 3;

    // 磁盘文件：<config>/cstmm/client/config.json
    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("cstmm/client/config.json");
    }

    // 懒加载：首次访问读盘；缺失/损坏按默认值兜底（损坏文件保留不覆盖，由下次保存时替换）
    private static void loadIfNeed() {
        if (loaded) return;
        loaded = true;
        try {
            Path f = file();
            if (!Files.exists(f)) return;
            Data d = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), Data.class);
            if (d == null) return;
            themeColor = validHex(d.themeColor) ? d.themeColor.toUpperCase() : DEFAULT_THEME_COLOR;
            if (d.mvpMusicEnabled != null) mvpMusicEnabled = d.mvpMusicEnabled;
            if (d.mvpMusicVolume != null) mvpMusicVolume = clampVolume(d.mvpMusicVolume);
            if (d.soundEffectsVolume != null) soundEffectsVolume = clampVolume(d.soundEffectsVolume);
            if (d.downloadThreads != null) downloadThreads = clampThreads(d.downloadThreads);
        } catch (Exception e) {
            Cstmm.LOGGER.warn("[CSTMM - ClientConfig] Failed to load client config, using defaults: {}", e.toString());
        }
    }

    // 6 位十六进制 RGB 校验
    private static boolean validHex(String s) {
        return s != null && s.matches("[0-9a-fA-F]{6}");
    }

    // 原子落盘（先写 .tmp 再替换）；失败仅告警，主题色丢失可接受
    private static void save() {
        try {
            Path f = file();
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(new Data(), writer);
            }
            try {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            Cstmm.LOGGER.warn("[CSTMM - ClientConfig] Failed to save client config: {}", e.toString());
        }
    }

    // 序列化载体（字段名即 JSON 键）
    private static class Data {
        String themeColor = ClientConfig.themeColor;
        Boolean mvpMusicEnabled = ClientConfig.mvpMusicEnabled;
        Float mvpMusicVolume = ClientConfig.mvpMusicVolume;
        Float soundEffectsVolume = ClientConfig.soundEffectsVolume;
        Integer downloadThreads = ClientConfig.downloadThreads;
    }

    // 音量钳制在 0~4（100%=1，最高 400%）
    private static float clampVolume(float v) {
        return v < 0 ? 0 : v > 4 ? 4 : v;
    }

    /** MVP 音乐是否启用（关闭后收到 MVP 通知也不播放） */
    public static boolean isMvpMusicEnabled() {
        loadIfNeed();
        return mvpMusicEnabled;
    }

    public static void setMvpMusicEnabled(boolean enabled) {
        loadIfNeed();
        mvpMusicEnabled = enabled;
        save();
    }

    /** MVP 音乐音量倍率（0~4，1=100%，独立于游戏音量） */
    public static float getMvpMusicVolume() {
        loadIfNeed();
        return mvpMusicVolume;
    }

    public static void setMvpMusicVolume(float volume) {
        loadIfNeed();
        mvpMusicVolume = clampVolume(volume);
        save();
    }

    /** 模组音效音量倍率（0~4，1=100%；缩放全部 cstmm 命名空间 MC 音效） */
    public static float getSoundEffectsVolume() {
        loadIfNeed();
        return soundEffectsVolume;
    }

    public static void setSoundEffectsVolume(float volume) {
        loadIfNeed();
        soundEffectsVolume = clampVolume(volume);
        save();
    }

    // 下载线程数钳制在 1~32
    private static int clampThreads(int n) {
        return n < 1 ? 1 : n > 32 ? 32 : n;
    }

    /** MVP 资源下载线程数（1~32，默认 3） */
    public static int getDownloadThreads() {
        loadIfNeed();
        return downloadThreads;
    }

    public static void setDownloadThreads(int threads) {
        loadIfNeed();
        downloadThreads = clampThreads(threads);
        save();
    }

    /**
     * 【作用】读取当前主题色（ARGB，alpha 固定 FF）；非法值兜底默认橙色。
     * 【被谁使用】MatchMenuScreen（标题/侧边栏标记/弹窗边框着色）等各界面。
     */
    public static int getThemeColorArgb() {
        loadIfNeed();
        try {
            return (int) (0xFF000000L | Long.parseLong(themeColor, 16));
        } catch (NumberFormatException e) {
            return 0xFFFFAA00;
        }
    }

    /** 读取当前主题色 hex 字符串（6 位 RGB，个性化页回显用） */
    public static String getThemeColor() {
        loadIfNeed();
        return themeColor;
    }

    /** 设置主题色（6 位 hex RGB）；非法值忽略，成功即落盘并即时生效 */
    public static void setThemeColor(String hex) {
        if (!validHex(hex)) return;
        loadIfNeed();
        themeColor = hex.toUpperCase();
        save();
    }
}
