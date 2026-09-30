package cn.woshiikun_1145.mcmod.choco.cstmm.client.cache;

import cn.woshiikun_1145.mcmod.choco.cstmm.Cstmm;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * 全服履历排行缓存（履历页下方列表数据源）。服务端 LeaderboardPayload 分片下发，
 * 本类负责分片重组与 JSON 解析；排序（K/D、胜率、总击杀）由 ProfileTabPanel 本地完成。
 * 【被谁使用】ClientNetworkHandler 的 LeaderboardPayload 接收器写入、DISCONNECT 回调清空；
 *             ProfileTabPanel 读取渲染。
 */
public class LeaderboardCache {

    // 饿汉式单例
    private static final LeaderboardCache INSTANCE = new LeaderboardCache();

    // 单例入口
    public static LeaderboardCache getInstance() { return INSTANCE; }

    // ===== 分片重组缓冲（网络线程回调写、渲染线程读） =====
    private final StringBuilder pendingJson = new StringBuilder();
    private int totalParts = -1;
    private int receivedParts = 0;

    /** 最新一次完整解析出的排行行；null = 尚未收到过数据（显示加载中），空列表 = 服务器无档案 */
    private volatile List<Row> rows = null;

    // 读取当前排行（null = 尚未收到数据）
    public List<Row> get() { return rows; }

    /**
     * 【作用】接收一个排行榜分片：首包重置缓冲，收齐全片后整体解析替换 rows。
     * 【被谁使用】仅被 ClientNetworkHandler 的 LeaderboardPayload 接收器调用。
     */
    public void apply(int partIndex, int total, String data) {
        if (partIndex == 0) {
            pendingJson.setLength(0);
            totalParts = total;
            receivedParts = 0;
        } else if (totalParts == -1) {
            Cstmm.LOGGER.warn("[CSTMM - LeaderboardCache] Missed first part, dropped");
            return;
        }
        pendingJson.append(data);
        receivedParts++;
        if (receivedParts >= totalParts) {
            String json = pendingJson.toString();
            pendingJson.setLength(0);
            totalParts = -1;
            receivedParts = 0;
            parse(json);
        }
    }

    // 解析完整 JSON（{"players":[{"name","kills","deaths","matches","wins"},...]}）
    private void parse(String json) {
        try {
            List<Row> parsed = new ArrayList<>();
            JsonElement root = JsonParser.parseString(json);
            if (root.isJsonObject() && root.getAsJsonObject().has("players")
                    && root.getAsJsonObject().get("players").isJsonArray()) {
                for (JsonElement e : root.getAsJsonObject().getAsJsonArray("players")) {
                    if (!e.isJsonObject()) continue;
                    JsonObject o = e.getAsJsonObject();
                    parsed.add(new Row(
                            str(o, "name"),
                            intOr(o, "kills"), intOr(o, "deaths"),
                            intOr(o, "matches"), intOr(o, "wins")));
                }
            }
            this.rows = parsed;
        } catch (Exception ex) {
            // 损坏 JSON：保留旧数据（静默降级，不影响页面其他部分）
            Cstmm.LOGGER.error("[CSTMM - LeaderboardCache] Failed to parse leaderboard", ex);
        }
    }

    // 断线时清空，恢复为"尚未收到数据"状态
    public void clear() {
        synchronized (this) {
            pendingJson.setLength(0);
            totalParts = -1;
            receivedParts = 0;
        }
        rows = null;
    }

    // 取字符串字段（缺失/null 回退空串）
    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    // 取整数字段（缺失/null 回退 0）
    private static int intOr(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsInt() : 0;
    }

    /** 排行榜单行（服务端只下发原始字段，K/D 与胜率本地派生） */
    public record Row(String name, int kills, int deaths, int matches, int wins) {
        // K/D（死亡为 0 按 1 兜底，与服务端 PlayerProfile.getKD 同规则）
        public double kd() {
            int d = deaths == 0 ? 1 : deaths;
            return (double) kills / d;
        }

        // 胜率（0-100，一位小数；无场次返回 0）
        public double winRate() {
            return matches > 0 ? (double) wins / matches * 100 : 0;
        }
    }
}
