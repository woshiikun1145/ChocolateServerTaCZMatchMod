package cn.woshiikun_1145.mcmod.choco.cstmm.network.payload;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.regex.Pattern;

/**
 * 战队徽标分片下发（S2C）。badgeId = 徽标 base64 的 SHA-256 前 8 字节 hex（内容寻址，16 字符），
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
     * 【作用】badgeId 合法格式：16 字符小写 hex（SHA-256 前 8 字节；退化分支 %08x%08x 同为 hex）。
     * 【被谁使用】客户端 BadgeCache（磁盘缓存文件名白名单，防路径穿越/恶意 id）、
     *           服务端 NetworkHandler（BadgeKnownPayload 上报校验）。
     */
    public static final Pattern BADGE_ID_PATTERN = Pattern.compile("[0-9a-f]{16}");

    /** badgeId 是否合法（null/非 16 字符小写 hex 均为非法） */
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
                buf.writeString(payload.badgeId(), 16);
                buf.writeVarInt(payload.partIndex());
                buf.writeVarInt(payload.totalParts());
                buf.writeString(payload.data(), MAX_PART_CHARS);
            },
            buf -> new BadgePayload(
                    buf.readString(16),
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
