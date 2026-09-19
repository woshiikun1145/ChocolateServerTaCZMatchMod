package cn.woshiikun_1145.mcmod.choco.cstmm.client.cache;

import cn.woshiikun_1145.mcmod.choco.cstmm.Cstmm;
import cn.woshiikun_1145.mcmod.choco.cstmm.network.payload.BadgePayload;
import cn.woshiikun_1145.mcmod.choco.cstmm.network.payload.RequestBadgePayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 战队徽标分片缓存（客户端）。
 * 服务端 clan_data JSON 的 badge 字段携带内容寻址 id（SHA-256 前 8 字节 hex），
 * 完整 base64 由 BadgePayload 分片下发，此处按 id 重组缓存；
 * 收到引用未知 id 的 clan_data 时自动发 RequestBadgePayload 请求补发（覆盖重连后缓存丢失）。
 */
public class BadgeCache {

    private static final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private static final Map<String, String> complete = new ConcurrentHashMap<>();
    private static final Set<String> requested = ConcurrentHashMap.newKeySet();

    /** 未集齐的徽标分片 */
    private static final class Pending {
        final int totalParts;
        final String[] parts;
        int receivedCount;

        Pending(int totalParts) {
            this.totalParts = totalParts;
            this.parts = new String[totalParts];
        }
    }

    /** 应用一枚分片；id 全部到齐后进入 complete 缓存 */
    public static void apply(BadgePayload p) {
        String id = p.badgeId();
        if (id == null || id.isEmpty()) return;
        if (p.totalParts() < 1 || p.totalParts() > BadgePayload.MAX_TOTAL_PARTS
                || p.partIndex() < 0 || p.partIndex() >= p.totalParts()) {
            Cstmm.LOGGER.warn("[CSTMM - BadgeCache] Invalid badge chunk (id={}, index={}, total={})", id, p.partIndex(), p.totalParts());
            return;
        }
        if (complete.containsKey(id)) return;
        Pending buf = pending.computeIfAbsent(id, k -> new Pending(p.totalParts()));
        if (buf.totalParts != p.totalParts()) {
            buf = new Pending(p.totalParts());
            pending.put(id, buf);
        }
        if (buf.parts[p.partIndex()] == null) {
            buf.parts[p.partIndex()] = p.data();
            buf.receivedCount++;
        }
        if (buf.receivedCount == p.totalParts()) {
            pending.remove(id);
            StringBuilder sb = new StringBuilder();
            for (String part : buf.parts) sb.append(part);
            complete.put(id, sb.toString());
        }
    }

    /** 取完整徽标 base64；未到齐返回 null（调用方画占位，下一帧自动恢复） */
    public static String get(String badgeId) {
        return badgeId == null ? null : complete.get(badgeId);
    }

    /** 本地是否已有完整徽标数据 */
    public static boolean has(String badgeId) {
        return badgeId != null && complete.containsKey(badgeId);
    }

    /** clan_data 引用了本地没有的徽标时请求服务端补发（每个 id 只请求一次） */
    public static void requestIfMissing(String badgeId) {
        if (badgeId == null || badgeId.isEmpty() || complete.containsKey(badgeId)) return;
        if (!requested.add(badgeId)) return;
        ClientPlayNetworking.send(new RequestBadgePayload(badgeId));
    }

    /** 断线时清空（徽标 id 是内容寻址，重连后重新请求即可） */
    public static void clear() {
        pending.clear();
        complete.clear();
        requested.clear();
    }
}
