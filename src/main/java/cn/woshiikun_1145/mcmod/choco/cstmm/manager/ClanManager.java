package cn.woshiikun_1145.mcmod.choco.cstmm.manager;

import cn.woshiikun_1145.mcmod.choco.cstmm.Cstmm;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.Clan;
import cn.woshiikun_1145.mcmod.choco.cstmm.network.NetworkHandler;
import cn.woshiikun_1145.mcmod.choco.cstmm.network.payload.BadgePayload;
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
 */
public class ClanManager {

    public static final int MAX_NAME_LENGTH = 128;
    public static final int MAX_ABBR_LENGTH = 10;
    /** 徽标 base64 上限 = 分片上传理论上限（每片 30000 × 最多 64 片），约 1.4MB PNG */
    public static final int MAX_BADGE_LENGTH = BadgePayload.MAX_PART_CHARS * 64;
    /** 随机展示的战队数量（队列/战队页列表） */
    public static final int RANDOM_LIST_SIZE = 10;

    /** 缩写字符集：仅限中日韩文字、英文、数字（用户指定） */
    private static final String ABBR_PATTERN = "[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}a-zA-Z0-9]+";

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

    public static ClanManager getInstance() {
        if (instance == null) {
            instance = new ClanManager();
        }
        return instance;
    }

    private Path dataFile() {
        return FabricLoaderHolder.configDir().resolve("cstmm").resolve("data").resolve("clans.json");
    }

    // ==================== 查询 ====================

    public Clan getClanByPlayer(UUID uuid) {
        return clanByMember.get(uuid);
    }

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

    /** 随机取 N 个战队（列表页“只随机展示10条”） */
    public List<Clan> getRandomClans() {
        List<Clan> all = new ArrayList<>(clansByName.values());
        Collections.shuffle(all, new Random());
        return all.subList(0, Math.min(RANDOM_LIST_SIZE, all.size()));
    }

    /** 搜索：完整/部分匹配战队名称、缩写、队长名（不区分大小写） */
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

    // ==================== 操作 ====================

    /** 创建战队；成功返回 null，失败返回错误消息 */
    public String create(ServerPlayerEntity player, String name, String abbr, String badge, int memberLimit) {
        if (loadFailed) return "§c战队数据文件损坏，请联系管理员修复后重启服务器！";
        UUID uuid = player.getUuid();
        if (clanByMember.containsKey(uuid)) return "POPUP:§c你已加入战队，退出后才能创建新战队！";
        name = name == null ? "" : name.trim();
        abbr = abbr == null ? "" : abbr.trim();
        if (name.isEmpty()) return "§c战队名称不能为空！";
        if (name.length() > MAX_NAME_LENGTH) return "§c战队名称过长（最多 " + MAX_NAME_LENGTH + " 字）！";
        if (name.contains("§")) return "§c战队名称不能包含格式化字符 §！";
        if (abbr.isEmpty()) return "§c战队缩写不能为空！";
        if (abbr.length() > MAX_ABBR_LENGTH) return "§c战队缩写过长（最多 " + MAX_ABBR_LENGTH + " 字符）！";
        if (!abbr.matches(ABBR_PATTERN)) return "§c战队缩写仅限中日韩文字、英文、数字！";
        if (badge != null && badge.length() > MAX_BADGE_LENGTH) {
            return "§c徽标过大（base64 最多 " + MAX_BADGE_LENGTH + " 字符）！";
        }
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

    /** 加入战队（受成员上限约束）；成功返回 null，失败返回错误消息 */
    public String join(ServerPlayerEntity player, String clanName) {
        if (loadFailed) return "§c战队数据文件损坏，请联系管理员修复后重启服务器！";
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

    /** 退出战队：队长退出自动转让给最早加入的成员（仅剩队长时解散） */
    public String leave(ServerPlayerEntity player) {
        if (loadFailed) return "§c战队数据文件损坏，请联系管理员修复后重启服务器！";
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

    /** 解散战队（仅队长） */
    public String disband(ServerPlayerEntity player) {
        if (loadFailed) return "§c战队数据文件损坏，请联系管理员修复后重启服务器！";
        Clan clan = clanByMember.get(player.getUuid());
        if (clan == null) return "§c你未加入任何战队！";
        if (!clan.leader.equals(player.getUuid().toString())) return "§c只有队长才能解散战队！";
        notifyAllOnline(clan, "§c战队「" + clan.name + "」已被队长解散");
        removeClan(clan);
        save();
        pushMineToClan(clan);
        return null;
    }

    /** 转让队长（仅队长，目标须为战队成员） */
    public String transfer(ServerPlayerEntity player, String targetName) {
        if (loadFailed) return "§c战队数据文件损坏，请联系管理员修复后重启服务器！";
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
     */
    public String edit(ServerPlayerEntity player, String name, String abbr, String badge, int memberLimit) {
        if (loadFailed) return "§c战队数据文件损坏，请联系管理员修复后重启服务器！";
        Clan clan = clanByMember.get(player.getUuid());
        if (clan == null) return "§c你未加入任何战队！";
        if (!clan.leader.equals(player.getUuid().toString())) return "§c只有队长才能编辑战队信息！";
        name = name == null ? "" : name.trim();
        abbr = abbr == null ? "" : abbr.trim();
        if (name.isEmpty()) return "§c战队名称不能为空！";
        if (name.length() > MAX_NAME_LENGTH) return "§c战队名称过长（最多 " + MAX_NAME_LENGTH + " 字）！";
        if (name.contains("§")) return "§c战队名称不能包含格式化字符 §！";
        if (abbr.isEmpty()) return "§c战队缩写不能为空！";
        if (abbr.length() > MAX_ABBR_LENGTH) return "§c战队缩写过长（最多 " + MAX_ABBR_LENGTH + " 字符）！";
        if (!abbr.matches(ABBR_PATTERN)) return "§c战队缩写仅限中日韩文字、英文、数字！";
        if (badge != null && badge.length() > MAX_BADGE_LENGTH) {
            return "§c徽标过大（base64 最多 " + MAX_BADGE_LENGTH + " 字符）！";
        }
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

    /** 踢出成员（仅队长，支持按名踢出离线成员） */
    public String kick(ServerPlayerEntity player, String targetName) {
        if (loadFailed) return "§c战队数据文件损坏，请联系管理员修复后重启服务器！";
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

    /** 玩家名更新（上线时刷新成员显示名） */
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

    /** 战队发生变化：向全体在线成员推送最新 MINE 状态（事件驱动，替代客户端轮询） */
    private void pushMineToClan(Clan clan) {
        for (Clan.Member m : clan.members) {
            try {
                NetworkHandler.sendClanMineTo(UUID.fromString(m.getUuid()));
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private void transferTo(Clan clan, Clan.Member newLeader) {
        clan.leader = newLeader.getUuid();
        // 队长移到成员列表首位（保持"最早加入在前"的次序语义）
        clan.members.remove(newLeader);
        clan.members.add(0, newLeader);
    }

    private Clan.Member findMemberByName(Clan clan, String name) {
        if (name == null) return null;
        String n = name.trim();
        for (Clan.Member m : clan.members) {
            if (m.getName() != null && m.getName().equalsIgnoreCase(n)) return m;
        }
        return null;
    }

    private void removeMember(Clan clan, UUID uuid) {
        clan.members.removeIf(m -> m.getUuid().equals(uuid.toString()));
        clanByMember.remove(uuid);
    }

    private void removeClan(Clan clan) {
        clansByName.remove(clan.name.toLowerCase());
        clansByAbbr.remove(clan.abbreviation.toLowerCase());
        for (Clan.Member m : clan.members) {
            clanByMember.remove(UUID.fromString(m.getUuid()));
        }
        save();
    }

    private void notifyAllOnline(Clan clan, String message) {
        for (Clan.Member m : clan.members) {
            ServerPlayerEntity p = findOnlinePlayer(m);
            if (p != null) p.sendMessage(Text.literal(message), false);
        }
    }

    private void notifyMemberOnline(Clan clan, Clan.Member member, String message) {
        ServerPlayerEntity p = findOnlinePlayer(member);
        if (p != null) p.sendMessage(Text.literal(message), false);
    }

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
