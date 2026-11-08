package cn.woshiikun_1145.mcmod.choco.cstmm.client.music;

import cn.woshiikun_1145.mcmod.choco.cstmm.Cstmm;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.cache.ClientCacheDirs;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.util.ToastManager;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 【作用】MVP 音乐曲目清单（客户端）：按全局配置的 mvpMusicUrl 访问 <url>/index_file.json，
 *         解析 updated_at 与 musics[]（music_name/author/path.root/music/icon/hash.music/icon），
 *         并按 URL 哈希在本地落盘缓存（mvp_music/index_<murmur3(url)>.json），网络失败时回退磁盘缓存。
 *         清单只在内存保留一份，切服务器/断线时清空。
 * 【被谁使用】MvpMusicClientHandler（播放时查找曲目）、PersonalizeTabPanel（播放列表展示与试听）。
 */
public final class MvpMusicIndex {

    /** 单条曲目（URL 已拼为可直接下载的完整地址；哈希为空串表示资源未提供哈希） */
    public record Track(String musicName, String author, String musicUrl, String iconUrl,
                        String musicHash, String iconHash) {
        /** 音乐文件扩展名（按 path.music 后缀，缺省 mp3） */
        public String musicExt() {
            return extOf(musicUrl, "mp3");
        }

        /** 图标文件扩展名（按 path.icon 后缀，缺省 png） */
        public String iconExt() {
            return extOf(iconUrl, "png");
        }

        /** 列表/提示展示用：作者 - 音乐名 */
        public String display() {
            return author.isEmpty() ? musicName : author + " ， " + musicName;
        }
    }

    @SuppressWarnings("unused")
    private static final Gson GSON = new Gson();
    
    private static volatile String baseUrl = "";
    private static volatile List<Track> tracks = List.of();
    private static volatile boolean loading = false;
    private static volatile String error = "";

    private MvpMusicIndex() {}

    /**
     * 【作用】确保指定 URL 的清单已加载（同 URL 已加载则不重复请求）；后台守护线程拉取，
     *         网络失败时回退该 URL 的磁盘缓存。
     * 【被谁使用】MvpMusicClientHandler（收到 MVP 通知时）、PersonalizeTabPanel（切到个性化页时）。
     */
    public static synchronized void ensureLoaded(String url) {
        String u = url == null ? "" : url.trim();
        if (u.isEmpty()) {
            baseUrl = "";
            tracks = List.of();
            error = "";
            return;
        }
        if (u.equals(baseUrl) && (loading || !tracks.isEmpty() || !error.isEmpty())) return;
        baseUrl = u;
        loading = true;
        error = "";
        Thread t = new Thread(() -> load(u), "cstmm-mvp-index");
        t.setDaemon(true);
        t.start();
    }

    /** 强制重新拉取（个性化页"刷新列表"按钮） */
    public static synchronized void reload(String url) {
        baseUrl = "";
        tracks = List.of();
        error = "";
        loading = false;
        ensureLoaded(url);
    }

    public static List<Track> getTracks() { return tracks; }
    public static boolean isLoading() { return loading; }
    public static String getError() { return error; }

    /** 按 music_name 精确查找曲目（找不到返回 null） */
    public static Track find(String musicName) {
        if (musicName == null || musicName.isEmpty()) return null;
        for (Track t : tracks) {
            if (t.musicName().equals(musicName)) return t;
        }
        return null;
    }

    /** 断线清理（不清磁盘：同 URL 下次可先回退缓存，网络恢复后再刷新） */
    public static synchronized void clear() {
        baseUrl = "";
        tracks = List.of();
        loading = false;
        error = "";
    }

    // ==================== 加载与解析 ====================

    private static void load(String url) {
        try {
            String json;
            try {
                json = fetchText(join(url, "index_file.json"));
                Path cache = cacheFile(url);
                Files.createDirectories(cache.getParent());
                Files.writeString(cache, json, StandardCharsets.UTF_8);
            } catch (Exception netError) {
                // 网络失败：回退磁盘缓存（首次加载/缓存缺失时才真正失败）
                Path cache = cacheFile(url);
                if (Files.exists(cache)) {
                    json = Files.readString(cache, StandardCharsets.UTF_8);
                    Cstmm.LOGGER.warn("[CSTMM - MvpMusicIndex] Network failed, using disk index cache: {}",
                            netError.getMessage());
                } else {
                    throw netError;
                }
            }
            List<Track> parsed = parse(url, json);
            tracks = parsed;
            loading = false;
            error = "";
            Cstmm.LOGGER.info("[CSTMM - MvpMusicIndex] Loaded {} tracks from {}", parsed.size(), url);
            // 清单就绪即后台预下载全部曲目资源（音乐+图标，多线程），不必等玩家主动加载
            MvpMusicCache.prefetchAll(parsed);
        } catch (Exception e) {
            loading = false;
            error = e.getMessage() == null ? e.toString() : e.getMessage();
            Cstmm.LOGGER.warn("[CSTMM - MvpMusicIndex] Failed to load index from {}: {}", url, error);
            // 清单加载失败弹顶部提示栏（同文案 3 秒去重，反复刷新不刷屏）
            ToastManager.show("§c曲目清单加载失败: " + error, 8000);
        }
    }

    private static List<Track> parse(String base, String json) {
        List<Track> list = new ArrayList<>();
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        if (!root.has("musics") || !root.get("musics").isJsonArray()) return list;
        JsonArray musics = root.getAsJsonArray("musics");
        for (JsonElement el : musics) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            String name = optString(o, "music_name");
            if (name.isEmpty()) continue;
            String author = optString(o, "author");

            JsonObject path = o.has("path") && o.get("path").isJsonObject()
                    ? o.getAsJsonObject("path") : new JsonObject();
            String rootPath = optString(path, "root");
            String musicPath = optString(path, "music");
            String iconPath = optString(path, "icon");

            // 哈希允许放在曲目对象或 path 子对象内（兼容两种写法）
            JsonObject hash = o.has("hash") && o.get("hash").isJsonObject()
                    ? o.getAsJsonObject("hash") : new JsonObject();
            if (hash.isEmpty() && path.has("hash") && path.get("hash").isJsonObject()) {
                hash = path.getAsJsonObject("hash");
            }
            String musicHash = optString(hash, "music");
            String iconHash = optString(hash, "icon");

            // 中文等非 ASCII 路径必须百分号编码，否则服务器按原始字节解析请求行会返回 400
            String musicUrl = encodeUrl(join(base, rootPath, musicPath));
            String iconUrl = encodeUrl(join(base, rootPath, iconPath));
            list.add(new Track(name, author, musicUrl, iconUrl, musicHash, iconHash));
        }
        return list;
    }

    // ==================== 工具 ====================

    private static Path cacheFile(String url) {
        return ClientCacheDirs.cacheRoot().resolve("mvp_music")
                .resolve("index_" + murmur3Hex(url) + ".json");
    }

    private static String fetchText(String url) {
        try {
            var conn = URI.create(encodeUrl(url)).toURL().openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("Accept", "application/json");
            try (InputStream in = conn.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage() == null ? e.toString() : e.getMessage(), e);
        }
    }

    /**
     * 拼接 URL：base 末尾有无 "/" 均可，各段前导 "/" 会被剥除（避免出现 "//"）；
     * 段内的中文/空格原样保留（由 URL 编码在打开连接时处理）。
     */
    public static String join(String base, String... segments) {
        StringBuilder sb = new StringBuilder(base == null ? "" : base.trim());
        for (String seg : segments) {
            if (seg == null || seg.isEmpty()) continue;
            String s = seg;
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '/') {
                while (s.startsWith("/")) s = s.substring(1);
            } else if (sb.length() > 0) {
                sb.append('/');
                while (s.startsWith("/")) s = s.substring(1);
            }
            sb.append(s);
        }
        return sb.toString();
    }

    /**
     * 【作用】URL 百分号编码：把中文等非 ASCII 字符、空格等非法字符转为 %XX（UTF-8 字节），
     *         已存在的 %XX 转义原样保留（不会二次编码），RFC 3986 保留字符原样保留。
     * 【被谁使用】本类 parse（拼装曲目 URL）与 fetchText（请求索引时）。
     */
    public static String encodeUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isEmpty()) return rawUrl == null ? "" : rawUrl;
        StringBuilder sb = new StringBuilder(rawUrl.length() + 16);
        for (int i = 0; i < rawUrl.length(); ) {
            char c = rawUrl.charAt(i);
            // 已编码的 %XX 保持原样（避免二次编码）
            if (c == '%' && i + 2 < rawUrl.length()
                    && isHex(rawUrl.charAt(i + 1)) && isHex(rawUrl.charAt(i + 2))) {
                sb.append(rawUrl, i, i + 3);
                i += 3;
                continue;
            }
            if (c < 0x80 && isUrlSafeAscii(c)) {
                sb.append(c);
                i++;
                continue;
            }
            // 非 ASCII（含代理对，如 emoji）按 UTF-8 逐字节转 %XX
            int cp = rawUrl.codePointAt(i);
            int next = i + Character.charCount(cp);
            byte[] bytes = rawUrl.substring(i, next).getBytes(StandardCharsets.UTF_8);
            for (byte b : bytes) {
                sb.append('%')
                        .append(Character.toUpperCase(Character.forDigit((b >> 4) & 0xF, 16)))
                        .append(Character.toUpperCase(Character.forDigit(b & 0xF, 16)));
            }
            i = next;
        }
        return sb.toString();
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    /** RFC 3986 中 URL 各部件通用的 ASCII 字符合集（不含空格与 %） */
    private static boolean isUrlSafeAscii(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || "-._~:/?#[]@!$&'()*+,;=".indexOf(c) >= 0;
    }

    /** 取 URL 路径后缀（忽略 query/fragment）；无后缀返回 fallback */
    private static String extOf(String url, String fallback) {
        if (url == null || url.isEmpty()) return fallback;
        String p = url;
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        q = p.indexOf('#');
        if (q >= 0) p = p.substring(0, q);
        int slash = p.lastIndexOf('/');
        int dot = p.lastIndexOf('.');
        if (dot <= slash) return fallback;
        String ext = p.substring(dot + 1).toLowerCase();
        return ext.matches("[a-z0-9]{1,8}") ? ext : fallback;
    }

    private static String optString(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    /** MurmurHash3_x86_32（seed=0）→ 8 位小写 hex（缓存标记与资源校验共用） */
    public static String murmur3Hex(String s) {
        return murmur3Hex(s.getBytes(StandardCharsets.UTF_8));
    }

    /** MurmurHash3_x86_32（seed=0）→ 8 位小写 hex */
    public static String murmur3Hex(byte[] data) {
        final int c1 = 0xcc9e2d51;
        final int c2 = 0x1b873593;
        int h = 0;
        int blocks = data.length / 4;
        for (int i = 0; i < blocks; i++) {
            int idx = i * 4;
            int k = (data[idx] & 0xFF) | ((data[idx + 1] & 0xFF) << 8)
                    | ((data[idx + 2] & 0xFF) << 16) | ((data[idx + 3] & 0xFF) << 24);
            k *= c1;
            k = Integer.rotateLeft(k, 15);
            k *= c2;
            h ^= k;
            h = Integer.rotateLeft(h, 13);
            h = h * 5 + 0xe6546b64;
        }
        int tail = blocks * 4;
        int rem = data.length - tail;
        if (rem > 0) {
            int k = 0;
            if (rem >= 3) k ^= (data[tail + 2] & 0xFF) << 16;
            if (rem >= 2) k ^= (data[tail + 1] & 0xFF) << 8;
            k ^= data[tail] & 0xFF;
            k *= c1;
            k = Integer.rotateLeft(k, 15);
            k *= c2;
            h ^= k;
        }
        h ^= data.length;
        h ^= h >>> 16;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        h *= 0xc2b2ae35;
        h ^= h >>> 16;
        StringBuilder sb = new StringBuilder(8);
        for (int shift = 28; shift >= 0; shift -= 4) {
            sb.append(Character.forDigit((h >>> shift) & 0xF, 16));
        }
        return sb.toString();
    }
}
