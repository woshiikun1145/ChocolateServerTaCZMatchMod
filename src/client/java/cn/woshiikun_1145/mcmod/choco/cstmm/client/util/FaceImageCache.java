package cn.woshiikun_1145.mcmod.choco.cstmm.client.util;

import cn.woshiikun_1145.mcmod.choco.cstmm.Cstmm;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 头像图片异步获取缓存：按服务端下发的头像绑定 (avatarType, avatarId) 自行获取图片纹理，
 * 与徽标 {@link UrlImageCache} 分开限长。
 * <ul>
 *   <li>QQ：直接拼接直链 https://q1.qlogo.cn/g?b=qq&amp;nk={id}&amp;s=640；</li>
 *   <li>B站：先经 uapis.cn 接口解析 face 字段得到直链（uid → 直链内存缓存，
 *       每会话每 UID 只请求一次，失败 60s 冷却），再下载图片。</li>
 * </ul>
 * 下载上限 512KiB（QQ 640px 头像可能超过徽标 48KiB 限制）、像素上限 1024，
 * 下载失败 60s 冷却。纹理注册（GL 调用）经 MinecraftClient.execute 切回渲染线程。
 * 【被谁使用】PersonalizeTabPanel（头像预览）、ClanTabPanel（成员列表头像）、
 *             QueueTabPanel（队列页自己的头像）、MatchMenuScreen（履历页头像）经 getTexture 调用；
 *             ClientNetworkHandler 的 DISCONNECT 回调调用 clear()。
 */
public final class FaceImageCache {

    // 工具类，禁止实例化
    private FaceImageCache() {}

    /** QQ 头像直链模板（s=640 为最大尺寸档） */
    private static final String QQ_FACE_URL = "https://q1.qlogo.cn/g?b=qq&nk=%s&s=640";
    /** B站头像解析接口（返回 JSON 的 face 字段为头像直链） */
    private static final String BILI_API_URL = "https://uapis.cn/api/v1/social/bilibili/userinfo?uid=%s";

    /** 下载字节上限（QQ s=640 头像实测可达数百 KiB，取 512KiB） */
    private static final int MAX_BYTES = 512 * 1024;
    /** 解码像素尺寸上限（宽或高任一超过即拒绝，防解压炸弹占满显存） */
    private static final int MAX_PIXELS = 1024;
    /** 下载/解析失败后的重试冷却 */
    private static final long RETRY_COOLDOWN_MS = 60_000;

    // 已加载完成的纹理（url -> 纹理）
    private static final Map<String, Base64ImageDecoder.CardTexture> loaded = new ConcurrentHashMap<>();
    // 下载失败的记录（url -> 失败时间戳，冷却期内不重试）
    private static final Map<String, Long> failedAt = new ConcurrentHashMap<>();
    // 正在下载中的 url 集合（防止重复开线程）
    private static final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    // B站 uid -> 已解析的头像直链（每会话每 UID 只请求一次 uapis）
    private static final Map<String, String> biliResolved = new ConcurrentHashMap<>();
    // B站解析中的 uid 集合（防重复开线程）
    private static final Set<String> biliInFlight = ConcurrentHashMap.newKeySet();
    // B站解析失败记录（uid -> 失败时间戳，冷却期内不重试）
    private static final Map<String, Long> biliFailedAt = new ConcurrentHashMap<>();

    /**
     * 按头像绑定取纹理；未解析完/未下载完/失败冷却中返回 null
     * （调用方画占位，解析/加载完成后下一帧自动显示）。
     * @param avatarType "qq" / "bili"；其他值或空 id 返回 null
     * 【被谁使用】各界面头像绘制的统一入口（QueueTabPanel.drawAvatar、PersonalizeTabPanel 预览）。
     */
    public static Base64ImageDecoder.CardTexture getTexture(String avatarType, String avatarId) {
        if (avatarType == null || avatarId == null || avatarId.isEmpty()) return null;
        switch (avatarType) {
            case "qq" -> {
                return get(String.format(QQ_FACE_URL, avatarId));
            }
            case "bili" -> {
                String url = biliResolved.get(avatarId);
                if (url != null) return get(url);
                // 解析失败冷却期内不重试
                Long fail = biliFailedAt.get(avatarId);
                if (fail != null && System.currentTimeMillis() - fail < RETRY_COOLDOWN_MS) return null;
                // 首次发现该 UID 时启动守护线程异步解析（add 返回 false 表示已在解析中）
                if (biliInFlight.add(avatarId)) {
                    Thread t = new Thread(() -> resolveBili(avatarId),
                            "cstmm-bili-face-" + Integer.toHexString(avatarId.hashCode()));
                    t.setDaemon(true);
                    t.start();
                }
                return null;
            }
            default -> {
                return null;
            }
        }
    }

    /**
     * 断线时清空（直链缓存与纹理按需重新解析/下载）。
     * 【被谁使用】仅被 ClientNetworkHandler 的 DISCONNECT 回调调用。
     */
    public static void clear() {
        loaded.clear();
        failedAt.clear();
        inFlight.clear();
        biliResolved.clear();
        biliFailedAt.clear();
        biliInFlight.clear();
    }

    /**
     * 取 URL 图片纹理；未下载完/失败冷却中返回 null。仅接受 http/https。
     * 【被谁使用】仅被本类 getTexture 调用。
     */
    private static Base64ImageDecoder.CardTexture get(String url) {
        if (!(url.startsWith("http://") || url.startsWith("https://"))) return null;
        Base64ImageDecoder.CardTexture tex = loaded.get(url);
        if (tex != null) return tex;
        Long fail = failedAt.get(url);
        if (fail != null && System.currentTimeMillis() - fail < RETRY_COOLDOWN_MS) return null;
        if (inFlight.add(url)) {
            Thread t = new Thread(() -> download(url), "cstmm-face-" + Integer.toHexString(url.hashCode()));
            t.setDaemon(true);
            t.start();
        }
        return null;
    }

    /**
     * 【作用】后台线程：请求 uapis 接口解析 B站 UID 的头像直链（JSON face 字段，
     *         兼容顶层与 data 内两种返回结构），成功记入 biliResolved（下一帧自动开始下载），
     *         失败记录冷却时间戳。
     * 【被谁使用】仅被本类 getTexture 启动的守护线程调用。
     */
    private static void resolveBili(String uid) {
        try {
            var conn = URI.create(String.format(BILI_API_URL, uid)).toURL().openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", "cstmm-mod-avatar");
            String face = "";
            try (InputStream in = conn.getInputStream()) {
                String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
                if (obj.has("face") && obj.get("face").isJsonPrimitive()) {
                    face = obj.get("face").getAsString();
                } else if (obj.has("data") && obj.getAsJsonObject("data").has("face")
                        && obj.getAsJsonObject("data").get("face").isJsonPrimitive()) {
                    face = obj.getAsJsonObject("data").get("face").getAsString();
                }
            }
            if (face.isEmpty()) throw new IllegalArgumentException("no face field in response");
            biliResolved.put(uid, face);
        } catch (Exception e) {
            biliFailedAt.put(uid, System.currentTimeMillis());
            Cstmm.LOGGER.warn("[CSTMM - FaceImageCache] Failed to resolve bili face for uid {}: {}", uid, e.toString());
        } finally {
            biliInFlight.remove(uid);
        }
    }

    /**
     * 【作用】后台线程：限长下载 → 解码 → 校验像素上限 → 切回渲染线程注册纹理；
     *         失败则记录冷却时间戳。
     * 【被谁使用】仅被本类 get 启动的守护线程调用。
     */
    private static void download(String url) {
        try {
            var conn = URI.create(url).toURL().openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(8000);
            // 多读 1 字节用于判断超限
            byte[] bytes;
            try (InputStream in = conn.getInputStream()) {
                bytes = in.readNBytes(MAX_BYTES + 1);
            }
            if (bytes.length > MAX_BYTES) {
                throw new IllegalArgumentException("image exceeds " + MAX_BYTES + " bytes");
            }
            NativeImage image = Base64ImageDecoder.decodeImageBytes(bytes);
            if (image.getWidth() > MAX_PIXELS || image.getHeight() > MAX_PIXELS) {
                int w = image.getWidth(), h = image.getHeight();
                image.close();
                throw new IllegalArgumentException("image too large (" + w + "x" + h + "px, max " + MAX_PIXELS + ")");
            }
            // GL 调用必须回到渲染线程；已完成任务跳过注册（并发竞态下可能重复下载，但不会重复建纹理）
            MinecraftClient.getInstance().execute(() -> {
                if (loaded.containsKey(url)) {
                    image.close();
                } else {
                    loaded.put(url, Base64ImageDecoder.registerTexture(image));
                }
            });
        } catch (Exception e) {
            failedAt.put(url, System.currentTimeMillis());
            Cstmm.LOGGER.warn("[CSTMM - FaceImageCache] Failed to load face '{}': {}", url, e.getMessage());
        } finally {
            inFlight.remove(url);
        }
    }
}
