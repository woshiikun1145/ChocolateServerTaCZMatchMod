package cn.woshiikun_1145.mcmod.choco.cstmm.network.payload;

import cn.woshiikun_1145.mcmod.choco.cstmm.network.NetworkHandler;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import java.util.regex.Pattern;

/**
 * 【作用】战队/徽标域 payload 容器：集中存放战队数据、战队操作、战队名提示、徽标分片
 *         下发与徽标缓存上报相关包。
 *         由原 ClanDataPayload.java、ClanActionPayload.java、ClanHintsPayload.java、
 *         BadgePayload.java、BadgeKnownPayload.java、RequestBadgePayload.java 合并而来，
 *         各嵌套类型行为与网络字节格式与拆分前完全一致（ID/CODEC 未改动）。
 */
public final class ClanPayloads {

    // 【作用】工具容器，禁止实例化
    private ClanPayloads() {
    }

    /**
     * 战队数据（S2C）。kind: MINE（我的战队状态）/ LIST（随机战队列表）/ DETAIL（选中战队详情）。
     * json 为对应快照；操作类反馈（成功/失败原因）由服务端直接发聊天消息，不走本包。
     *
     * 【被谁使用】S2C（服务端→客户端）。服务端 NetworkHandler#sendClanData 响应 ClanActionPayload
     * 的各请求（LIST/DETAIL/MINE），sendClanMineTo 在战队变更时主动推送 MINE；
     * 客户端 ClientNetworkHandler 注册接收器 → ClanCache#update 缓存并检查徽标缺失。
     */
    public record ClanDataPayload(
            String kind,
            String json
    ) implements CustomPayload {

        // 【作用】包类型标识（cstmm:clan_data），由 NetworkHandler 注册并由网络层路由
        public static final Id<ClanDataPayload> ID = new Id<>(
                Identifier.of("cstmm", "clan_data")
        );

        // 【作用】编解码器：kind（上限 16 字符）+ 快照 JSON（上限 65536 字节）与 PacketByteBuf 互转
        public static final PacketCodec<PacketByteBuf, ClanDataPayload> CODEC = PacketCodec.of(
                (payload, buf) -> {
                    buf.writeString(NetworkHandler.truncateByUtf8Bytes(payload.kind(), 16), 16);
                    buf.writeString(NetworkHandler.truncateByUtf8Bytes(payload.json(), 65536), 65536);
                },
                buf -> new ClanDataPayload(
                        buf.readString(16),
                        buf.readString(65536)
                )
        );

        @Override
        public Id<ClanDataPayload> getId() {
            return ID;
        }
    }

    /**
     * 战队操作（C2S）。text1/text2/badge/number 按动作复用：
     * CREATE: text1=战队名称, text2=缩写, badge=徽标base64, number=成员上限
     * JOIN / REQUEST_DETAIL / REQUEST_LIST(可带查询串): text1=战队名/查询串
     * TRANSFER / KICK: text1=目标玩家名
     * LEAVE / DISBAND / REQUEST_MINE: 全部空闲
     *
     * 大徽标分片上传：badge 超过 MAX_PART_CHARS(30000) 时由客户端拆成 totalParts 片逐包发送，
     * 每片 badge 字段携带对应分片数据；服务端集齐后拼接再执行动作（只有最后一片触发执行）。
     * totalParts==1 时 badge 为完整数据（兼容小徽标单包直发）。
     * 徽标上限：base64 解码后 ≤ 48KiB（ClanManager.validateBadge），URL 徽标直接填 URL 不分片。
     *
     * 【被谁使用】C2S（客户端→服务端）。客户端 ClanTabPanel#sendAction 在战队页操作
     * （创建/加入/退出/解散/转让/踢人/编辑/请求列表/详情/我的）时分包发送；
     * 服务端 NetworkHandler 注册接收器（ServerPlayNetworking.registerGlobalReceiver），
     * 经 handleClanActionMaybeChunked 重组分片后由 handleClanAction 分发到 ClanManager。
     */
    public record ClanActionPayload(
            ClanAction action,
            String text1,
            String text2,
            String badge,
            int number,
            int badgePartIndex,
            int badgeTotalParts
    ) implements CustomPayload {

        // 【作用】单分片最大字符数：客户端上传拆片与服务端解码校验共用
        public static final int MAX_PART_CHARS = 30000;
        /** 48KiB 徽标 base64（≤65536 字符）每片 30000 字符时最多 3 片（服务端校验解码后大小） */
        public static final int MAX_TOTAL_PARTS = 3;

        /** 便捷构造：单包完整徽标（totalParts=1） */
        // 【被谁使用】ClanTabPanel#sendAction（≤30000 字符单包直发）与 NetworkHandler#handleClanActionMaybeChunked（分片拼齐后重建单包）
        public static ClanActionPayload single(ClanAction action, String text1, String text2, String badge, int number) {
            return new ClanActionPayload(action, text1, text2, badge == null ? "" : badge, number, 0, 1);
        }

        // 【作用】包类型标识（cstmm:clan_action），由 NetworkHandler 注册并由网络层路由
        public static final Id<ClanActionPayload> ID = new Id<>(
                Identifier.of("cstmm", "clan_action")
        );

        // 【作用】编解码器：按动作复用字段与 PacketByteBuf 互转，各字符串上限与解码端一致
        public static final PacketCodec<PacketByteBuf, ClanActionPayload> CODEC = PacketCodec.of(
                (payload, buf) -> {
                    buf.writeEnumConstant(payload.action());
                    buf.writeString(NetworkHandler.truncateByUtf8Bytes(payload.text1(), 192), 192);
                    buf.writeString(NetworkHandler.truncateByUtf8Bytes(payload.text2(), 16), 16);
                    buf.writeString(NetworkHandler.truncateByUtf8Bytes(payload.badge(), MAX_PART_CHARS), MAX_PART_CHARS);
                    buf.writeInt(payload.number());
                    buf.writeVarInt(payload.badgePartIndex());
                    buf.writeVarInt(payload.badgeTotalParts());
                },
                buf -> new ClanActionPayload(
                        buf.readEnumConstant(ClanAction.class),
                        buf.readString(192),
                        buf.readString(16),
                        buf.readString(MAX_PART_CHARS),
                        buf.readInt(),
                        buf.readVarInt(),
                        buf.readVarInt()
                )
        );

        @Override
        public Id<ClanActionPayload> getId() {
            return ID;
        }

        /**
         * 【作用】战队操作类型，决定服务端 handleClanAction 的分发分支。
         * 【被谁使用】由 ClanActionPayload 携带；客户端 ClanTabPanel 构造动作，服务端 NetworkHandler switch 分发。
         */
        public enum ClanAction {
            REQUEST_LIST,
            REQUEST_DETAIL,
            REQUEST_MINE,
            CREATE,
            JOIN,
            LEAVE,
            DISBAND,
            TRANSFER,
            KICK,
            EDIT
        }
    }

    /**
     * 【作用】战队名提示快照（S2C）：全服全部战队名与其成员名的紧凑 JSON
     *         （{"战队名":["成员1","成员2",...], ...}），供客户端 Tab 补全使用——
     *         /cstmm data get|edit|delete clan 的建议 provider 在客户端执行，
     *         而战队数据只在服务端，必须把提示数据同步到客户端才能出建议。
     * 【被谁使用】S2C（服务端→客户端）。服务端 NetworkHandler 在玩家 JOIN 与战队任何
     * 变更（ClanManager.save 落盘点）时全服广播；客户端 ClientNetworkHandler 注册接收器
     * → CommandHintCache#set 供命令补全读取。
     */
    public record ClanHintsPayload(
            String json
    ) implements CustomPayload {

        // 【作用】包类型标识（cstmm:clan_hints），由 NetworkHandler 注册并由网络层路由
        public static final Id<ClanHintsPayload> ID = new Id<>(
                Identifier.of("cstmm", "clan_hints")
        );

        // 提示 JSON 字节上限（战队名 ≤128 字符 × 数量 + 成员名列表，正常规模远小于此）
        private static final int MAX_HINTS_BYTES = 32767;

        // 【作用】编解码器：提示 JSON（上限 32767 字节）与 PacketByteBuf 互转
        public static final PacketCodec<PacketByteBuf, ClanHintsPayload> CODEC = PacketCodec.of(
                (payload, buf) -> buf.writeString(NetworkHandler.truncateByUtf8Bytes(payload.json(), MAX_HINTS_BYTES), MAX_HINTS_BYTES),
                buf -> new ClanHintsPayload(buf.readString(MAX_HINTS_BYTES))
        );

        @Override
        public Id<ClanHintsPayload> getId() {
            return ID;
        }
    }

    /**
     * 战队徽标分片下发（S2C）。badgeId = 徽标 base64 的 MurmurHash3 32 位哈希（内容寻址，8 字符小写 hex），
     * 客户端按 id 重组缓存；clan_data JSON 中的 badge 字段携带该 id 而非完整 base64
     * （clan_data 单包 65536 字节上限，大徽标必须分片；URL 徽标则直接携带 URL，不经此通道）。
     * data 每片 ≤30000 字符；徽标解码后上限 48KiB（base64 ≤ 65536 字符）→ totalParts ∈ [1,3]。
     *
     * 【被谁使用】S2C（服务端→客户端）。服务端 NetworkHandler#sendBadgeParts（徽标补发流程）按片发送；
     * 客户端 ClientNetworkHandler 注册接收器 → BadgeCache#apply 按 badgeId 重组并缓存。
     */
    public record BadgePayload(
            String badgeId,
            int partIndex,
            int totalParts,
            String data
    ) implements CustomPayload {

        // 【作用】单分片最大字符数：服务端拆片与客户端解码校验共用
        public static final int MAX_PART_CHARS = 30000;
        /** 48KiB 徽标 base64（≤65536 字符）每片 30000 字符时最多 3 片 */
        public static final int MAX_TOTAL_PARTS = 3;

        /**
         * 【作用】badgeId 合法格式：8 字符小写 hex（MurmurHash3 32 位哈希）。
         * 【被谁使用】客户端 BadgeCache（磁盘缓存文件名白名单，防路径穿越/恶意 id）、
         *           服务端 NetworkHandler（BadgeKnownPayload 上报校验）。
         */
        public static final Pattern BADGE_ID_PATTERN = Pattern.compile("[0-9a-f]{8}");

        /** badgeId 是否合法（null/非 8 字符小写 hex 均为非法） */
        public static boolean isValidBadgeId(String id) {
            return id != null && BADGE_ID_PATTERN.matcher(id).matches();
        }

        // 【作用】包类型标识（cstmm:badge），由 NetworkHandler 注册并由网络层路由
        public static final Id<BadgePayload> ID = new Id<>(
                Identifier.of("cstmm", "badge")
        );

        // 【作用】编解码器：分片信息 + 分片数据与 PacketByteBuf 互转
        public static final PacketCodec<PacketByteBuf, BadgePayload> CODEC = PacketCodec.of(
                (payload, buf) -> {
                    buf.writeString(NetworkHandler.truncateByUtf8Bytes(payload.badgeId(), 8), 8);
                    buf.writeVarInt(payload.partIndex());
                    buf.writeVarInt(payload.totalParts());
                    buf.writeString(NetworkHandler.truncateByUtf8Bytes(payload.data(), MAX_PART_CHARS), MAX_PART_CHARS);
                },
                buf -> new BadgePayload(
                        buf.readString(8),
                        buf.readVarInt(),
                        buf.readVarInt(),
                        buf.readString(MAX_PART_CHARS)
                )
        );

        @Override
        public Id<BadgePayload> getId() {
            return ID;
        }
    }

    /**
     * 【作用】客户端徽标缓存上报（C2S）：客户端进服后把本机磁盘缓存中已有的徽标 id 列表
     *         （逗号分隔的 8 字符小写 hex，最多 {@link #MAX_IDS} 个）告知服务端，
     *         服务端将其加入该玩家的"已下发"集合，后续 MINE/DETAIL 引用这些徽标时
     *         跳过分片重发（只发 8 字符 id 引用）——徽标磁盘缓存省带宽的服务端感知半环。
     *         谎报无害：客户端声称已有却无缓存时只是自己界面显示占位（自伤行为）。
     * 【被谁使用】C2S（客户端→服务端）。客户端 ClientNetworkHandler 的 JOIN 回调
     *         （BadgeCache#diskBadgeIds 列目录收集）；服务端 NetworkHandler 注册接收器 →
     *         逐个校验 hex 格式后加入 sentBadges。
     */
    public record BadgeKnownPayload(
            String badgeIds
    ) implements CustomPayload {

        /** 单次上报的徽标 id 数量上限（客户端截断 + 服务端校验双重防护） */
        public static final int MAX_IDS = 256;
        /** 编码后字符串上限：256 个 id × 9 字符（8 hex + 逗号）= 2304 字符，余量放宽 */
        private static final int MAX_WIRE_CHARS = 8192;

        // 【作用】包类型标识（cstmm:badge_known），由 NetworkHandler 注册并由网络层路由
        public static final Id<BadgeKnownPayload> ID = new Id<>(
                Identifier.of("cstmm", "badge_known")
        );

        // 【作用】编解码器：逗号分隔的徽标 id 列表字符串与 PacketByteBuf 互转
        public static final PacketCodec<PacketByteBuf, BadgeKnownPayload> CODEC = PacketCodec.of(
                (payload, buf) -> buf.writeString(NetworkHandler.truncateByUtf8Bytes(payload.badgeIds(), MAX_WIRE_CHARS), MAX_WIRE_CHARS),
                buf -> new BadgeKnownPayload(buf.readString(MAX_WIRE_CHARS))
        );

        @Override
        public Id<BadgeKnownPayload> getId() {
            return ID;
        }
    }

    /**
     * 徽标缓存缺失请求（C2S）：客户端收到 clan_data 时若本地没有该 badgeId 的完整数据，
     * 发送本包请求服务端重新下发全部分片（覆盖断线重连后客户端缓存丢失的场景）。
     *
     * 【被谁使用】C2S（客户端→服务端）。客户端 BadgeCache#requestIfMissing 在收到 clan_data
     * 引用未知徽标 id 时发送（ClientPlayNetworking.send）；服务端 NetworkHandler 注册接收器
     * → 查表重新下发该徽标的全部分片（BadgePayload）。
     */
    public record RequestBadgePayload(String badgeId) implements CustomPayload {

        // 【作用】包类型标识（cstmm:request_badge），由 NetworkHandler 注册并由网络层路由
        public static final Id<RequestBadgePayload> ID = new Id<>(
                Identifier.of("cstmm", "request_badge")
        );

        // 【作用】编解码器：badgeId（8 字符小写 hex，上限 16 字符余量）与 PacketByteBuf 互转
        public static final PacketCodec<PacketByteBuf, RequestBadgePayload> CODEC = PacketCodec.of(
                (payload, buf) -> buf.writeString(payload.badgeId(), 16),
                buf -> new RequestBadgePayload(buf.readString(16))
        );

        @Override
        public Id<RequestBadgePayload> getId() {
            return ID;
        }
    }
}
