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

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // 单例工具类，禁止实例化
    private ClientConfig() {}

    // 内存态（懒加载后与磁盘一致）
    private static boolean loaded = false;
    private static String themeColor = DEFAULT_THEME_COLOR;

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
