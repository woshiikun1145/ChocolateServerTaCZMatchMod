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
 * 徽标为 PNG 的 Base64（留空 = 黑色实心正方形）；memberLimit 0 = 无上限。
 */
public class Clan {

    /** 成员条目：uuid + 最近一次可见的玩家名（支持离线后按名踢出/展示） */
    public static class Member {
        public String uuid;
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

    public String name;
    public String abbreviation;
    public String badgeBase64 = "";
    public String leader;
    public int memberLimit;
    public long createdAt;
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

    private transient String badgeIdCache = null;

    /**
     * 徽标内容寻址 id：SHA-256 前 8 字节 hex（16 字符）。
     * 用于 S2C 分片下发的重组键与缓存键（相同徽标跨战队可复用）；
     * 编辑徽标后内容变化 → id 变化，旧 id 缓存自然失效。
     */
    public String getBadgeId() {
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
