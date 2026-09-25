package cn.woshiikun_1145.mcmod.choco.cstmm.client.cache;

import cn.woshiikun_1145.mcmod.choco.cstmm.Cstmm;
import com.google.gson.Gson;

import java.util.List;

/**
 * "队列"标签页状态缓存（服务端 queue_status 包解析结果，每秒刷新）。
 * 地图显示名/类型/背景图由客户端按 id 从 {@link ConfigDataCache} 解析。
 * 【被谁使用】ClientNetworkHandler 的 QueueStatusPayload 接收器写入、DISCONNECT 回调清空；
 *             QueueTabPanel#init 读取渲染队列页，MatchMenuScreen 据此显示取消匹配按钮。
 */
public class QueueStatusCache {

    // 饿汉式单例
    private static final QueueStatusCache INSTANCE = new QueueStatusCache();
    // 用于反序列化服务端 JSON 快照
    private static final Gson GSON = new Gson();

    // 单例入口
    public static QueueStatusCache getInstance() { return INSTANCE; }

    // 最新一次快照（volatile：网络线程回调写、渲染线程读）
    private volatile Snapshot snapshot = Snapshot.empty();

    // 读取当前快照（永不为 null）
    public Snapshot get() { return snapshot; }

    /**
     * 【作用】解析服务端推送的队列状态 JSON 并整体替换当前快照。
     * 【被谁使用】仅被 ClientNetworkHandler 的 QueueStatusPayload 接收器调用。
     */
    public void update(String json) {
        try {
            Snapshot s = GSON.fromJson(json, Snapshot.class);
            if (s != null) snapshot = s;
        } catch (Exception e) {
            Cstmm.LOGGER.error("[CSTMM - QueueStatusCache] Failed to parse queue status", e);
        }
    }

    // 断线时清空，恢复为空快照
    public void clear() { snapshot = Snapshot.empty(); }

    /**
     * 【作用】队列页一次性状态快照（我的排队 + 战队信息 + 全地图队列行），GSON 反序列化目标。
     * 【被谁使用】本类 update 反序列化产出；QueueTabPanel/MatchMenuScreen 读取。
     */
    public static class Snapshot {
        // 我自己的排队状态
        public Own own = new Own();
        // 我所在战队及成员的排队状态
        public ClanRow clan = new ClanRow();
        // 全部地图的队列状态行
        public List<MapRow> maps = List.of();

        // 空快照工厂（未入服/断线时的默认值）
        public static Snapshot empty() { return new Snapshot(); }

        /** 我的排队状态 */
        public static class Own {
            // 是否正在排队中
            public boolean inQueue = false;
            // 自己的头像绑定（队列页"战队徽标右侧"展示；avatarType 空串 = 未设置，图片客户端自行获取）
            public String avatarType = "";
            public String avatarId = "";
            // 排队地图 id（供客户端查配置）
            public String mapId = "";
            // 排队地图显示名
            public String map = "";
            // 匹配模式名（竞技/休闲）
            public String mode = "";
            // 当前已排队人数
            public int matched = 0;
            /** 还差多少人开局；-1 = 快速匹配（无固定门槛） */
            public int needed = 0;
        }

        /** 我所在战队的信息与成员排队明细 */
        public static class ClanRow {
            // 是否已加入战队
            public boolean inClan = false;
            // 战队全名
            public String name = "";
            // 战队缩写
            public String abbr = "";
            // 成员总数
            public int count = 0;
            // 各成员的排队明细
            public List<MemberRow> members = List.of();

            /** 单个战队成员的排队明细 */
            public static class MemberRow {
                // 成员排队中的地图显示名（空闲为 ""）
                public String map = "";
                // 成员玩家名
                public String player = "";
            }
        }

        /** AVAILABLE / IN_MATCH / DISABLED / COOLDOWN */
        public static class MapRow {
            // 地图 id
            public String id = "";
            // 地图当前状态
            public String status = "AVAILABLE";
            /** 按模式独立的队列数据（"竞技" / "休闲"），客户端每 3 秒轮换展示 */
            public List<ModeRow> modes = List.of();
        }

        /** 单个模式下的队列数据 */
        public static class ModeRow {
            // 模式名（"竞技" / "休闲"）
            public String mode = "";
            /** 排队总人数（队伍在开局时才分配，队列无蓝红之分） */
            public int count = 0;
            public List<String> players = List.of();
        }
    }
}
