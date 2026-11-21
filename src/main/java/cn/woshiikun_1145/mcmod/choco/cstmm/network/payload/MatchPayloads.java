package cn.woshiikun_1145.mcmod.choco.cstmm.network.payload;

import cn.woshiikun_1145.mcmod.choco.cstmm.data.config.GlobalConfig;
import cn.woshiikun_1145.mcmod.choco.cstmm.network.NetworkHandler;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * 【作用】对局/匹配域 payload 容器：集中存放对局 HUD、对局状态、匹配动作、队列状态、弹窗、
 *         玩家档案、履历排行、商店数据、MVP 音乐与个性化设置相关包。
 *         由原 HudDataPayload.java、MatchStatusPayload.java、MatchActionPayload.java、
 *         QueueStatusPayload.java、RequestQueueStatusPayload.java、PopupPayload.java、
 *         PlayerProfilePayload.java、LeaderboardPayload.java、ShopDataS2CPayload.java、
 *         MvpMusicPayload.java、SetMvpMusicPayload.java、SetFacePayload.java 合并而来，
 *         各嵌套类型行为与网络字节格式与拆分前完全一致（ID/CODEC 未改动）。
 */
public final class MatchPayloads {

    // 【作用】工具容器，禁止实例化
    private MatchPayloads() {
    }

    /**
     * 【作用】对局 HUD 快照（地图名/红蓝击杀数/剩余秒数/阶段/花名册 JSON），驱动客户端顶部
     *         CS2 风格队伍栏显示；mapName 为空且 inGame=false 表示清空 HUD（对局结束或玩家退出）。
     *         rosterJson 为本局玩家花名册（紧凑 JSON 数组，元素字段见 MatchManager#buildRosterJson）：
     *         uuid/名字/队伍/个人击杀/死亡/上一条命击杀/战队缩写/徽标引用/头像绑定，
     *         供客户端渲染左右两队玩家卡片（血量与存活状态由客户端本地读取，不在包内）。
     * 【被谁使用】S2C（服务端→客户端）。服务端 MatchManager#broadcastHudData 每秒为对局内玩家构造，
     * 经 NetworkHandler#sendHudData（快照去重后）发送；客户端 ClientNetworkHandler 注册接收器
     * → HudOverlay#updateData 更新显示。
     */
    public record HudDataPayload(
            String mapName,
            int redKills,
            int blueKills,
            int remainingSeconds,
            boolean inGame,
            int phase,
            String rosterJson,
            String extraJson
    ) implements CustomPayload {

        // 【作用】包类型标识（cstmm:hud_data），由 NetworkHandler 注册并由网络层路由
        public static final Id<HudDataPayload> ID = new Id<>(
                Identifier.of("cstmm", "hud_data")
        );

        // 花名册 JSON 字节上限（与 clan_data 一致；服务端构建时已按此截断，此处仅为协议兜底）
        private static final int MAX_ROSTER_BYTES = 65536;
        // 扩展信息 JSON 字节上限（爆破回合状态/经济字段，体量小）
        private static final int MAX_EXTRA_BYTES = 4096;

        public static final PacketCodec<PacketByteBuf, HudDataPayload> CODEC = PacketCodec.of(
                (payload, buf) -> {
                    // mapName 上限 64 UTF-8 字节：writeString 校验的是编码后字节数，
                    // encode 时按字节截断（不切断多字节字符）确保永不抛异常
                    buf.writeString(NetworkHandler.truncateByUtf8Bytes(payload.mapName(), 64), 64);
                    buf.writeInt(payload.redKills());
                    buf.writeInt(payload.blueKills());
                    buf.writeInt(payload.remainingSeconds());
                    buf.writeBoolean(payload.inGame());
                    buf.writeVarInt(payload.phase());
                    // 花名册 JSON：发送前按字节截断兜底（正常构建时已远小于上限）
                    buf.writeString(NetworkHandler.truncateByUtf8Bytes(
                            payload.rosterJson() == null ? "[]" : payload.rosterJson(), MAX_ROSTER_BYTES), MAX_ROSTER_BYTES);
                    // 扩展信息 JSON（爆破回合状态等；死亡竞赛对局为 "{}"）
                    buf.writeString(NetworkHandler.truncateByUtf8Bytes(
                            payload.extraJson() == null ? "{}" : payload.extraJson(), MAX_EXTRA_BYTES), MAX_EXTRA_BYTES);
                },
                buf -> new HudDataPayload(
                        buf.readString(64),
                        buf.readInt(),
                        buf.readInt(),
                        buf.readInt(),
                        buf.readBoolean(),
                        buf.readVarInt(),
                        buf.readString(MAX_ROSTER_BYTES),
                        buf.readString(MAX_EXTRA_BYTES)
                )
        );

        @Override
        public Id<HudDataPayload> getId() {
            return ID;
        }
    }

    /**
     * 【作用】对局状态广播包（状态类型/提示消息/红蓝比分），驱动客户端的对局提示与比分展示；
     * message 由 NetworkHandler#sendMatchStatus 按 128 UTF-8 字节统一截断，确保 writeString 不越界。
     * 【被谁使用】S2C（服务端→客户端）。服务端 MatchManager#broadcastMatchStatus（开局/倒计时/结束）
     * 与 VoteManager（投票开始/结果）经 NetworkHandler#sendMatchStatus 发送；
     * 客户端 ClientNetworkHandler 注册接收器，将 message 显示到聊天栏。
     */
    public record MatchStatusPayload(
            StatusType type,
            String message,
            int redKills,
            int blueKills
    ) implements CustomPayload {

        // 【作用】包类型标识（cstmm:match_status），由 NetworkHandler 注册并由网络层路由
        public static final Id<MatchStatusPayload> ID = new Id<>(
                Identifier.of("cstmm", "match_status")
        );

        // 【作用】编解码器：枚举 + 消息（上限 128）+ 双方比分与 PacketByteBuf 互转
        public static final PacketCodec<PacketByteBuf, MatchStatusPayload> CODEC = PacketCodec.of(
                (payload, buf) -> {
                    buf.writeEnumConstant(payload.type());
                    buf.writeString(NetworkHandler.truncateByUtf8Bytes(payload.message(), 128), 128);
                    buf.writeInt(payload.redKills());
                    buf.writeInt(payload.blueKills());
                },
                buf -> new MatchStatusPayload(
                        buf.readEnumConstant(StatusType.class),
                        buf.readString(128),
                        buf.readInt(),
                        buf.readInt()
                )
        );

        @Override
        public Id<MatchStatusPayload> getId() {
            return ID;
        }

        /**
         * 【作用】对局状态类型，决定消息语义（开局/结束/投票开始/投票结果/倒计时）。
         * 【被谁使用】由 MatchStatusPayload 携带；服务端 MatchManager/VoteManager 构造，客户端仅展示消息文本。
         */
        public enum StatusType {
            MATCH_STARTING,
            MATCH_ENDED,
            VOTE_STARTED,
            VOTE_RESULT,
            COUNTDOWN
        }
    }

    /**
     * 【作用】匹配/商店/档案等客户端动作包，ActionType 决定服务端 handleMatchAction 的分发分支。
     * 字段按动作复用：mapName=地图 ID（加入队列）或商品索引字符串（购买）；
     * team=队伍偏好（1 红/2 蓝/0 无）；target=匹配模式（加入队列）或空（投票/档案/商店）。
     * 【被谁使用】C2S（客户端→服务端）。客户端 MatchMenuScreen（加入/退出队列、投票、请求档案）、
     * CstmmClient（投票快捷键）、ShopScreen（请求商店/购买物品）发送（ClientPlayNetworking.send）；
     * 服务端 NetworkHandler 注册接收器 → handleMatchAction 分发到 QueueManager/VoteManager 等。
     */
    public record MatchActionPayload(
            ActionType action,
            String mapName,
            int team,
            String target
    ) implements CustomPayload {

        // 【作用】包类型标识（cstmm:match_action），由 NetworkHandler 注册并由网络层路由
        public static final Id<MatchActionPayload> ID = new Id<>(
                Identifier.of("cstmm", "match_action")
        );

        // 【作用】编解码器：动作枚举 + 按动作复用的字段与 PacketByteBuf 互转
        public static final PacketCodec<PacketByteBuf, MatchActionPayload> CODEC = PacketCodec.of(
                (payload, buf) -> {
                    buf.writeEnumConstant(payload.action());
                    buf.writeString(payload.mapName(), 64);
                    buf.writeInt(payload.team());
                    buf.writeString(payload.target(), 64);
                },
                buf -> new MatchActionPayload(
                        buf.readEnumConstant(ActionType.class),
                        buf.readString(64),
                        buf.readInt(),
                        buf.readString(64)
                )
        );

        @Override
        public Id<MatchActionPayload> getId() {
            return ID;
        }

        /**
         * 【作用】动作类型，决定服务端 handleMatchAction 的分发分支。
         * 【被谁使用】由 MatchActionPayload 携带；客户端 MatchMenuScreen/ShopScreen/CstmmClient 构造，
         * 服务端 NetworkHandler switch 分发。新增值只能末尾追加保持 ordinal 兼容（writeEnumConstant 按序号编码）。
         */
        public enum ActionType {
            JOIN_QUEUE,
            LEAVE_QUEUE,
            VOTE_YES,
            VOTE_NO,
            VOTE_OVERTIME,
            SELECT_TEAM,
            BUY_ITEM,
            REQUEST_PROFILE,      // 新增
            REQUEST_SHOP,         // 请求商店数据（按地图下发，末尾追加保持 ordinal 兼容）
            JOIN_QUICK,           // 快速匹配：不再复用 JOIN_QUEUE + "quick" 伪地图 ID，避免与真实地图名冲突（末尾追加保持 ordinal 兼容）
            REQUEST_LEADERBOARD,  // 请求全服履历排行（履历页下方列表，服务端分片下发 LeaderboardPayload）
            PLANT_BOMB,           // 爆破交互：手持 C4 长按右键安放（target="start"/"stop" 按住状态翻转时发送）
            DEFUSE_BOMB           // 爆破交互：CT 靠近已安放 C4 长按使用键拆除（target="start"/"stop"）
        }
    }

    /**
     * 队列状态快照（S2C）：own（自己队列状态）+ clan（自己战队匹配状态）+ maps（每张地图队列状态）。
     * 由客户端"队列"标签页每秒请求一次（request_queue_status，服务端限频 2 次/秒/玩家）。
     *
     * 【被谁使用】S2C（服务端→客户端）。服务端 NetworkHandler 处理 RequestQueueStatusPayload 订阅时
     * 立即回发，并由 pushQueueStatusToSubscribers 在队列变化时推送给订阅者（QueueManager 触发）；
     * 客户端 ClientNetworkHandler 注册接收器 → QueueStatusCache#update 供"队列"页渲染。
     */
    public record QueueStatusPayload(
            String json
    ) implements CustomPayload {

        // 【作用】包类型标识（cstmm:queue_status），由 NetworkHandler 注册并由网络层路由
        public static final Id<QueueStatusPayload> ID = new Id<>(
                Identifier.of("cstmm", "queue_status")
        );

        // 【作用】编解码器：队列状态 JSON（上限 65536 字节）与 PacketByteBuf 互转
        public static final PacketCodec<PacketByteBuf, QueueStatusPayload> CODEC = PacketCodec.of(
                (payload, buf) -> buf.writeString(NetworkHandler.truncateByUtf8Bytes(payload.json(), 65536), 65536),
                buf -> new QueueStatusPayload(buf.readString(65536))
        );

        @Override
        public Id<QueueStatusPayload> getId() {
            return ID;
        }
    }

    /**
     * 队列状态订阅（C2S，空数据载荷）。
     * subscribe=true：加入订阅集并立即回发一次快照；false：退出订阅集。
     * 之后队列发生变化时服务端主动向订阅者推送 queue_status——不再需要客户端每秒轮询。
     *
     * 【被谁使用】C2S（客户端→服务端）。客户端 MatchMenuScreen 打开"队列"页时发 subscribe=true、
     * 关闭界面/取消匹配时发 subscribe=false（ClientPlayNetworking.send）；服务端 NetworkHandler
     * 注册接收器维护订阅集，订阅成功时立即回发一次 QueueStatusPayload 快照。
     */
    public record RequestQueueStatusPayload(
            boolean subscribe
    ) implements CustomPayload {

        // 【作用】包类型标识（cstmm:request_queue_status），由 NetworkHandler 注册并由网络层路由
        public static final Id<RequestQueueStatusPayload> ID = new Id<>(
                Identifier.of("cstmm", "request_queue_status")
        );

        // 【作用】编解码器：订阅开关布尔值与 PacketByteBuf 互转
        public static final PacketCodec<PacketByteBuf, RequestQueueStatusPayload> CODEC = PacketCodec.of(
                (payload, buf) -> buf.writeBoolean(payload.subscribe()),
                buf -> new RequestQueueStatusPayload(buf.readBoolean())
        );

        @Override
        public Id<RequestQueueStatusPayload> getId() {
            return ID;
        }
    }

    /**
     * 服务端弹窗通知（S2C）：替代部分聊天栏提示（如"配置已保存！"/战队加入结果），
     * 客户端在当前界面内弹出对话框；无界面时回退聊天栏。
     *
     * 【被谁使用】S2C（服务端→客户端）。服务端 NetworkHandler#sendPopup 在战队创建/加入/退出成功、
     * 配置保存成功等时机发送；客户端 ClientNetworkHandler 注册接收器——匹配菜单/配置界面内嵌弹窗，
     * 其他场景用 PopupScreen 全局弹窗界面承载。
     */
    public record PopupPayload(
            String message
    ) implements CustomPayload {

        // 【作用】包类型标识（cstmm:popup），由 NetworkHandler 注册并由网络层路由
        public static final Id<PopupPayload> ID = new Id<>(
                Identifier.of("cstmm", "popup")
        );

        // 【作用】编解码器：弹窗消息（上限 256 字符）与 PacketByteBuf 互转
        public static final PacketCodec<PacketByteBuf, PopupPayload> CODEC = PacketCodec.of(
                (payload, buf) -> buf.writeString(NetworkHandler.truncateByUtf8Bytes(payload.message(), 256), 256),
                buf -> new PopupPayload(buf.readString(256))
        );

        @Override
        public Id<PopupPayload> getId() {
            return ID;
        }
    }

    /**
     * 【作用】玩家档案同步包，jsonData 为 PlayerProfile 的 JSON 序列化（战绩/库存/货币等）。
     * 【被谁使用】S2C（服务端→客户端）。服务端 NetworkHandler#sendPlayerProfile 由 EventListener
     * （玩家加入回调）与 PlayerDataManager#syncProfileToPlayer（档案变更、MATCH_ACTION 的
     * REQUEST_PROFILE 请求）触发；客户端 ClientNetworkHandler 注册接收器解析进 PlayerProfileCache。
     */
    public record PlayerProfilePayload(
            String jsonData
    ) implements CustomPayload {

        // 【作用】包类型标识（cstmm:player_profile），由 NetworkHandler 注册并由网络层路由
        public static final Id<PlayerProfilePayload> ID = new Id<>(
                Identifier.of("cstmm", "player_profile")
        );

        // 【作用】编解码器：JSON 字符串（上限 65536 字节）与 PacketByteBuf 互转
        public static final PacketCodec<PacketByteBuf, PlayerProfilePayload> CODEC = PacketCodec.of(
                (payload, buf) -> buf.writeString(NetworkHandler.truncateByUtf8Bytes(payload.jsonData(), 65536), 65536),
                buf -> new PlayerProfilePayload(buf.readString(65536))
        );

        @Override
        public Id<PlayerProfilePayload> getId() {
            return ID;
        }
    }

    /**
     * 全服履历排行（S2C）：紧凑 JSON（{"players":[{"name":..,"kills":..,"deaths":..,"matches":..,"wins":..},...]}）
     * 过大时分包发送，分包结构与 ConfigSyncPayload 相同（partIndex 从 0 开始，totalParts 为总数）。
     * 排序方式（K/D、胜率、总击杀）由客户端本地选择，服务端不做排序。
     *
     * 【被谁使用】S2C（服务端→客户端）。服务端 NetworkHandler#sendLeaderboard 在收到
     * MatchActionPayload 的 REQUEST_LEADERBOARD 动作（履历页打开）时分包发送；
     * 客户端 ClientNetworkHandler 注册接收器按 partIndex 重组 → LeaderboardCache 供履历页排行渲染。
     */
    public record LeaderboardPayload(
            int partIndex,
            int totalParts,
            String data
    ) implements CustomPayload {

        // 【作用】包类型标识（cstmm:leaderboard），由 NetworkHandler 注册并由网络层路由
        public static final Id<LeaderboardPayload> ID = new Id<>(
                Identifier.of("cstmm", "leaderboard")
        );

        // 【作用】编解码器：分包序号/总数（VarInt）+ 分片数据（上限 32767 字节）与 PacketByteBuf 互转
        public static final PacketCodec<PacketByteBuf, LeaderboardPayload> CODEC = PacketCodec.of(
                (payload, buf) -> {
                    buf.writeVarInt(payload.partIndex());
                    buf.writeVarInt(payload.totalParts());
                    buf.writeString(NetworkHandler.truncateByUtf8Bytes(payload.data(), 32767), 32767);
                },
                buf -> new LeaderboardPayload(
                        buf.readVarInt(),
                        buf.readVarInt(),
                        buf.readString(32767)
                )
        );

        @Override
        public Id<LeaderboardPayload> getId() {
            return ID;
        }
    }

    /**
     * 商店数据同步包（S2C）：
     * 玩家请求商店（REQUEST_SHOP）时，服务端返回购买资格与购买页的完整网格布局：
     * 爆破图 = 1+4 列（第 1 列固定"装备"列，仅 CT 携带拆弹装备购买项，T 方空列），
     * 死亡竞赛图 = 5 列分类网格；每列携带列头名称与列内商品列表（商品含价格/显示名/图标）。
     * 爆破图额外携带经济信息（余额、购买窗口、拆弹装备价格）；死亡竞赛图免费发放。
     *
     * 【被谁使用】S2C（服务端→客户端）。服务端 NetworkHandler#sendShopData（REQUEST_SHOP/BUY_ITEM
     * 成功后与订阅制推送共用）经 create 构造发送；客户端 ClientNetworkHandler 注册接收器
     * → ShopDataCache#update 缓存并刷新已打开的 ShopScreen。
     */
    public record ShopDataS2CPayload(
            boolean eligible,
            boolean bombMode,
            // 爆破图第 1 列是否显示拆弹装备购买项（服务端按阵营判定：CT=true，T=false 空列）
            boolean hasDefuser,
            int money,
            boolean buyWindowOpen,
            int defuserPrice,
            // 玩家是否已持有拆弹装备（tag 态，2026-11-12 限购配套）：true 时客户端把购买项置灰显示"已持有"
            boolean ownedDefuser,
            List<ColumnData> columns
    ) implements CustomPayload {

        /**
         * 【作用】购买页单列数据：列头名称 + 列内商品列表（爆破图第 1 列固定为"装备"列，
         *         items 恒为空列表，拆弹装备购买项由客户端按 hasDefuser 单独渲染）。
         */
        public record ColumnData(String name, List<GlobalConfig.ShopItem> items) {}

        // 【作用】包类型标识（cstmm:shop_data），由 NetworkHandler 注册并由网络层路由
        public static final Id<ShopDataS2CPayload> ID = new Id<>(
                Identifier.of("cstmm", "shop_data")
        );

        // 【作用】编解码器：资格/模式/装备列位 + 爆破经济三字段 + 逐列（列名/列内商品逐项）
        public static final PacketCodec<PacketByteBuf, ShopDataS2CPayload> CODEC = PacketCodec.of(
                (payload, buf) -> {
                    buf.writeBoolean(payload.eligible());
                    buf.writeBoolean(payload.bombMode());
                    buf.writeBoolean(payload.hasDefuser());
                    buf.writeVarInt(Math.max(0, payload.money()));
                    buf.writeBoolean(payload.buyWindowOpen());
                    buf.writeVarInt(Math.max(0, payload.defuserPrice()));
                    buf.writeBoolean(payload.ownedDefuser());
                    List<ColumnData> cols = payload.columns() == null ? List.of() : payload.columns();
                    buf.writeVarInt(cols.size());
                    for (ColumnData col : cols) {
                        buf.writeString(truncUtf8(col.name(), 64), 64);
                        List<GlobalConfig.ShopItem> items = col.items() == null ? List.of() : col.items();
                        // 先数非空条目再写（null 元素跳过，计数与实际写入一致防客户端读包错位）
                        int count = 0;
                        for (GlobalConfig.ShopItem item : items) {
                            if (item != null) count++;
                        }
                        buf.writeVarInt(count);
                        for (GlobalConfig.ShopItem item : items) {
                            if (item == null) continue;
                            writeItem(buf, item);
                        }
                    }
                },
                buf -> {
                    boolean eligible = buf.readBoolean();
                    boolean bombMode = buf.readBoolean();
                    boolean hasDefuser = buf.readBoolean();
                    int money = Math.max(0, buf.readVarInt());
                    boolean buyWindowOpen = buf.readBoolean();
                    int defuserPrice = Math.max(0, buf.readVarInt());
                    boolean ownedDefuser = buf.readBoolean();
                    int colCount = Math.max(0, buf.readVarInt());
                    List<ColumnData> columns = new ArrayList<>(colCount);
                    for (int c = 0; c < colCount; c++) {
                        String name = buf.readString(64);
                        int itemCount = Math.max(0, buf.readVarInt());
                        List<GlobalConfig.ShopItem> items = new ArrayList<>(itemCount);
                        for (int i = 0; i < itemCount; i++) {
                            items.add(readItem(buf));
                        }
                        columns.add(new ColumnData(name, List.copyOf(items)));
                    }
                    return new ShopDataS2CPayload(eligible, bombMode, hasDefuser, money, buyWindowOpen,
                            defuserPrice, ownedDefuser, List.copyOf(columns));
                }
        );

        // 【作用】物品串线路上限（UTF-16 字符数，两端 codec 共用此常量）：
        //        /give 格式带组件的物品串（custom_name/lore/block_entity_data 等）可远超 256 字符，
        //        1.21.1 writeString 第一道校验是"字符数 > 上限即抛 EncoderException"，
        //        未截断会直接炸掉服务端编码器导致玩家断连（2026-11-12 shop_data EncoderException 根因）。
        //        线路副本仅供客户端预览图标/名称，服务端发放仍用配置原件，截断只影响预览不影响购买。
        public static final int ITEM_ID_MAX_CHARS = 2048;

        // 【作用】写单个商品条目（物品串/价格/限购 + 队伍/显示名/图标，全部经 UTF-8 安全截断防断连）
        private static void writeItem(PacketByteBuf buf, GlobalConfig.ShopItem item) {
            buf.writeString(NetworkHandler.truncateByUtf8Bytes(
                    item.getItemId() == null ? "" : item.getItemId(), ITEM_ID_MAX_CHARS), ITEM_ID_MAX_CHARS);
            buf.writeVarInt(Math.max(0, item.getPrice()));
            buf.writeVarInt(Math.max(0, item.getMaxPurchase()));
            buf.writeString(NetworkHandler.truncateByUtf8Bytes(item.getTeam(), 8), 8);
            buf.writeString(NetworkHandler.truncateByUtf8Bytes(item.getDisplayName(), 256), 256);
            buf.writeString(NetworkHandler.truncateByUtf8Bytes(item.getIcon(), 32000), 32000);
        }

        // 【作用】读单个商品条目（与 writeItem 一一对应）
        private static GlobalConfig.ShopItem readItem(PacketByteBuf buf) {
            String itemId = buf.readString(ITEM_ID_MAX_CHARS);
            int price = buf.readVarInt();
            int maxPurchase = buf.readVarInt();
            String team = buf.readString(8);
            String displayName = buf.readString(256);
            String icon = buf.readString(32000);
            return new GlobalConfig.ShopItem(itemId, price, maxPurchase, team, displayName, icon);
        }

        /** 按 UTF-8 字节数截断（防 writeString 超上限抛异常断连；null 一律按空串写，切断多字节产生的残字符仅影响显示） */
        private static String truncUtf8(String s, int maxBytes) {
            // 【作用】统一委托 NetworkHandler.truncateByUtf8Bytes（不切断多字节字符的实现，
            //        本类原字节硬切实现会在多字节边界回涨出替换字符，已被替换）
            return NetworkHandler.truncateByUtf8Bytes(s, maxBytes);
        }

        /**
         * 【作用】完整构造（columns 为 null 时按空列表处理）。
         * 【被谁使用】NetworkHandler#sendShopData（服务端下发）与 REQUEST_SHOP 无对局分支（不可购空数据）。
         */
        public static ShopDataS2CPayload create(boolean eligible, boolean bombMode, boolean hasDefuser,
                                                int money, boolean buyWindowOpen, int defuserPrice,
                                                boolean ownedDefuser, List<ColumnData> columns) {
            List<ColumnData> safeCols = columns == null ? List.of() : columns;
            return new ShopDataS2CPayload(eligible, bombMode, hasDefuser, money, buyWindowOpen,
                    defuserPrice, ownedDefuser, safeCols);
        }

        @Override
        public Id<ShopDataS2CPayload> getId() {
            return ID;
        }
    }

    /**
     * 【作用】MVP 音乐通知包（S2C）：告诉对局内全体玩家本场/本回合的 MVP 是谁、其选择的音乐名，
     *         以及客户端应延迟多久开始播放（爆破模式需避开胜利音效/语音播报）。
     *         音乐文件的 URL 与哈希由客户端按全局配置的 mvpMusicUrl 自行访问 index_file.json 获取，
     *         本包不携带任何资源直链，避免服务端额外流量与 URL 变更时的同步问题。
     * 【被谁使用】服务端 MvpMusicManager 在回合结束/对局结算时广播；
     *             客户端 ClientNetworkHandler 接收后交给 MvpMusicClientHandler 播放与提示。
     */
    public record MvpMusicPayload(
            String mvpName,
            String musicName,
            int delayMs,
            // MVP 提示栏（HUD 横幅）显示时长毫秒：服务端按所配对的胜利横幅时长下发，
            // 保证 MVP 栏与获胜信息栏一起出现、一起消失（2026-11-12）；<=0 时客户端回退默认时长
            int durationMs,
            boolean bombMode
    ) implements CustomPayload {

        // 【作用】包类型标识（cstmm:mvp_music），由 NetworkHandler/ClientNetworkHandler 注册
        public static final Id<MvpMusicPayload> ID = new Id<>(
                Identifier.of("cstmm", "mvp_music")
        );

        // 【作用】编解码器：MVP 名（≤64 字符）+ 音乐名（≤256 字符）+ 延迟毫秒 + 提示栏时长毫秒 + 是否爆破模式
        public static final PacketCodec<PacketByteBuf, MvpMusicPayload> CODEC = PacketCodec.of(
                (payload, buf) -> {
                    buf.writeString(NetworkHandler.truncateByUtf8Bytes(payload.mvpName(), 64), 64);
                    buf.writeString(NetworkHandler.truncateByUtf8Bytes(payload.musicName(), 256), 256);
                    buf.writeVarInt(Math.max(0, payload.delayMs()));
                    buf.writeVarInt(Math.max(0, payload.durationMs()));
                    buf.writeBoolean(payload.bombMode());
                },
                buf -> new MvpMusicPayload(buf.readString(64), buf.readString(256),
                        buf.readVarInt(), buf.readVarInt(), buf.readBoolean())
        );

        @Override
        public Id<MvpMusicPayload> getId() {
            return ID;
        }
    }

    /**
     * 【作用】客户端设置/清除自己 MVP 音乐选择的 C2S 包：musicName 为 index_file.json 中的
     *         music_name（空串 = 清除选择）。服务端校验后写入玩家档案（mvpMusicName 字段）并同步档案。
     * 【被谁使用】客户端 PersonalizeTabPanel（选择/清除按钮）发送；
     *             服务端 NetworkHandler 注册接收器 → PlayerDataManager.setMvpMusic。
     */
    public record SetMvpMusicPayload(
            String musicName
    ) implements CustomPayload {

        // 【作用】包类型标识（cstmm:set_mvp_music）
        public static final Id<SetMvpMusicPayload> ID = new Id<>(
                Identifier.of("cstmm", "set_mvp_music")
        );

        // 【作用】编解码器：音乐名（≤256 字符，空串 = 清除）
        public static final PacketCodec<PacketByteBuf, SetMvpMusicPayload> CODEC = PacketCodec.of(
                (payload, buf) -> buf.writeString(payload.musicName(), 256),
                buf -> new SetMvpMusicPayload(buf.readString(256))
        );

        @Override
        public Id<SetMvpMusicPayload> getId() {
            return ID;
        }
    }

    /**
     * 【作用】客户端设置/清除自己头像绑定的 C2S 包：avatarType 为平台（"qq"/"bili"），
     *         avatarId 为账号数字 ID（QQ号 / B站 UID），空 avatarType 表示清除头像。
     *         服务端只存绑定不存图片直链——头像图片由各客户端按绑定自行获取
     *         （QQ 拼接 qlogo 直链 / B站经 uapis 解析 face 字段）。
     * 【被谁使用】C2S（客户端→服务端）。客户端 PersonalizeTabPanel（保存/清除头像按钮）发送；
     *             服务端 NetworkHandler 注册接收器 → PlayerDataManager.setAvatarBinding（写入档案文件
     *             config/cstmm/data/players/<uuid>.json 的 avatarType/avatarId 字段，进服即建档）。
     */
    public record SetFacePayload(
            String avatarType,
            String avatarId
    ) implements CustomPayload {

        // 【作用】包类型标识（cstmm:set_face），由 NetworkHandler 注册并由网络层路由
        public static final Id<SetFacePayload> ID = new Id<>(
                Identifier.of("cstmm", "set_face")
        );

        // 【作用】编解码器：平台（最长 16 字符，空串 = 清除）+ 账号 ID（最长 32 字符）
        public static final PacketCodec<PacketByteBuf, SetFacePayload> CODEC = PacketCodec.of(
                (payload, buf) -> {
                    buf.writeString(payload.avatarType(), 16);
                    buf.writeString(payload.avatarId(), 32);
                },
                buf -> new SetFacePayload(buf.readString(16), buf.readString(32))
        );

        @Override
        public Id<SetFacePayload> getId() {
            return ID;
        }
    }
}
