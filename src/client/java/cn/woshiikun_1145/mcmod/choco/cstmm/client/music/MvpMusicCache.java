package cn.woshiikun_1145.mcmod.choco.cstmm.client.music;

import cn.woshiikun_1145.mcmod.choco.cstmm.Cstmm;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.cache.ClientCacheDirs;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.cache.ClientConfig;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.util.Base64ImageDecoder;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.util.ToastManager;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.util.UrlImageCache;
import net.minecraft.client.texture.NativeImage;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 【作用】MVP 音乐资源磁盘缓存（客户端）：目录 config/cstmm/client/cache/mvp_music/<服务器地址_端口>/，
 *         音乐存 music/<MurmurHash3>.<ext>，图标存 cover/<MurmurHash3>.<ext>（按服务器隔离）。
 *         下载完成先校验哈希，再临时文件原子替换落盘；已缓存的文件直接复用。
 *         下载走有界线程池（多线程并发，防同一资源重复下载）；索引加载完成后由
 *         prefetchAll 后台预下载全部曲目资源，个性化页显示下载中提示。
 * 【被谁使用】MvpMusicClientHandler（播放前确保音乐文件就绪）、PersonalizeTabPanel（试听与曲目图标）、
 *           MvpMusicIndex（索引加载完成后触发预下载）。
 */
public final class MvpMusicCache {

    // 正在下载中的目标文件集合（防止同一资源并发重复下载）
    private static final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    // 等待同一资源下载完成的回调（key → 回调列表；下载结束（成功/失败）统一回放，失败传 null）
    private static final Map<String, List<Consumer<Path>>> waiters = new ConcurrentHashMap<>();
    // 下载失败冷却（目标路径 → 失败时间戳），冷却期内不重试
    private static final Map<String, Long> failedAt = new ConcurrentHashMap<>();
    private static final long RETRY_COOLDOWN_MS = 60_000;
    // 已解码的图标纹理（磁盘文件 → GPU 纹理），避免每帧重复解码
    private static final Map<String, Base64ImageDecoder.CardTexture> iconTextures = new ConcurrentHashMap<>();

    // 有界下载线程池（多线程下载同时避免带宽/内存被单服务器资源拉满），常驻守护线程；
    // 初始大小取客户端配置 downloadThreads（1~32，默认 3），运行时可经 setDownloadThreads 动态调整
    private static final ThreadPoolExecutor DOWNLOAD_POOL = new ThreadPoolExecutor(
            ClientConfig.getDownloadThreads(), ClientConfig.getDownloadThreads(),
            30L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
            r -> {
                Thread t = new Thread(r, "cstmm-mvp-dl");
                t.setDaemon(true);
                return t;
            });

    // 预下载（prefetchAll）中未完成的资源数与标记集合（>0 时个性化页显示"正在下载资源"提示）
    private static final AtomicInteger prefetchPending = new AtomicInteger();
    private static final Set<String> prefetchKeys = ConcurrentHashMap.newKeySet();
    // 本轮预下载批次中是否出现过失败（决定批量结束时是否报"下载完成"，失败提示单独弹出）
    private static volatile boolean prefetchHadFailure = false;

    // 当前服务器的缓存根：<缓存根>/mvp_music/<host_port>（JOIN 时由 onServer 设置）
    private static volatile Path serverDir = null;

    private MvpMusicCache() {}

    /**
     * 【作用】JOIN 时记录服务器标识并切换缓存子目录（mvp_music/<host_port>/）。
     * 【被谁使用】ClientNetworkHandler 的 JOIN 回调（与 BadgeCache.onServer 同处）。
     */
    public static void onServer(String serverKey) {
        serverDir = ClientCacheDirs.cacheRoot()
                .resolve("mvp_music")
                .resolve(ClientCacheDirs.sanitize(serverKey));
    }

    /** 音乐子目录：.../mvp_music/<host_port>/music（未进服返回 null） */
    private static Path musicDir() {
        return serverDir == null ? null : serverDir.resolve("music");
    }

    /** 图标子目录：.../mvp_music/<host_port>/cover（未进服返回 null） */
    private static Path coverDir() {
        return serverDir == null ? null : serverDir.resolve("cover");
    }

    /** 按哈希与扩展名在指定子目录定位缓存文件（哈希非法字符会被清理；目录/哈希为空返回 null） */
    private static Path fileFor(Path dir, String hash, String ext) {
        if (dir == null || hash == null || hash.isBlank()) return null;
        String safe = hash.trim().toLowerCase().replaceAll("[^0-9a-z]", "");
        if (safe.isEmpty()) return null;
        String e = (ext == null || ext.isBlank()) ? "mp3" : ext.replaceAll("[^a-z0-9]", "");
        if (e.isEmpty()) e = "mp3";
        return dir.resolve(safe + "." + e);
    }

    /**
     * 【作用】确保曲目音乐文件已在本地缓存：已存在直接回调；否则后台下载、校验 MurmurHash3_x86_32 后落盘，
     *         回调在守护线程中执行（调用方如需操作 UI/音频线程请自行切线程）。
     * 【被谁使用】MvpMusicClientHandler、PersonalizeTabPanel。
     */
    public static void ensureMusic(MvpMusicIndex.Track track, Consumer<Path> onReady) {
        if (track == null || track.musicUrl().isEmpty()) return;
        Path dir = musicDir();
        if (dir == null) return;
        Path target = fileFor(dir, track.musicHash(), track.musicExt());
        if (target != null && Files.isRegularFile(target)) {
            onReady.accept(target);
            return;
        }
        startDownload(track.musicUrl(), track.musicHash(), track.musicExt(), dir, onReady);
    }

    /** 已缓存音乐文件（未缓存/未进服返回 null） */
    public static Path cachedMusic(MvpMusicIndex.Track track) {
        if (track == null) return null;
        Path p = fileFor(musicDir(), track.musicHash(), track.musicExt());
        return (p != null && Files.isRegularFile(p)) ? p : null;
    }

    /**
     * 【作用】确保曲目图标已缓存到 cover/（后台下载 + 哈希校验）；回调在守护线程中执行。
     * 【被谁使用】MvpMusicCache#iconTexture（渲染线程发现磁盘缺失时触发下载）。
     */
    public static void ensureIcon(MvpMusicIndex.Track track, Consumer<Path> onReady) {
        if (track == null || track.iconUrl().isEmpty()) return;
        Path dir = coverDir();
        if (dir == null) return;
        Path target = fileFor(dir, track.iconHash(), track.iconExt());
        if (target != null && Files.isRegularFile(target)) {
            onReady.accept(target);
            return;
        }
        startDownload(track.iconUrl(), track.iconHash(), track.iconExt(), dir, onReady);
    }

    /** 已缓存图标文件（未缓存/未进服返回 null） */
    public static Path cachedIcon(MvpMusicIndex.Track track) {
        if (track == null) return null;
        Path p = fileFor(coverDir(), track.iconHash(), track.iconExt());
        return (p != null && Files.isRegularFile(p)) ? p : null;
    }

    /**
     * 【作用】曲目图标纹理：优先读 cover/ 下的磁盘缓存并解码注册（纹理内存缓存，只在渲染线程调用）；
     *         磁盘缺失时后台下载并返回 null（调用方画占位，下一帧自动显示）。
     *         索引未提供图标哈希时无法按哈希落盘，退回 UrlImageCache 的 URL 直连加载。
     * 【被谁使用】PersonalizeTabPanel 的曲目列表渲染。
     */
    public static Base64ImageDecoder.CardTexture iconTexture(MvpMusicIndex.Track track) {
        if (track == null) return null;
        Path cached = cachedIcon(track);
        if (cached == null) {
            if (track.iconHash().isBlank()) return UrlImageCache.get(track.iconUrl());
            ensureIcon(track, ignored -> {});
            return null;
        }
        String key = cached.toString();
        Base64ImageDecoder.CardTexture tex = iconTextures.get(key);
        if (tex != null) return tex;
        try {
            NativeImage image = Base64ImageDecoder.decodeImageBytes(Files.readAllBytes(cached));
            tex = Base64ImageDecoder.registerTexture(image);
            iconTextures.put(key, tex);
            return tex;
        } catch (Exception e) {
            Cstmm.LOGGER.warn("[CSTMM - MvpMusicCache] Failed to decode icon cache '{}': {}",
                    cached.getFileName(), e.getMessage());
            return null;
        }
    }

    // ==================== 下载 ====================

    /** 提交后台下载（同目标并发去重 + 失败冷却 + 等待者回调回放） */
    private static void startDownload(String url, String hash, String ext, Path dir, Consumer<Path> onReady) {
        Path target = fileFor(dir, hash, ext);
        String key = target == null ? url : target.toString();
        Long fail = failedAt.get(key);
        if (fail != null && System.currentTimeMillis() - fail < RETRY_COOLDOWN_MS) return;
        waiters.computeIfAbsent(key, k -> java.util.Collections.synchronizedList(new java.util.ArrayList<>()))
                .add(onReady);
        if (inFlight.add(key)) {
            // 新下载任务实际入队时提示（同文案 3 秒去重，预下载批量入队只弹一条）
            ToastManager.show("§e正在下载MVP音乐...", 8000);
            DOWNLOAD_POOL.submit(() -> download(url, hash, ext, dir, key));
        }
    }

    private static void download(String url, String expectedHash, String ext, Path dir, String key) {
        Path target = null;
        Path tmp = null;
        try {
            var conn = URI.create(url).toURL().openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);
            byte[] bytes;
            try (InputStream in = conn.getInputStream()) {
                bytes = in.readAllBytes();
            }

            String actualHash = MvpMusicIndex.murmur3Hex(bytes);
            // 有哈希时校验；不一致视为资源损坏（不落盘、不播放）
            if (expectedHash != null && !expectedHash.isBlank() && !sameHash(expectedHash, actualHash)) {
                throw new IllegalStateException("MurmurHash3 mismatch: expected " + expectedHash + ", got " + actualHash);
            }

            target = fileFor(dir, expectedHash != null && !expectedHash.isBlank() ? expectedHash : actualHash, ext);
            if (target == null) throw new IllegalStateException("invalid cache file name");
            Files.createDirectories(target.getParent());
            tmp = Files.createTempFile(target.getParent(), "mvp_", ".part");
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomicFail) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            tmp = null;
            failedAt.remove(target.toString());
            Cstmm.LOGGER.info("[CSTMM - MvpMusicCache] Cached {} -> {}", url, target.getFileName());
            // 音乐文件按需下载（非预下载批量）完成时提示；预下载完成由个性化页"正在下载资源"提示消退表达
            if (dir.equals(musicDir()) && !prefetchKeys.contains(key)) {
                ToastManager.show("§aMVP音乐下载完成。");
            }
        } catch (Exception e) {
            failedAt.put(key, System.currentTimeMillis());
            Cstmm.LOGGER.warn("[CSTMM - MvpMusicCache] Failed to download '{}': {}", url, e.getMessage());
            // 预下载批次内失败：标记本轮批次，批量结束不再报"下载完成"（失败提示栏单独弹出）
            if (prefetchKeys.contains(key)) prefetchHadFailure = true;
            // 缓存失败弹顶部提示栏（主题色；同文案 3 秒去重，预下载批量失败不刷屏）
            ToastManager.show("MVP 资源缓存失败，请检查网络或稍后重试");
        } finally {
            if (tmp != null) {
                try { Files.deleteIfExists(tmp); } catch (Exception ignored) {}
            }
            inFlight.remove(key);
            // 预下载标记：无论成败都计入完成（UI 提示消退）；整批无失败时最后一个完成提示"下载完成"
            if (prefetchKeys.remove(key) && prefetchPending.decrementAndGet() == 0) {
                ToastManager.dismiss("§e正在下载MVP音乐...");
                if (!prefetchHadFailure) {
                    ToastManager.show("§aMVP音乐下载完成。");
                }
            }
            // 回放等待者：成功传落盘路径，失败传 null（调用方均对 null 安全）
            List<Consumer<Path>> callbacks = waiters.remove(key);
            if (callbacks != null) {
                for (Consumer<Path> cb : callbacks) {
                    try {
                        cb.accept(target);
                    } catch (Exception ignored) {}
                }
            }
        }
    }

    /** 比较两个 32 位十六进制哈希（忽略大小写与前导零） */
    private static boolean sameHash(String expected, String actual) {
        try {
            return Integer.parseUnsignedInt(expected.trim(), 16) == Integer.parseUnsignedInt(actual, 16);
        } catch (NumberFormatException e) {
            return expected.trim().equalsIgnoreCase(actual);
        }
    }

    // ==================== 预下载 ====================

    /**
     * 【作用】索引加载完成后预下载全部曲目资源（音乐 + 图标；已缓存/冷却中/下载中跳过），
     *         3 线程并发下载，未完成数供个性化页显示"正在下载资源"提示。
     * 【被谁使用】MvpMusicIndex#load（清单解析成功后）。
     */
    public static void prefetchAll(List<MvpMusicIndex.Track> tracks) {
        if (tracks == null || tracks.isEmpty() || musicDir() == null) return;
        int queued = 0;
        for (MvpMusicIndex.Track track : tracks) {
            queued += prefetchOne(track.musicUrl(), track.musicHash(), track.musicExt(), musicDir());
            queued += prefetchOne(track.iconUrl(), track.iconHash(), track.iconExt(), coverDir());
        }
        if (queued > 0) {
            Cstmm.LOGGER.info("[CSTMM - MvpMusicCache] Prefetching {} resources ({} pending total)",
                    queued, prefetchPending.get());
            prefetchHadFailure = false;
            // 预下载批次开始提示（8 秒；全部完成后由 download finally 替换为"下载完成"）
            ToastManager.show("§e正在下载MVP音乐...", 8000);
        }
    }

    /** 预下载单个资源：未缓存才排队；返回是否实际排队 */
    private static int prefetchOne(String url, String hash, String ext, Path dir) {
        if (url == null || url.isEmpty() || dir == null) return 0;
        Path target = fileFor(dir, hash, ext);
        if (target == null || Files.isRegularFile(target)) return 0;
        String key = target.toString();
        Long fail = failedAt.get(key);
        if (fail != null && System.currentTimeMillis() - fail < RETRY_COOLDOWN_MS) return 0;
        if (!inFlight.add(key)) return 0;
        prefetchKeys.add(key);
        prefetchPending.incrementAndGet();
        DOWNLOAD_POOL.submit(() -> download(url, hash, ext, dir, key));
        return 1;
    }

    /** 是否仍有预下载未完成（个性化页"正在下载资源"提示条件） */
    public static boolean isPrefetching() {
        return prefetchPending.get() > 0;
    }

    /**
     * 【作用】运行时调整下载线程数（1~32，超界钳制）：线程池核心/最大并发即时生效，
     *         排队中的任务按新并发继续执行。
     * 【被谁使用】PersonalizeTabPanel 控制区（下载线程数滑块/输入框）。
     */
    public static synchronized void setDownloadThreads(int threads) {
        int n = Math.max(1, Math.min(32, threads));
        if (n == DOWNLOAD_POOL.getCorePoolSize() && n == DOWNLOAD_POOL.getMaximumPoolSize()) return;
        // 先扩 max 再扩 core / 先缩 core 再缩 max，保证任意中间态满足 core ≤ max
        if (n > DOWNLOAD_POOL.getMaximumPoolSize()) {
            DOWNLOAD_POOL.setMaximumPoolSize(n);
            DOWNLOAD_POOL.setCorePoolSize(n);
        } else {
            DOWNLOAD_POOL.setCorePoolSize(n);
            DOWNLOAD_POOL.setMaximumPoolSize(n);
        }
        Cstmm.LOGGER.info("[CSTMM - MvpMusicCache] Download threads set to {}", n);
    }

    /** 断线/退出时清理内存态（磁盘缓存保留；进行中的下载照常完成，仅取消 UI 提示） */
    public static void clearRuntime() {
        inFlight.clear();
        failedAt.clear();
        waiters.clear();
        iconTextures.clear();
        prefetchKeys.clear();
        prefetchPending.set(0);
    }
}
