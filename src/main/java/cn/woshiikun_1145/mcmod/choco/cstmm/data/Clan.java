package cn.woshiikun_1145.mcmod.choco.cstmm.data;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 战队数据（Gson 持久化到 config/cstmm/data/clans.json）。
 * name 为全服唯一键（不区分大小写）；members 按加入顺序排列（最早在前，队长在首位），
 * 顺序用于队长退出时的自动转让（转让给最早加入的成员）。
 * 徽标为图片 Base64 或图片 URL（http/https，客户端自行下载；留空 = 黑色实心正方形）；
 * base64 解码后上限 48KiB（ClanManager.validateBadge 校验）；memberLimit 0 = 无上限。
 */
public class Clan {

    /** 成员条目：uuid + 最近一次可见的玩家名（支持离线后按名踢出/展示） */
    public static class Member {
        public String uuid;
        // 最近一次见到的玩家名（上线时由 ClanManager 刷新）
        public String name;

        public Member() {}

        public Member(String uuid, String name) {
            this.uuid = uuid;
            this.name = name;
        }

        public String getUuid() { return uuid; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    // 全服唯一战队名（不区分大小写），ClanManager 以此为键建图
    public String name;
    // 战队缩写（聊天/计分板展示用）
    public String abbreviation;
    // 徽标内容：图片 Base64 或 http(s) URL，空串表示默认黑色方块
    public String badgeBase64 = "";
    // 队长的玩家 UUID（members 首位成员）
    public String leader;
    // 人数上限，0 = 无上限
    public int memberLimit;
    // 创建时间戳（毫秒）
    public long createdAt;
    // 成员列表，按加入顺序排列（最早在前，队长在首位）
    public List<Member> members = new ArrayList<>();

    public String getName() { return name; }
    public String getAbbreviation() { return abbreviation; }
    public String getBadgeBase64() { return badgeBase64; }

    /** 徽标变更时必须调用：失效内容寻址 id 缓存 */
    public void setBadgeBase64(String badge) {
        this.badgeBase64 = badge == null ? "" : badge;
        this.badgeIdCache = null;
    }
    public String getLeader() { return leader; }
    public int getMemberLimit() { return memberLimit; }
    public long getCreatedAt() { return createdAt; }
    public List<Member> getMembers() { return members; }

    // 徽标内容寻址 id 缓存（transient：不随 Gson 持久化，按需计算）
    private transient String badgeIdCache = null;

    /**
     * 徽标内容寻址 id：SHA-256 前 8 字节 hex（16 字符）。
     * 用于 S2C 分片下发的重组键与缓存键（相同徽标跨战队可复用）；
     * 编辑徽标后内容变化 → id 变化，旧 id 缓存自然失效。
     */
    public String getBadgeId() {
        // 懒加载：首次调用时计算 SHA-256 并缓存，后续直接返回
        if (badgeIdCache == null) {
            try {
                byte[] hash = MessageDigest.getInstance("SHA-256")
                        .digest(badgeBase64.getBytes(StandardCharsets.UTF_8));
                badgeIdCache = HexFormat.of().formatHex(hash, 0, 8);
            } catch (Exception e) {
                // SHA-256 必然存在；万一异常退化为长度+哈希码组合（仍保持 16 字符内、内容相关）
                badgeIdCache = String.format("%08x%08x", badgeBase64.length(), badgeBase64.hashCode());
            }
        }
        return badgeIdCache;
    }

    /** 是否已满员（memberLimit 0 = 无上限） */
    public boolean isFull() {
        return memberLimit > 0 && members.size() >= memberLimit;
    }
}
