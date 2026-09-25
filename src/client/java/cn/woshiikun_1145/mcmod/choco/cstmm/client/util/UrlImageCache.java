package cn.woshiikun_1145.mcmod.choco.cstmm.client.util;

import cn.woshiikun_1145.mcmod.choco.cstmm.Cstmm;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.cache.BadgeCache;
import cn.woshiikun_1145.mcmod.choco.cstmm.manager.ClanManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;

import java.io.InputStream;
import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 图片 URL → 纹理 的异步下载缓存（URL 战队徽标专用）。
 * URL 徽标不经过服务器分片下发（不占服务器带宽），客户端按 clan JSON 中的 URL 自行下载：
 * 下载大小上限与 base64 徽标一致（ClanManager.MAX_BADGE_BYTES = 48KiB），像素上限 1024
 * （防止小图解压出超大纹理占满显存），失败后 60s 冷却内不重试。
 * 纹理注册（GL 调用）通过 MinecraftClient.execute 切回渲染线程执行。
 * 【被谁使用】ClanTabPanel、QueueTabPanel 经 resolveBadgeTexture 渲染徽标；
 *             ClientNetworkHandler 的 DISCONNECT 回调调用 clear()。
 */
public final class UrlImageCache {

    // 工具类，禁止实例化
    private UrlImageCache() {}

    /** 下载/解码的像素尺寸上限（宽或高任一超过即拒绝） */
    private static final int MAX_PIXELS = 1024;
    /** 下载失败后的重试冷却 */
    private static final long RETRY_COOLDOWN_MS = 60_000;

    // 已加载完成的纹理（url -> 纹理）
    private static final Map<String, Base64ImageDecoder.CardTexture> loaded = new ConcurrentHashMap<>();
    // 下载失败的记录（url -> 失败时间戳，冷却期内不重试）
    private static final Map<String, Long> failedAt = new ConcurrentHashMap<>();
    // 正在下载中的 url 集合（防止重复开线程）
    private static final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    /**
     * 取 URL 图片纹理；未下载完/失败冷却中返回 null（调用方画占位，加载完成后下一帧自动显示）。
     * 仅接受 http/https，其他值一律返回 null。
     * 【被谁使用】仅被本类 resolveBadgeTexture 调用（对外统一入口是 resolveBadgeTexture）。
     */
    public static Base64ImageDecoder.CardTexture get(String url) {
        if (!ClanManager.isBadgeUrl(url)) return null;
        Base64ImageDecoder.CardTexture tex = loaded.get(url);
        if (tex != null) return tex;
        // 失败冷却期内不重试，避免每帧重复请求
        Long fail = failedAt.get(url);
        if (fail != null && System.currentTimeMillis() - fail < RETRY_COOLDOWN_MS) return null;
        // 【作用】首次发现该 URL 时启动守护线程异步下载（add 返回 false 表示已在下载中）
        if (inFlight.add(url)) {
            Thread t = new Thread(() -> download(url), "cstmm-badge-url-" + Integer.toHexString(url.hashCode()));
            t.setDaemon(true);
            t.start();
        }
        return null;
    }

    /**
     * 徽标字段 → 纹理的统一入口：URL 徽标走本类下载缓存，
     * base64 徽标（badge 字段为内容寻址 id）走分片缓存 + base64 解码。
     * 战队详情/战队页/队列页三处徽标渲染共用，保证 URL 徽标全界面生效。
     * 【被谁使用】ClanTabPanel（第 463 行）、QueueTabPanel（第 311 行）渲染徽标。
     */
    public static Base64ImageDecoder.CardTexture resolveBadgeTexture(String badgeValue) {
        if (badgeValue == null || badgeValue.isEmpty()) return null;
        if (ClanManager.isBadgeUrl(badgeValue)) return get(badgeValue);
        String base64 = BadgeCache.get(badgeValue);
        return (base64 == null || base64.isEmpty()) ? null : Base64ImageDecoder.decode(base64);
    }

    /**
     * 断线时清空（URL 徽标重连后按需重新下载）。
     * 【被谁使用】仅被 ClientNetworkHandler 的 DISCONNECT 回调调用。
     */
    public static void clear() {
        loaded.clear();
        failedAt.clear();
        inFlight.clear();
    }

    /**
     * 【作用】后台线程执行：限长下载 → 解码 → 校验像素上限 → 切回渲染线程注册纹理；
     *         失败则记录冷却时间戳。
     * 【被谁使用】仅被本类 get 启动的守护线程调用。
     */
    private static void download(String url) {
        try {
            var conn = URI.create(url).toURL().openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            // 大小上限与 base64 徽标一致：多读 1 字节用于判断超限
            byte[] bytes;
            try (InputStream in = conn.getInputStream()) {
                bytes = in.readNBytes(ClanManager.MAX_BADGE_BYTES + 1);
            }
            if (bytes.length > ClanManager.MAX_BADGE_BYTES) {
                throw new IllegalArgumentException("image exceeds " + ClanManager.MAX_BADGE_BYTES + " bytes");
            }
            NativeImage image = Base64ImageDecoder.decodeImageBytes(bytes);
            // 【作用】校验像素尺寸，防止小体积图片解压出超大纹理占满显存
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
            Cstmm.LOGGER.warn("[CSTMM - UrlImageCache] Failed to load badge URL '{}': {}", url, e.getMessage());
        } finally {
            inFlight.remove(url);
        }
    }
}
