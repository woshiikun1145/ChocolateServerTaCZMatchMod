package cn.woshiikun_1145.mcmod.choco.cstmm.client.cache;

import cn.woshiikun_1145.mcmod.choco.cstmm.Cstmm;
import cn.woshiikun_1145.mcmod.choco.cstmm.network.payload.BadgeKnownPayload;
import cn.woshiikun_1145.mcmod.choco.cstmm.network.payload.BadgePayload;
import cn.woshiikun_1145.mcmod.choco.cstmm.network.payload.RequestBadgePayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 战队徽标分片缓存（客户端，内存 + 磁盘两级，按服务器隔离）。
 * 服务端 clan_data JSON 的 badge 字段携带内容寻址 id（SHA-256 前 8 字节 hex），
 * 完整 base64 由 BadgePayload 分片下发，此处按 id 重组缓存并落盘
 * （config/cstmm/client/cache/badges/<host_port>/<id>.b64，内容寻址天然幂等，跨重连/跨启动复用）；
 * 重连进服时 ClientNetworkHandler 用 diskBadgeIds() 把磁盘已有的 id 上报服务端
 * （BadgeKnownPayload），服务端跳过分片重发；收到引用未知 id 的 clan_data 时
 * 先查磁盘、确实没有才发 RequestBadgePayload 请求补发。
 * 【被谁使用】ClientNetworkHandler 的 BadgePayload/ClanDataPayload 接收器、JOIN 回调
 *             （onServer 记录服务器标识 + 已知徽标上报）与 DISCONNECT 回调；
 *             UrlImageCache、ClanTabPanel 读取徽标 base64 用于渲染。
 */
@Environment(EnvType.CLIENT)
public class BadgeCache {

    // 当前服务器的徽标磁盘缓存目录：<缓存根>/badges/<host_port>（JOIN 时由 onServer 设置）
    private static volatile Path diskDir = null;

    // 未集齐分片的徽标缓冲（id -> 分片重组器）
    private static final Map<String, Pending> pending = new ConcurrentHashMap<>();
    // 已集齐的完整徽标（id -> 完整 base64 字符串；仅内存级，磁盘由 diskDir 承载）
    private static final Map<String, String> complete = new ConcurrentHashMap<>();
    // 已处理过的补发请求 id 集合（防止重复请求/重复读盘）
    private static final Set<String> requested = ConcurrentHashMap.newKeySet();

    /**
     * 【作用】JOIN 时记录当前服务器标识并切换徽标磁盘子目录（badges/<host_port>）。
     * 【被谁使用】ClientNetworkHandler 的 ClientPlayConnectionEvents.JOIN 回调（在 diskBadgeIds 上报之前）。
     */
    public static void onServer(String serverKey) {
        diskDir = ClientCacheDirs.cacheRoot()
                .resolve("badges")
                .resolve(ClientCacheDirs.sanitize(serverKey));
    }

    /** 未集齐的徽标分片 */
    private static final class Pending {
        // 该徽标的总片数
        final int totalParts;
        // 各分片内容（未收到的下标为 null）
        final String[] parts;
        // 已收到的分片数
        int receivedCount;

        Pending(int totalParts) {
            this.totalParts = totalParts;
            this.parts = new String[totalParts];
        }
    }

    /**
     * 应用一枚分片；id 全部到齐后进入 complete 缓存并落盘（下次进服经上报免重发）。
     * 【被谁使用】仅被 ClientNetworkHandler 的 BadgePayload 接收器调用。
     */
    public static void apply(BadgePayload p) {
        String id = p.badgeId();
        if (id == null || id.isEmpty()) return;
        // 【作用】校验分片头信息合法性（含 id 格式白名单），非法分片直接丢弃
        if (!BadgePayload.isValidBadgeId(id)
                || p.totalParts() < 1 || p.totalParts() > BadgePayload.MAX_TOTAL_PARTS
                || p.partIndex() < 0 || p.partIndex() >= p.totalParts()) {
            Cstmm.LOGGER.warn("[CSTMM - BadgeCache] Invalid badge chunk (id={}, index={}, total={})", id, p.partIndex(), p.totalParts());
            return;
        }
        // 已有完整徽标则忽略后续分片
        if (complete.containsKey(id)) return;
        Pending buf = pending.computeIfAbsent(id, k -> new Pending(p.totalParts()));
        // 服务端总片数变化（内容更新）时重建缓冲
        if (buf.totalParts != p.totalParts()) {
            buf = new Pending(p.totalParts());
            pending.put(id, buf);
        }
        // 重复分片只计入一次
        if (buf.parts[p.partIndex()] == null) {
            buf.parts[p.partIndex()] = p.data();
            buf.receivedCount++;
        }
        // 【作用】全部分片到齐后按序拼接 base64、移入完整缓存并落盘
        if (buf.receivedCount == p.totalParts()) {
            pending.remove(id);
            StringBuilder sb = new StringBuilder();
            for (String part : buf.parts) sb.append(part);
            String base64 = sb.toString();
            complete.put(id, base64);
            writeToDisk(id, base64);
        }
    }

    /**
     * 取完整徽标 base64；未到齐返回 null（调用方画占位，下一帧自动恢复）。仅查内存——
     * 渲染每帧高频调用，磁盘命中已由 requestIfMissing 在收到 clan_data 时提前载入内存。
     * 【被谁使用】UrlImageCache（URL 不可用时的本地回退）、ClanTabPanel 渲染徽标。
     */
    public static String get(String badgeId) {
        return badgeId == null ? null : complete.get(badgeId);
    }

    /** 本地是否已有完整徽标数据（当前无调用方，预留查询接口） */
    public static boolean has(String badgeId) {
        return badgeId != null && complete.containsKey(badgeId);
    }

    /**
     * clan_data 引用了本地没有的徽标时：先查磁盘缓存（命中载入内存，免网络），确实没有才发
     * 补发请求（每个 id 只处理一次）；URL 徽标由客户端自行下载，不请求。
     * 【被谁使用】ClientNetworkHandler 的 ClanDataPayload 接收器（收到 MINE/DETAIL 数据后检查）。
     */
    public static void requestIfMissing(String badgeId) {
        if (badgeId == null || badgeId.isEmpty() || complete.containsKey(badgeId)) return;
        if (cn.woshiikun_1145.mcmod.choco.cstmm.manager.ClanManager.isBadgeUrl(badgeId)) return;
        // requested 兼作"该 id 已处理过"标记：无论最终是磁盘命中还是发出补发请求，都不再重复处理
        if (!requested.add(badgeId)) return;
        String disk = loadFromDisk(badgeId);
        if (disk != null) {
            complete.put(badgeId, disk);
            requested.remove(badgeId);
            return;
        }
        ClientPlayNetworking.send(new RequestBadgePayload(badgeId));
    }

    /**
     * 列出当前服务器磁盘缓存目录中全部合法徽标 id（进服上报服务端用，服务端据此跳过重发）。
     * 按 id 格式白名单过滤（防恶意构造的文件名混入），数量封顶 {@link BadgeKnownPayload} 上限。
     * 【被谁使用】ClientNetworkHandler 的 JOIN 回调（BadgeKnownPayload 上报）。
     */
    public static List<String> diskBadgeIds() {
        List<String> ids = new ArrayList<>();
        Path dir = diskDir;
        if (dir == null) return ids;
        try {
            if (!Files.isDirectory(dir)) return ids;
            try (var stream = Files.list(dir)) {
                for (Path p : stream.filter(x -> x.getFileName().toString().endsWith(".b64")).toList()) {
                    String name = p.getFileName().toString();
                    String id = name.substring(0, name.length() - 4);
                    if (BadgePayload.isValidBadgeId(id)) {
                        ids.add(id);
                        if (ids.size() >= BadgeKnownPayload.MAX_IDS) break;
                    }
                }
            }
        } catch (IOException e) {
            Cstmm.LOGGER.warn("[CSTMM - BadgeCache] Failed to list disk badges: {}", e.toString());
        }
        return ids;
    }

    /**
     * 断线时清空内存级缓存（分片重组缓冲/完整徽标/请求标记）；磁盘缓存保留，
     * 重连后经 JOIN 上报 + requestIfMissing 读盘恢复，无需重新下载。
     * 【被谁使用】仅被 ClientNetworkHandler 的 DISCONNECT 回调调用。
     */
    public static void clear() {
        pending.clear();
        complete.clear();
        requested.clear();
    }

    // ==================== 磁盘读写 ====================

    // 重组完成后落盘到当前服务器子目录（原子写：.tmp 再替换）；badgeId 已经过 hex 白名单校验，文件名安全。
    // 失败仅告警：磁盘缓存是纯优化，写不进去只是下次多收一次分片
    private static void writeToDisk(String id, String base64) {
        Path dir = diskDir;
        if (dir == null) return;
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve(id + ".b64");
            Path tmp = dir.resolve(id + ".b64.tmp");
            Files.writeString(tmp, base64, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            Cstmm.LOGGER.warn("[CSTMM - BadgeCache] Failed to store badge {}: {}", id, e.toString());
        }
    }

    // 从当前服务器子目录读取徽标 base64；文件缺失/损坏/内容为空返回 null（调用方回退请求补发）
    private static String loadFromDisk(String id) {
        Path dir = diskDir;
        if (dir == null || !BadgePayload.isValidBadgeId(id)) return null;
        try {
            Path file = dir.resolve(id + ".b64");
            if (!Files.exists(file)) return null;
            String base64 = Files.readString(file, StandardCharsets.UTF_8);
            return base64.isEmpty() ? null : base64;
        } catch (IOException e) {
            Cstmm.LOGGER.warn("[CSTMM - BadgeCache] Failed to load badge {}: {}", id, e.toString());
            return null;
        }
    }
}
