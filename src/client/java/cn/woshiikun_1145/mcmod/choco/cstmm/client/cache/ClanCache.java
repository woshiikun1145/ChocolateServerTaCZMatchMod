package cn.woshiikun_1145.mcmod.choco.cstmm.client.cache;

import cn.woshiikun_1145.mcmod.choco.cstmm.Cstmm;
import com.google.gson.Gson;

import java.util.List;

/**
 * 战队页状态缓存（服务端 clan_data 包解析结果）。
 * kind: MINE（我的战队）/ LIST（随机或搜索战队列表）/ DETAIL（选中战队详情）。
 * 【被谁使用】ClientNetworkHandler 的 ClanDataPayload 接收器写入、DISCONNECT 回调清空；
 *             ClanTabPanel、QueueTabPanel 读取渲染战队相关界面。
 */
public class ClanCache {

    // 饿汉式单例
    private static final ClanCache INSTANCE = new ClanCache();
    // 用于反序列化服务端 JSON
    private static final Gson GSON = new Gson();

    // 单例入口
    public static ClanCache getInstance() { return INSTANCE; }

    // 我的战队状态（未入服/未加入时为默认值）
    private volatile Mine mine = new Mine();
    // 战队列表页数据（随机/搜索结果）
    private volatile ListData list = new ListData();
    // 当前选中查看详情的战队；null 表示未选中或未找到
    private volatile ClanInfo selectedDetail = null;

    // 读取我的战队状态
    public Mine getMine() { return mine; }
    // 读取战队列表数据
    public ListData getList() { return list; }
    // 读取当前选中的战队详情（可能为 null）
    public ClanInfo getSelectedDetail() { return selectedDetail; }

    /**
     * 【作用】按 kind（MINE/LIST/DETAIL）解析服务端推送的战队 JSON 并更新对应缓存。
     * 【被谁使用】仅被 ClientNetworkHandler 的 ClanDataPayload 接收器调用。
     */
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

    // 断线时清空全部战队缓存
    public void clear() {
        mine = new Mine();
        list = new ListData();
        selectedDetail = null;
    }

    /** 我的战队状态（GSON 反序列化目标） */
    public static class Mine {
        // 是否已加入战队
        public boolean inClan = false;
        // 所在战队信息；未加入为 null
        public ClanInfo clan = null;
    }

    /** 战队列表页数据 */
    public static class ListData {
        // 列表各行战队摘要
        public List<ListRow> clans = List.of();

        /** 列表中单个战队的摘要行 */
        public static class ListRow {
            public String name = "";
            public String abbr = "";
            public String leaderName = "";
            public int memberCount = 0;
            public int limit = 0;
        }
    }

    /** 详情查询结果（found=false 表示服务端未找到该战队） */
    public static class Detail {
        public boolean found = false;
        public ClanInfo clan = null;
    }

    /** 战队完整信息 */
    public static class ClanInfo {
        public String name = "";
        public String abbr = "";
        // 战队徽标：内容寻址 id（走 BadgeCache 分片缓存）或 URL（客户端自行下载）
        public String badge = "";
        public String leaderName = "";
        public int memberCount = 0;
        public int limit = 0;
        // 成员列表
        public List<MemberEntry> members = List.of();

        /** 单个成员信息 */
        public static class MemberEntry {
            public String name = "";
            public boolean isLeader = false;
            /** 是否在线（离线名字灰色） */
            public boolean online = false;
            /** 匹配状态："" = 空闲，否则 "地图显示名-模式名" */
            public String matchState = "";
            /** 成员头像绑定（个性化设置；avatarType 空串 = 未设置；图片由客户端按绑定自行获取） */
            public String avatarType = "";
            public String avatarId = "";
        }
    }
}
