package cn.woshiikun_1145.mcmod.choco.cstmm.data;

import java.util.UUID;

/**
 * 【作用】玩家档案：累计击杀/死亡/场次/胜场、惩罚死亡数与头像绑定（avatarType/avatarId），
 *         支持 K/D 计算，随 Gson 持久化到玩家数据文件（config/cstmm/data/players/<uuid>.json，
 *         进服即创建）。头像绑定只存平台与账号 ID，图片由各客户端自行获取。
 * 【被谁使用】PlayerDataManager 加载/保存并调用 addXxx 累计战绩；ModCommands（stats 指令展示）、
 *           NetworkHandler（战绩同步给客户端/设置头像绑定）、QueueManager（队列快照头像绑定）、
 *           客户端 PersonalizeTabPanel（validateAvatarBinding 预校验）读取。
 */
public class PlayerProfile {
    /** 头像绑定账号 ID 长度上限（QQ号 5-11 位、B站 UID 可更长，取宽松上限） */
    public static final int MAX_AVATAR_ID_LENGTH = 32;

    // 玩家 UUID（持久化主键）
    private UUID playerUuid;
    // 玩家名（离线展示用）
    private String playerName;
    // 累计击杀数
    private int totalKills;
    // 累计死亡数
    private int totalDeaths;
    // 累计参赛场次
    private int totalMatches;
    // 累计胜场
    private int totalWins;
    // 惩罚死亡数（越界处决等计入，仅统计不参与 K/D）
    private int penaltyDeaths;
    // 头像绑定平台（"qq"/"bili"；空串 = 未设置；字段默认值随档案文件持久化）
    private String avatarType = "";
    // 头像绑定账号 ID（QQ号 / B站 UID，纯数字）
    private String avatarId = "";

    // Gson 反序列化所需的无参构造
    public PlayerProfile() {}

    public PlayerProfile(UUID playerUuid, String playerName) {
        this.playerUuid = playerUuid;
        this.playerName = playerName;
        this.totalKills = 0;
        this.totalDeaths = 0;
        this.totalMatches = 0;
        this.totalWins = 0;
        this.penaltyDeaths = 0;
        this.avatarType = "";
        this.avatarId = "";
    }

    public UUID getPlayerUuid() { return playerUuid; }
    public String getPlayerName() { return playerName; }
    public int getTotalKills() { return totalKills; }
    public int getTotalDeaths() { return totalDeaths; }
    public int getTotalMatches() { return totalMatches; }
    public int getTotalWins() { return totalWins; }
    public int getPenaltyDeaths() { return penaltyDeaths; }
    public String getAvatarType() { return avatarType == null ? "" : avatarType; }
    public String getAvatarId() { return avatarId == null ? "" : avatarId; }

    public void setPlayerUuid(UUID playerUuid) { this.playerUuid = playerUuid; }
    public void setPlayerName(String playerName) { this.playerName = playerName; }
    public void setAvatarBinding(String avatarType, String avatarId) {
        this.avatarType = avatarType == null ? "" : avatarType;
        this.avatarId = avatarId == null ? "" : avatarId;
    }

    /**
     * 【作用】校验头像绑定：平台仅限 qq/bili，账号 ID 必须为纯数字；空平台（清除）直接通过。
     * @return null 表示通过，否则返回可直接发给玩家的错误消息（服务端 SET_FACE 与客户端
     *         PersonalizeTabPanel 提交前共用同一规则）
     */
    public static String validateAvatarBinding(String type, String id) {
        if (type == null || type.isEmpty()) return null; // 清除头像
        if (!type.equals("qq") && !type.equals("bili")) {
            return "§c仅支持 QQ 或 B站账号头像！";
        }
        if (id == null || !id.matches("\\d{3," + MAX_AVATAR_ID_LENGTH + "}")) {
            return "§c账号 ID 必须是纯数字（QQ号或 B站 UID）！";
        }
        return null;
    }

    public void setTotalKills(int totalKills) { this.totalKills = totalKills; }
    public void setTotalDeaths(int totalDeaths) { this.totalDeaths = totalDeaths; }
    public void setTotalMatches(int totalMatches) { this.totalMatches = totalMatches; }
    public void setTotalWins(int totalWins) { this.totalWins = totalWins; }
    public void setPenaltyDeaths(int penaltyDeaths) { this.penaltyDeaths = penaltyDeaths; }

    public void addKills(int amount) { this.totalKills += amount; }
    public void addDeaths(int amount) { this.totalDeaths += amount; }
    public void addPenaltyDeaths(int amount) { this.penaltyDeaths += amount; }
    public void addMatch() { this.totalMatches++; }
    public void addWin() { this.totalWins++; }

    /** 【作用】计算 K/D 比（死亡数为 0 时按 1 兜底，避免除零）。 */
    public double getKD() {
        // 死亡数为 0 时按 1 计算，避免除零
        int deaths = totalDeaths == 0 ? 1 : totalDeaths;
        return (double) totalKills / deaths;
    }

    /** 【作用】K/D 保留两位小数的字符串形式，供展示。 */
    public String getKDString() {
        return String.format("%.2f", getKD());
    }
}