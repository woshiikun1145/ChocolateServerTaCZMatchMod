package cn.woshiikun_1145.mcmod.choco.cstmm.client.cache;

import cn.woshiikun_1145.mcmod.choco.cstmm.Cstmm;
import com.google.gson.Gson;

import java.util.List;

/**
 * "队列"标签页状态缓存（服务端 queue_status 包解析结果，每秒刷新）。
 * 地图显示名/类型/背景图由客户端按 id 从 {@link ConfigDataCache} 解析。
 */
public class QueueStatusCache {

    private static final QueueStatusCache INSTANCE = new QueueStatusCache();
    private static final Gson GSON = new Gson();

    public static QueueStatusCache getInstance() { return INSTANCE; }

    private volatile Snapshot snapshot = Snapshot.empty();

    public Snapshot get() { return snapshot; }

    public void update(String json) {
        try {
            Snapshot s = GSON.fromJson(json, Snapshot.class);
            if (s != null) snapshot = s;
        } catch (Exception e) {
            Cstmm.LOGGER.error("[CSTMM - QueueStatusCache] Failed to parse queue status", e);
        }
    }

    public void clear() { snapshot = Snapshot.empty(); }

    public static class Snapshot {
        public Own own = new Own();
        public ClanRow clan = new ClanRow();
        public List<MapRow> maps = List.of();

        public static Snapshot empty() { return new Snapshot(); }

        public static class Own {
            public boolean inQueue = false;
            public String mapId = "";
            public String map = "";
            public String mode = "";
            public int matched = 0;
            /** 还差多少人开局；-1 = 快速匹配（无固定门槛） */
            public int needed = 0;
        }

        public static class ClanRow {
            public boolean inClan = false;
            public String name = "";
            public String abbr = "";
            public int count = 0;
            public List<MemberRow> members = List.of();

            public static class MemberRow {
                public String map = "";
                public String player = "";
            }
        }

        /** AVAILABLE / IN_MATCH / DISABLED / COOLDOWN */
        public static class MapRow {
            public String id = "";
            public String status = "AVAILABLE";
            /** 按模式独立的队列数据（"竞技" / "休闲"），客户端每 3 秒轮换展示 */
            public List<ModeRow> modes = List.of();
        }

        public static class ModeRow {
            public String mode = "";
            public int red = 0;
            public int blue = 0;
            public List<String> players = List.of();
        }
    }
}
