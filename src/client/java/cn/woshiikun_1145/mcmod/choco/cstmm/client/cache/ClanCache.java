package cn.woshiikun_1145.mcmod.choco.cstmm.client.cache;

import cn.woshiikun_1145.mcmod.choco.cstmm.Cstmm;
import com.google.gson.Gson;

import java.util.List;

/**
 * 战队页状态缓存（服务端 clan_data 包解析结果）。
 * kind: MINE（我的战队）/ LIST（随机或搜索战队列表）/ DETAIL（选中战队详情）。
 */
public class ClanCache {

    private static final ClanCache INSTANCE = new ClanCache();
    private static final Gson GSON = new Gson();

    public static ClanCache getInstance() { return INSTANCE; }

    private volatile Mine mine = new Mine();
    private volatile ListData list = new ListData();
    private volatile ClanInfo selectedDetail = null;

    public Mine getMine() { return mine; }
    public ListData getList() { return list; }
    public ClanInfo getSelectedDetail() { return selectedDetail; }

    public void update(String kind, String json) {
        try {
            switch (kind) {
                case "MINE" -> {
                    Mine m = GSON.fromJson(json, Mine.class);
                    if (m != null) mine = m;
                }
                case "LIST" -> {
                    ListData l = GSON.fromJson(json, ListData.class);
                    if (l != null) list = l;
                }
                case "DETAIL" -> {
                    Detail d = GSON.fromJson(json, Detail.class);
                    selectedDetail = (d != null && d.found) ? d.clan : null;
                }
                default -> Cstmm.LOGGER.warn("[CSTMM - ClanCache] Unknown clan data kind: {}", kind);
            }
        } catch (Exception e) {
            Cstmm.LOGGER.error("[CSTMM - ClanCache] Failed to parse clan data (" + kind + ")", e);
        }
    }

    public void clear() {
        mine = new Mine();
        list = new ListData();
        selectedDetail = null;
    }

    public static class Mine {
        public boolean inClan = false;
        public ClanInfo clan = null;
    }

    public static class ListData {
        public List<ListRow> clans = List.of();

        public static class ListRow {
            public String name = "";
            public String abbr = "";
            public String leaderName = "";
            public int memberCount = 0;
            public int limit = 0;
        }
    }

    public static class Detail {
        public boolean found = false;
        public ClanInfo clan = null;
    }

    public static class ClanInfo {
        public String name = "";
        public String abbr = "";
        public String badge = "";
        public String leaderName = "";
        public int memberCount = 0;
        public int limit = 0;
        public List<MemberEntry> members = List.of();

        public static class MemberEntry {
            public String name = "";
            public boolean isLeader = false;
            /** 是否在线（离线名字灰色） */
            public boolean online = false;
            /** 匹配状态："" = 空闲，否则 "地图显示名-模式名" */
            public String matchState = "";
        }
    }
}
