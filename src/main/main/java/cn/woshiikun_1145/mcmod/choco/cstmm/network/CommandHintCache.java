package cn.woshiikun_1145.mcmod.choco.cstmm.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 【作用】命令 Tab 补全的战队提示缓存（"战队名 → 成员名列表"映射）：
 *         /cstmm data get|edit|delete clan 的建议 provider 在双端执行——服务端有
 *         ClanManager 真数据，客户端只有本缓存（由 clan_hints 包写入）。
 *         读取方统一走 getNames/getMembers：缓存非空（客户端收到过包）用缓存，
 *         否则回退 ClanManager（服务端控制台/服务端内执行场景，数据在本地）。
 * 【被谁使用】写入：ClientNetworkHandler 的 ClanHintsPayload 接收器（仅客户端）；
 *           读取：ModCommands 的 suggestClanNames / suggestClanArgs / clanValueSuggestions。
 */
public final class CommandHintCache {

    /** 战队名 → 成员名列表（volatile 整体替换，读侧无锁） */
    private static volatile Map<String, List<String>> hints = Map.of();

    private CommandHintCache() {
    }

    /** 【作用】整体替换提示缓存（客户端收到 clan_hints 包时调用）。 */
    public static void set(Map<String, List<String>> newHints) {
        hints = newHints == null ? Map.of() : Map.copyOf(newHints);
    }

    /** 【作用】清空缓存（客户端断线时调用，避免残留上一服务器的战队名）。 */
    public static void clear() {
        hints = Map.of();
    }

    /** 【作用】全部战队名列表（顺序保持服务端下发顺序；缓存为空时由调用方回退 ClanManager）。 */
    public static List<String> getNames() {
        return new ArrayList<>(hints.keySet());
    }

    /** 【作用】查询战队名是否存在。 */
    public static boolean hasClan(String name) {
        return hints.containsKey(name);
    }

    /** 【作用】指定战队的成员名列表（无该战队返回 null，由调用方回退或判空）。 */
    public static List<String> getMembers(String name) {
        List<String> members = hints.get(name);
        return members == null ? null : new ArrayList<>(members);
    }

    /** 【作用】缓存是否为空（为空时调用方应回退 ClanManager 本地数据源）。 */
    public static boolean isEmpty() {
        return hints.isEmpty();
    }
}
