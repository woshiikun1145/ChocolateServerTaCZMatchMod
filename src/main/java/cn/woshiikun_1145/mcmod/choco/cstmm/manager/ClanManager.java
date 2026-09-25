package cn.woshiikun_1145.mcmod.choco.cstmm.manager;

import cn.woshiikun_1145.mcmod.choco.cstmm.Cstmm;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.Clan;
import cn.woshiikun_1145.mcmod.choco.cstmm.network.NetworkHandler;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * 战队管理器：创建/加入/退出/解散/转让/踢人、查询与持久化。
 * 战队只影响匹配分队的偏好（同战队尽量同队、不同战队尽量不同队），
 * 不影响 KD、击杀、商店等任何其他功能。
 *
 * 持久化：config/cstmm/data/clans.json，原子写盘（临时文件 + ATOMIC_MOVE）；
 * 加载失败（JsonParseException/IOException）时置 loadFailed，拒绝一切变更且绝不覆盖用户文件。
 *
 * 【被谁使用】NetworkHandler（处理玩家战队操作 payload、徽标分片下发、进服刷新成员显示名）、
 * QuickMatchEngine / QueueManager（匹配分队偏好、队列列表展示）、ModCommands（管理员战队命令）；
 * 静态工具 isBadgeUrl/validateBadge 与徽标常量还被客户端 ClanTabPanel、BadgeCache、UrlImageCache 引用。
 * 全部仅服务端逻辑。
 */
public class ClanManager {

    public static final int MAX_NAME_LENGTH = 128;
    public static final int MAX_ABBR_LENGTH = 10;
    /** 徽标解码后大小上限（48KiB）：防止超大徽标分片下发挤爆服务器带宽 */
    public static final int MAX_BADGE_BYTES = 48 * 1024;
    /** 徽标 URL 长度上限（URL 徽标由客户端自行下载，不占服务器带宽，仅限字符串长度） */
    public static final int MAX_BADGE_URL_LENGTH = 512;
    /** 徽标 base64 字符数上限 = 48KiB 解码后的 base64 长度（预过滤；精确校验见 validateBadge） */
    public static final int MAX_BADGE_LENGTH = ((MAX_BADGE_BYTES + 2) / 3) * 4;
    /** 随机展示的战队数量（队列/战队页列表） */
    public static final int RANDOM_LIST_SIZE = 10;

    /** 缩写字符集：仅限中日韩文字、英文、数字（用户指定） */
    private static final String ABBR_PATTERN = "[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}a-zA-Z0-9]+";

    /** 战队数据文件损坏时的统一错误提示 */
    private static final String LOAD_FAILED_MSG = "§c战队数据文件损坏，请联系管理员修复后重启服务器！";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static ClanManager instance;

    /** key: 战队名小写 */
    private final Map<String, Clan> clansByName = new HashMap<>();
    /** key: 战队缩写小写 */
    private final Map<String, Clan> clansByAbbr = new HashMap<>();
    /** key: 成员 uuid */
    private final Map<UUID, Clan> clanByMember = new HashMap<>();
    private boolean loadFailed = false;

    private ClanManager() {
        load();
    }

    // 单例入口：首次调用时触发 load() 从磁盘加载战队数据
    public static ClanManager getInstance() {
        if (instance == null) {
            instance = new ClanManager();
        }
        return instance;
    }

    // 数据文件路径：config/cstmm/data/clans.json
    private Path dataFile() {
        return FabricLoaderHolder.configDir().resolve("cstmm").resolve("data").resolve("clans.json");
    }

    // ==================== 查询 ====================

    /**
     * 【作用】查询玩家所在战队（未加入返回 null）。
     * 【被谁使用】QuickMatchEngine（分队偏好）、QueueManager（队列列表展示战队标记）、
     *           NetworkHandler（登录/同步时推送玩家的战队信息）。仅服务端。
     */
    public Clan getClanByPlayer(UUID uuid) {
        return clanByMember.get(uuid);
    }

    /**
     * 【作用】按战队名（不区分大小写）查询战队。
     * 【被谁使用】ModCommands（管理员查询战队）、join（加入战队前查找目标）。仅服务端。
     */
    public Clan getClan(String name) {
        return name == null ? null : clansByName.get(name.toLowerCase());
    }

    /** 按徽标内容 id 反查战队（客户端徽标缓存缺失请求用）；战队数量少，直接遍历 */
    public Clan getClanByBadgeId(String badgeId) {
        if (badgeId == null || badgeId.isEmpty()) return null;
        for (Clan clan : clansByName.values()) {
            if (clan.getBadgeId().equals(badgeId)) return clan;
        }
        return null;
    }

    /** 全部战队名（/cstmm data 命令 Tab 补全用） */
    public List<String> getAllClanNames() {
        List<String> names = new ArrayList<>();
        for (Clan clan : clansByName.values()) {
            names.add(clan.getName());
        }
        return names;
    }

    /** 随机取 N 个战队（列表页“只随机展示10条”）
     * 【被谁使用】NetworkHandler（客户端战队列表页请求且无搜索词时随机展示）。仅服务端。 */
    public List<Clan> getRandomClans() {
        List<Clan> all = new ArrayList<>(clansByName.values());
        Collections.shuffle(all, new Random());
        return all.subList(0, Math.min(RANDOM_LIST_SIZE, all.size()));
    }

    /** 搜索：完整/部分匹配战队名称、缩写、队长名（不区分大小写）
     * 【被谁使用】NetworkHandler（客户端战队列表页搜索请求）。仅服务端。 */
    public List<Clan> search(String query) {
        String q = query == null ? "" : query.trim().toLowerCase();
        List<Clan> result = new ArrayList<>();
        if (q.isEmpty()) return result;
        for (Clan clan : clansByName.values()) {
            if (clan.getName().toLowerCase().contains(q)
                    || clan.getAbbreviation().toLowerCase().contains(q)
                    || leaderOrMemberNameContains(clan, q)) {
                result.add(clan);
            }
        }
        return result;
    }

    private boolean leaderOrMemberNameContains(Clan clan, String q) {
        for (Clan.Member m : clan.getMembers()) {
            if (m.getName() != null && m.getName().toLowerCase().contains(q)) return true;
        }
        return false;
    }

    // ==================== 徽标校验 ====================

    /** 徽标是否为图片 URL（http/https 开头）：URL 徽标由客户端自行下载，不占服务器带宽
     * 【被谁使用】服务端 NetworkHandler/ModCommands 与客户端 ClanTabPanel、BadgeCache、UrlImageCache
     *           （判断徽标走 URL 还是 base64 分支）。服务端与客户端通用。 */
    public static boolean isBadgeUrl(String badge) {
        return badge != null && (badge.startsWith("http://") || badge.startsWith("https://"));
    }

    /**
     * 校验徽标内容：URL 仅限字符串长度；base64 解码后 ≤ MAX_BADGE_BYTES（48KiB）。
     * 返回 null 表示通过，否则返回可直接发给玩家的错误消息（客户端对话框复用同一规则）。
     * 【被谁使用】服务端 create/edit 创建与编辑校验；客户端 ClanTabPanel（创建/编辑对话框提交前预校验，
     *           与服务端保持同一规则）。
     */
    public static String validateBadge(String badge) {
        if (badge == null || badge.isEmpty()) return null;
        if (isBadgeUrl(badge)) {
            if (badge.length() > MAX_BADGE_URL_LENGTH) {
                return "§c徽标 URL 过长（最多 " + MAX_BADGE_URL_LENGTH + " 字符）！";
            }
            return null;
        }
        if (badge.length() > MAX_BADGE_LENGTH) {
            return oversizedMessage();
        }
        // 精确校验：剥掉 data URI 前缀与空白后按 base64 解码，比较解码字节数
        String s = badge.trim();
        int comma = s.indexOf(',');
        if (s.startsWith("data:") && comma > 0) s = s.substring(comma + 1);
        byte[] decoded;
        try {
            decoded = java.util.Base64.getMimeDecoder().decode(s.replaceAll("\\s", ""));
        } catch (IllegalArgumentException e) {
            return "§c徽标 base64 无效！";
        }
        if (decoded.length > MAX_BADGE_BYTES) return oversizedMessage();
        return null;
    }

    private static String oversizedMessage() {
        return "§c徽标过大（解码后最大 " + (MAX_BADGE_BYTES / 1024) + "KiB）！";
    }

    /** 战队名称校验（非空/长度/禁用字符）；通过返回 null，否则返回可直接发给玩家的错误消息 */
    private static String validateClanName(String name) {
        if (name.isEmpty()) return "§c战队名称不能为空！";
        if (name.length() > MAX_NAME_LENGTH) return "§c战队名称过长（最多 " + MAX_NAME_LENGTH + " 字）！";
        if (name.contains("§")) return "§c战队名称不能包含格式化字符 §！";
        return null;
    }

    /** 战队缩写校验（非空/长度/字符集）；通过返回 null，否则返回可直接发给玩家的错误消息 */
    private static String validateAbbr(String abbr) {
        if (abbr.isEmpty()) return "§c战队缩写不能为空！";
        if (abbr.length() > MAX_ABBR_LENGTH) return "§c战队缩写过长（最多 " + MAX_ABBR_LENGTH + " 字符）！";
        if (!abbr.matches(ABBR_PATTERN)) return "§c战队缩写仅限中日韩文字、英文、数字！";
        return null;
    }

    // ==================== 操作 ====================

    /** 创建战队；成功返回 null，失败返回错误消息
     * 【被谁使用】NetworkHandler（处理客户端 ClanActionPayload 的 CREATE 操作）。仅服务端。 */
    public String create(ServerPlayerEntity player, String name, String abbr, String badge, int memberLimit) {
        if (loadFailed) return LOAD_FAILED_MSG;
        UUID uuid = player.getUuid();
        if (clanByMember.containsKey(uuid)) return "POPUP:§c你已加入战队，退出后才能创建新战队！";
        name = name == null ? "" : name.trim();
        abbr = abbr == null ? "" : abbr.trim();
        String nameError = validateClanName(name);
        if (nameError != null) return nameError;
        String abbrError = validateAbbr(abbr);
        if (abbrError != null) return abbrError;
        String badgeError = validateBadge(badge);
        if (badgeError != null) return badgeError;
        if (memberLimit < 0) return "§c成员数量限制不能为负（0 = 无限制）！";
        String nameKey = name.toLowerCase();
        String abbrKey = abbr.toLowerCase();
        if (clansByName.containsKey(nameKey)) return "§c该战队名称已存在！";
        if (clansByAbbr.containsKey(abbrKey)) return "POPUP:§c该战队缩写已存在！";

        Clan clan = new Clan();
        clan.name = name;
        clan.abbreviation = abbr;
        clan.setBadgeBase64(badge == null ? "" : badge);
        clan.leader = uuid.toString();
        clan.memberLimit = memberLimit;
        clan.createdAt = System.currentTimeMillis();
        clan.members.add(new Clan.Member(uuid.toString(), player.getName().getString()));

        clansByName.put(nameKey, clan);
        clansByAbbr.put(abbrKey, clan);
        clanByMember.put(uuid, clan);
        save();
        NetworkHandler.sendClanMineTo(uuid);
        return null;
    }

    /** 加入战队（受成员上限约束）；成功返回 null，失败返回错误消息
     * 【被谁使用】NetworkHandler（处理客户端 ClanActionPayload 的 JOIN 操作）。仅服务端。 */
    public String join(ServerPlayerEntity player, String clanName) {
        if (loadFailed) return LOAD_FAILED_MSG;
        UUID uuid = player.getUuid();
        if (clanByMember.containsKey(uuid)) return "POPUP:§c你已加入战队，请先退出当前战队！";
        Clan clan = getClan(clanName);
        if (clan == null) return "§c该战队不存在或已被解散！";
        if (clan.isFull()) return "§c该战队已满员（" + clan.members.size() + "/" + clan.memberLimit + "）！";
        clan.members.add(new Clan.Member(uuid.toString(), player.getName().getString()));
        clanByMember.put(uuid, clan);
        save();
        pushMineToClan(clan);
        return null;
    }

    /** 退出战队：队长退出自动转让给最早加入的成员（仅剩队长时解散）
     * 【被谁使用】NetworkHandler（处理客户端 ClanActionPayload 的 LEAVE 操作）。仅服务端。 */
    public String leave(ServerPlayerEntity player) {
        if (loadFailed) return LOAD_FAILED_MSG;
        UUID uuid = player.getUuid();
        Clan clan = clanByMember.get(uuid);
        if (clan == null) return "§c你未加入任何战队！";
        boolean isLeader = clan.leader.equals(uuid.toString());
        if (isLeader && clan.members.size() > 1) {
            // 自动转让给最早加入的其他成员（members[0] 为队长，取下一个）
            Clan.Member next = clan.members.get(1);
            transferTo(clan, next);
            notifyMemberOnline(clan, next, "§e你已成为战队「" + clan.name + "」的队长（原队长退出，自动转让）");
        }
        removeMember(clan, uuid);
        if (clan.members.isEmpty()) {
            removeClan(clan);
            Cstmm.LOGGER.info("[CSTMM - ClanManager] Clan {} disbanded (last member left)", clan.name);
        } else {
            save();
        }
        pushMineToClan(clan);
        return null;
    }

    /** 解散战队（仅队长）
     * 【被谁使用】NetworkHandler（处理客户端 ClanActionPayload 的 DISBAND 操作）。仅服务端。 */
    public String disband(ServerPlayerEntity player) {
        if (loadFailed) return LOAD_FAILED_MSG;
        Clan clan = clanByMember.get(player.getUuid());
        if (clan == null) return "§c你未加入任何战队！";
        if (!clan.leader.equals(player.getUuid().toString())) return "§c只有队长才能解散战队！";
        notifyAllOnline(clan, "§c战队「" + clan.name + "」已被队长解散");
        removeClan(clan);
        save();
        pushMineToClan(clan);
        return null;
    }

    /**
     * 【作用】管理员删除战队（/cstmm data delete clan）：与队长解散同流程——通知在线成员、
     *         清除名称/缩写/成员三索引、落盘、向成员客户端推送最新 MINE（收到 inClan:false）。
     * 【被谁使用】ModCommands（/cstmm data delete clan 命令）。仅服务端。
     * @return null 表示成功；否则为可直接发给玩家的错误消息
     */
    public String deleteByAdmin(String clanName) {
        if (loadFailed) return LOAD_FAILED_MSG;
        Clan clan = getClan(clanName);
        if (clan == null) return "§c未找到战队: §f" + clanName;
        notifyAllOnline(clan, "§c战队「" + clan.name + "」已被管理员删除");
        removeClan(clan);
        pushMineToClan(clan);
        Cstmm.LOGGER.info("[CSTMM - ClanManager] Clan {} deleted by admin", clan.name);
        return null;
    }

    /** 转让队长（仅队长，目标须为战队成员）
     * 【被谁使用】NetworkHandler（处理客户端 ClanActionPayload 的 TRANSFER 操作）。仅服务端。 */
    public String transfer(ServerPlayerEntity player, String targetName) {
        if (loadFailed) return LOAD_FAILED_MSG;
        Clan clan = clanByMember.get(player.getUuid());
        if (clan == null) return "§c你未加入任何战队！";
        if (!clan.leader.equals(player.getUuid().toString())) return "§c只有队长才能转让队长！";
        Clan.Member target = findMemberByName(clan, targetName);
        if (target == null) return "§c该玩家不是本战队成员！";
        if (target.getUuid().equals(player.getUuid().toString())) return "§c你已经是队长！";
        transferTo(clan, target);
        save();
        notifyMemberOnline(clan, target, "§a你已成为战队「" + clan.name + "」的队长！");
        pushMineToClan(clan);
        return null;
    }

    /**
     * 编辑战队信息（仅队长）：四项全部可改；名称/缩写唯一性校验排除自身；
     * 成员上限允许改小到低于当前人数（不踢人，仅影响后续加入）。
     * 【被谁使用】NetworkHandler（处理客户端 ClanActionPayload 的 EDIT 操作）。仅服务端。
     */
    public String edit(ServerPlayerEntity player, String name, String abbr, String badge, int memberLimit) {
        if (loadFailed) return LOAD_FAILED_MSG;
        Clan clan = clanByMember.get(player.getUuid());
        if (clan == null) return "§c你未加入任何战队！";
        if (!clan.leader.equals(player.getUuid().toString())) return "§c只有队长才能编辑战队信息！";
        name = name == null ? "" : name.trim();
        abbr = abbr == null ? "" : abbr.trim();
        String nameError = validateClanName(name);
        if (nameError != null) return nameError;
        String abbrError = validateAbbr(abbr);
        if (abbrError != null) return abbrError;
        String badgeError = validateBadge(badge);
        if (badgeError != null) return badgeError;
        if (memberLimit < 0) return "§c成员数量限制不能为负（0 = 无限制）！";
        // 唯一性校验（排除自身）
        Clan byName = clansByName.get(name.toLowerCase());
        if (byName != null && byName != clan) return "§c该战队名称已存在！";
        Clan byAbbr = clansByAbbr.get(abbr.toLowerCase());
        if (byAbbr != null && byAbbr != clan) return "POPUP:§c该战队缩写已存在！";

        // 名称/缩写变更需同步重建索引键
        if (!clan.name.equalsIgnoreCase(name)) {
            clansByName.remove(clan.name.toLowerCase());
            clansByName.put(name.toLowerCase(), clan);
        }
        if (!clan.abbreviation.equalsIgnoreCase(abbr)) {
            clansByAbbr.remove(clan.abbreviation.toLowerCase());
            clansByAbbr.put(abbr.toLowerCase(), clan);
        }
        clan.name = name;
        clan.abbreviation = abbr;
        clan.setBadgeBase64(badge == null ? "" : badge);
        clan.memberLimit = memberLimit;
        save();
        pushMineToClan(clan);
        return null;
    }

    /** 踢出成员（仅队长，支持按名踢出离线成员）
     * 【被谁使用】NetworkHandler（处理客户端 ClanActionPayload 的 KICK 操作）。仅服务端。 */
    public String kick(ServerPlayerEntity player, String targetName) {
        if (loadFailed) return LOAD_FAILED_MSG;
        Clan clan = clanByMember.get(player.getUuid());
        if (clan == null) return "§c你未加入任何战队！";
        if (!clan.leader.equals(player.getUuid().toString())) return "§c只有队长才能踢出成员！";
        Clan.Member target = findMemberByName(clan, targetName);
        if (target == null) return "§c该玩家不是本战队成员！";
        if (target.getUuid().equals(player.getUuid().toString())) return "§c不能踢出自己（如需离开请使用退出战队）！";
        removeMember(clan, UUID.fromString(target.getUuid()));
        save();
        notifyMemberOnline(clan, target, "§c你已被移出战队「" + clan.name + "」");
        pushMineToClan(clan);
        return null;
    }

    // ==================== 管理员命令支持（/cstmm data get/edit clan） ====================

    /**
     * 管理员重命名战队：唯一性校验并重建名称索引；成功返回 null，失败返回错误消息。
     * 【被谁使用】ModCommands（/cstmm data edit clan name 命令）。仅服务端。
     */
    public String adminRename(Clan clan, String newName) {
        if (loadFailed) return LOAD_FAILED_MSG;
        newName = newName == null ? "" : newName.trim();
        String err = validateClanName(newName);
        if (err != null) return err;
        Clan byName = clansByName.get(newName.toLowerCase());
        if (byName != null && byName != clan) return "§c该战队名称已存在！";
        clansByName.remove(clan.name.toLowerCase());
        clan.name = newName;
        clansByName.put(newName.toLowerCase(), clan);
        save();
        pushMineToClan(clan);
        return null;
    }

    /** 管理员改战队缩写：唯一性校验并重建缩写索引；成功返回 null，失败返回错误消息
     * 【被谁使用】ModCommands（/cstmm data edit clan abbr 命令）。仅服务端。 */
    public String adminSetAbbr(Clan clan, String abbr) {
        if (loadFailed) return LOAD_FAILED_MSG;
        abbr = abbr == null ? "" : abbr.trim();
        String err = validateAbbr(abbr);
        if (err != null) return err;
        Clan byAbbr = clansByAbbr.get(abbr.toLowerCase());
        if (byAbbr != null && byAbbr != clan) return "POPUP:§c该战队缩写已存在！";
        clansByAbbr.remove(clan.abbreviation.toLowerCase());
        clan.abbreviation = abbr;
        clansByAbbr.put(abbr.toLowerCase(), clan);
        save();
        pushMineToClan(clan);
        return null;
    }

    /** 管理员改成员上限（允许改小到低于当前人数，不踢人，仅影响后续加入）；成功返回 null
     * 【被谁使用】ModCommands（/cstmm data edit clan limit 命令）。仅服务端。 */
    public String adminSetMemberLimit(Clan clan, int limit) {
        if (loadFailed) return LOAD_FAILED_MSG;
        if (limit < 0) return "§c成员数量限制不能为负（0 = 无限制）！";
        clan.memberLimit = limit;
        save();
        pushMineToClan(clan);
        return null;
    }

    /**
     * 【作用】管理员改战队徽标：支持 URL（http/https 开头）、base64（复用 validateBadge，
     *         ≤48KiB；游戏内聊天命令有 256 字符上限，超长 base64 需经服务器控制台输入）、
     *         以及 clear/空串清空徽标；成员客户端自动收到最新 MINE（含新徽标分片按需下发）。
     * 【被谁使用】ModCommands（/cstmm data edit clan badge 命令）。仅服务端。
     */
    public String adminSetBadge(Clan clan, String value) {
        if (loadFailed) return LOAD_FAILED_MSG;
        value = value == null ? "" : value.trim();
        if (value.isEmpty() || value.equalsIgnoreCase("clear")) {
            clan.setBadgeBase64("");
            save();
            pushMineToClan(clan);
            return null;
        }
        String err = validateBadge(value);
        if (err != null) return err;
        clan.setBadgeBase64(value);
        save();
        pushMineToClan(clan);
        return null;
    }

    /** 管理员转让队长：目标须为战队成员（支持离线成员，按最近可见名匹配）；成功返回 null
     * 【被谁使用】ModCommands（/cstmm data edit clan leader 命令）。仅服务端。 */
    public String adminSetLeader(Clan clan, String memberName) {
        if (loadFailed) return LOAD_FAILED_MSG;
        Clan.Member target = findMemberByName(clan, memberName);
        if (target == null) return "§c该玩家不是本战队成员！";
        if (target.getUuid().equals(clan.leader)) return "§e该玩家已经是队长！";
        transferTo(clan, target);
        save();
        notifyMemberOnline(clan, target, "§a你已成为战队「" + clan.name + "」的队长（管理员操作）！");
        pushMineToClan(clan);
        return null;
    }

    /** 玩家名更新（上线时刷新成员显示名）
     * 【被谁使用】NetworkHandler（玩家进服事件处理中调用，保证离线成员名最新）。仅服务端。 */
    public void updateMemberName(UUID uuid, String name) {
        Clan clan = clanByMember.get(uuid);
        if (clan == null) return;
        for (Clan.Member m : clan.members) {
            if (m.getUuid().equals(uuid.toString())) {
                m.setName(name);
                return;
            }
        }
    }

    // ==================== 内部工具 ====================

    /**
     * 战队发生变化：向全体在线成员推送最新 MINE 状态（事件驱动，替代客户端轮询），
     * 并向队列状态订阅者重推快照——队列快照的 clan 行（inClan/名称/成员排队明细）依赖战队数据，
     * 若只推 MINE，"先匹配后入队/退队"的玩家队列页会一直显示旧战队状态（如"你还没有加入战队"）。
     */
    private void pushMineToClan(Clan clan) {
        for (Clan.Member m : clan.members) {
            try {
                NetworkHandler.sendClanMineTo(UUID.fromString(m.getUuid()));
            } catch (IllegalArgumentException ignored) {
            }
        }
        // 战队成员/信息变化影响队列快照内容，向所有订阅者（打开队列页的玩家）重推最新快照
        NetworkHandler.pushQueueStatusToSubscribers();
    }

    // 【作用】将队长移交给指定成员，并把新队长移到成员列表首位
    private void transferTo(Clan clan, Clan.Member newLeader) {
        clan.leader = newLeader.getUuid();
        // 队长移到成员列表首位（保持"最早加入在前"的次序语义）
        clan.members.remove(newLeader);
        clan.members.add(0, newLeader);
    }

    // 【作用】按显示名（不区分大小写）在战队内查找成员，支持离线成员
    private Clan.Member findMemberByName(Clan clan, String name) {
        if (name == null) return null;
        String n = name.trim();
        for (Clan.Member m : clan.members) {
            if (m.getName() != null && m.getName().equalsIgnoreCase(n)) return m;
        }
        return null;
    }

    // 【作用】从战队成员列表与成员索引中移除指定玩家
    private void removeMember(Clan clan, UUID uuid) {
        clan.members.removeIf(m -> m.getUuid().equals(uuid.toString()));
        clanByMember.remove(uuid);
    }

    // 【作用】从三个索引（名称/缩写/成员）中彻底清除战队并落盘
    private void removeClan(Clan clan) {
        clansByName.remove(clan.name.toLowerCase());
        clansByAbbr.remove(clan.abbreviation.toLowerCase());
        for (Clan.Member m : clan.members) {
            clanByMember.remove(UUID.fromString(m.getUuid()));
        }
        save();
    }

    // 【作用】向战队全体在线成员发送系统消息（离线成员跳过）
    private void notifyAllOnline(Clan clan, String message) {
        for (Clan.Member m : clan.members) {
            ServerPlayerEntity p = findOnlinePlayer(m);
            if (p != null) p.sendMessage(Text.literal(message), false);
        }
    }

    // 【作用】向单个成员发消息（仅在线时）
    private void notifyMemberOnline(Clan clan, Clan.Member member, String message) {
        ServerPlayerEntity p = findOnlinePlayer(member);
        if (p != null) p.sendMessage(Text.literal(message), false);
    }

    // 【作用】按 UUID 查找在线玩家实体，离线或 UUID 非法返回 null
    private ServerPlayerEntity findOnlinePlayer(Clan.Member member) {
        try {
            var server = Cstmm.getServer();
            if (server == null) return null;
            return server.getPlayerManager().getPlayer(UUID.fromString(member.getUuid()));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ==================== 持久化（原子写盘） ====================

    // 【作用】启动时从 clans.json 加载全部战队并重建三个索引；解析失败置 loadFailed 禁用战队系统
    private void load() {
        Path file = dataFile();
        if (!Files.exists(file)) return;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Clan[] arr = GSON.fromJson(reader, Clan[].class);
            if (arr == null) return;
            for (Clan clan : arr) {
                if (clan == null || clan.name == null || clan.abbreviation == null || clan.members == null) continue;
                if (clan.leader == null) continue;
                if (clansByName.containsKey(clan.name.toLowerCase())) continue;
                clansByName.put(clan.name.toLowerCase(), clan);
                clansByAbbr.put(clan.abbreviation.toLowerCase(), clan);
                for (Clan.Member m : clan.members) {
                    try {
                        clanByMember.put(UUID.fromString(m.getUuid()), clan);
                    } catch (IllegalArgumentException ignored) {
                        // 坏条目跳过，不拖垮整个文件
                    }
                }
            }
            Cstmm.LOGGER.info("[CSTMM - ClanManager] Loaded {} clans", clansByName.size());
        } catch (JsonParseException | IOException | DirectoryIteratorException e) {
            loadFailed = true;
            Cstmm.LOGGER.error("[CSTMM - ClanManager] Failed to load clans.json - clan system disabled until file is fixed", e);
        }
    }

    // 【作用】把全部战队原子写盘（先写 .tmp 再 ATOMIC_MOVE 替换，崩溃不产生半写文件）
    private void save() {
        if (loadFailed) return; // 加载失败绝不覆盖用户文件
        Path file = dataFile();
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(new ArrayList<>(clansByName.values()), writer);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            Cstmm.LOGGER.error("[CSTMM - ClanManager] Failed to save clans.json", e);
        }
    }

    /** 延迟持有 FabricLoader 配置目录（避免本类静态依赖顺序问题） */
    private static final class FabricLoaderHolder {
        static Path configDir() {
            return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir();
        }
    }
}
