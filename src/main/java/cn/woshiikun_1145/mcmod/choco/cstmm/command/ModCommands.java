package cn.woshiikun_1145.mcmod.choco.cstmm.command;

import cn.woshiikun_1145.mcmod.choco.cstmm.Cstmm;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.Clan;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.MatchSession;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.PlayerProfile;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.config.MapConfig;
import cn.woshiikun_1145.mcmod.choco.cstmm.manager.*;
import cn.woshiikun_1145.mcmod.choco.cstmm.network.payload.OpenConfigScreenPayload;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 【作用】/cstmm 命令树的注册与全部子命令执行逻辑（服务端 Brigadier 命令）。
 * 【被谁使用】Cstmm#onInitialize 经 CommandRegistrationCallback.EVENT 注册（Fabric 服务端命令注册回调）。
 * /cstmm 命令树。结构一览：
 * <pre>
 * /cstmm queue    join &lt;地图&gt; [模式] / quick [模式] / leave / status   （玩家）
 * /cstmm match    status / list / forceend &lt;ID&gt;（forceend 需 OP≥2）
 * /cstmm vote     kick &lt;玩家&gt; / kickyes / kickno
 * /cstmm config / reload                                             （OP≥2）
 * /cstmm data     get player|clan / edit player|clan / restore bags / delete player|clan   （OP≥2，支持离线玩家）
 * </pre>
 * 每个节点按 registerXxx 方法拆分注册，执行逻辑为独立静态方法，便于增删子命令。
 */
public class ModCommands {

    // data get clan 展示战队创建时间用的格式化器（服务器本地时区）
    private static final DateTimeFormatter CREATED_AT_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    /**
     * 【作用】注册 /cstmm 命令树根节点，并挂接 queue/match/vote/config/reload/data 各子命令分支。
     * 【被谁使用】Cstmm#onInitialize（以方法引用注册到 Fabric CommandRegistrationCallback，服务端命令注册阶段调用）。
     */
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher,
                                CommandRegistryAccess registryAccess,
                                CommandManager.RegistrationEnvironment environment) {

        var cstmm = CommandManager.literal("cstmm")
                .requires(source -> source.hasPermissionLevel(0))
                .executes(ModCommands::showHelp);

        registerQueue(cstmm);
        registerMatch(cstmm);
        registerVote(cstmm);
        registerConfigAndReload(cstmm);
        registerData(cstmm);

        dispatcher.register(cstmm);
        Cstmm.LOGGER.info("[CSTMM - Commands] Registered commands under /cstmm");
    }

    // ==================== /cstmm queue ====================

    private static void registerQueue(LiteralArgumentBuilder<ServerCommandSource> cstmm) {
        var queue = CommandManager.literal("queue")
                .executes(usage("/cstmm queue join <地图> [模式] | quick [模式] | leave | status"));

        queue.then(CommandManager.literal("join")
                .then(CommandManager.argument("map", StringArgumentType.string())
                        .suggests(ModCommands::suggestMapIds)
                        .executes(ModCommands::queueJoin)
                        .then(CommandManager.argument("mode", StringArgumentType.word())
                                .suggests(ModCommands::suggestModes)
                                .executes(ModCommands::queueJoin))));

        queue.then(CommandManager.literal("quick")
                .executes(ModCommands::queueQuick)
                .then(CommandManager.argument("mode", StringArgumentType.word())
                        .suggests(ModCommands::suggestModes)
                        .executes(ModCommands::queueQuick)));

        queue.then(CommandManager.literal("leave")
                .executes(ModCommands::queueLeave));

        queue.then(CommandManager.literal("status")
                .executes(ModCommands::queueStatus));

        cstmm.then(queue);
    }

    // /cstmm queue join <地图> [模式]：加入指定地图的匹配队列
    private static int queueJoin(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity player = requirePlayer(ctx);
        if (player == null) return 0;
        String mapId = StringArgumentType.getString(ctx, "map");
        QueueManager.getInstance().joinQueue(player, mapId, 0, modeOf(ctx));
        return 1;
    }

    // /cstmm queue quick [模式]：加入快速匹配队列
    private static int queueQuick(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity player = requirePlayer(ctx);
        if (player == null) return 0;
        QueueManager.getInstance().joinQuickQueue(player, modeOf(ctx));
        return 1;
    }

    // /cstmm queue leave：退出匹配队列
    private static int queueLeave(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity player = requirePlayer(ctx);
        if (player == null) return 0;
        QueueManager.getInstance().leaveQueue(player);
        return 1;
    }

    /** 队列状态：自身所在队列 + 快速匹配队列人数（合并原 match status_quick） */
    private static int queueStatus(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity player = requirePlayer(ctx);
        if (player == null) return 0;
        UUID uuid = player.getUuid();
        String state;
        if (MatchManager.getInstance().isInGame(uuid)) {
            state = "§a游戏中";
        } else if (QueueManager.getInstance().isInQueue(uuid)) {
            state = "§e队列中";
        } else {
            state = "§7空闲";
        }
        int quickSize = QueueManager.getInstance().getQuickQueueSize();
        player.sendMessage(Text.literal(
                "§6=== 匹配队列状态 ===\n" +
                        "§e当前状态: " + state + "\n" +
                        "§e快速匹配队列: §f" + quickSize + " 人" +
                        (quickSize >= 2 ? " §a（已有人数，正在尝试匹配...）" : " §7（等待更多玩家加入）")
        ), false);
        return 1;
    }

    // ==================== /cstmm match ====================

    private static void registerMatch(LiteralArgumentBuilder<ServerCommandSource> cstmm) {
        var match = CommandManager.literal("match")
                .executes(usage("/cstmm match status | list | forceend <ID>"));

        match.then(CommandManager.literal("status")
                .executes(ModCommands::matchStatus));

        match.then(CommandManager.literal("list")
                .executes(ModCommands::matchList));

        // forceend <id>：支持 match list 显示的 8 位短 ID（Tab 可补全）
        match.then(CommandManager.literal("forceend")
                .requires(source -> source.hasPermissionLevel(2))
                .then(CommandManager.argument("id", StringArgumentType.string())
                        .suggests(ModCommands::suggestSessionIds)
                        .executes(ModCommands::forceEndSession)));

        cstmm.then(match);
    }

    // ==================== /cstmm vote ====================

    private static void registerVote(LiteralArgumentBuilder<ServerCommandSource> cstmm) {
        var vote = CommandManager.literal("vote")
                .executes(usage("/cstmm vote kick <玩家> | kickyes | kickno"));

        vote.then(CommandManager.literal("kickyes")
                .executes(ctx -> {
                    ServerPlayerEntity player = requirePlayer(ctx);
                    if (player == null) return 0;
                    VoteManager.getInstance().handleVote(player, true);
                    return 1;
                }));

        vote.then(CommandManager.literal("kickno")
                .executes(ctx -> {
                    ServerPlayerEntity player = requirePlayer(ctx);
                    if (player == null) return 0;
                    VoteManager.getInstance().handleVote(player, false);
                    return 1;
                }));

        vote.then(CommandManager.literal("kick")
                .then(CommandManager.argument("player", EntityArgumentType.player())
                        .executes(ctx -> {
                            ServerPlayerEntity player = requirePlayer(ctx);
                            if (player == null) return 0;
                            ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
                            if (target == player) {
                                player.sendMessage(Text.literal("§c你不能投票踢出自己！"), false);
                                return 0;
                            }
                            VoteManager.getInstance().startKickVote(player, target);
                            return 1;
                        })));

        cstmm.then(vote);
    }

    // ==================== /cstmm config | reload ====================

    private static void registerConfigAndReload(LiteralArgumentBuilder<ServerCommandSource> cstmm) {
        cstmm.then(CommandManager.literal("config")
                .requires(source -> source.hasPermissionLevel(2))
                .executes(ctx -> {
                    ServerPlayerEntity player = requirePlayer(ctx);
                    if (player == null) return 0;
                    ServerPlayNetworking.send(player, new OpenConfigScreenPayload());
                    player.sendMessage(Text.literal("§a正在打开配置界面..."), false);
                    return 1;
                }));

        cstmm.then(CommandManager.literal("reload")
                .requires(source -> source.hasPermissionLevel(2))
                .executes(ctx -> {
                    ConfigManager.getInstance().reload();
                    ctx.getSource().sendMessage(Text.literal("§a配置已重新加载！"));
                    return 1;
                }));
    }

    // ==================== /cstmm data（OP≥2） ====================

    private static void registerData(LiteralArgumentBuilder<ServerCommandSource> cstmm) {
        var data = CommandManager.literal("data")
                .requires(source -> source.hasPermissionLevel(2))
                .executes(usage("/cstmm data get player|clan <名称> / edit player|clan <名称> <字段> <值> / restore bags <玩家> [槽位] / delete player|clan <名称> [数据类型]"));

        // ----- restore bags（保留原有能力） -----
        data.then(CommandManager.literal("restore")
                .then(CommandManager.literal("bags")
                        .then(CommandManager.argument("player", EntityArgumentType.player())
                                .executes(ctx -> {
                                    ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
                                    boolean restored = InventoryManager.getInstance().restoreInventory(target);
                                    if (restored) {
                                        ctx.getSource().sendMessage(Text.literal("§a已恢复 " + target.getName() + " 的全部背包"));
                                    } else {
                                        ctx.getSource().sendMessage(Text.literal("§e该玩家没有保存的背包数据"));
                                    }
                                    return 1;
                                })
                                .then(CommandManager.argument("slot", IntegerArgumentType.integer(0, 40))
                                        .executes(ctx -> {
                                            ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
                                            int slot = IntegerArgumentType.getInteger(ctx, "slot");
                                            boolean restored = InventoryManager.getInstance().restoreSlot(target, slot);
                                            if (restored) {
                                                ctx.getSource().sendMessage(Text.literal("§a已恢复 " + target.getName() + " 的槽位 " + slot));
                                            } else {
                                                ctx.getSource().sendMessage(Text.literal("§e该玩家没有保存的背包数据或槽位无效"));
                                            }
                                            return 1;
                                        })))));

        // ----- get player / get clan / get <配置项>（返回 JSON 原文；player/clan 支持可选单字段） -----
        data.then(CommandManager.literal("get")
                .executes(usage("/cstmm data get player|clan <名称> [字段] | maps|global|clans（返回 JSON 原文）"))
                .then(CommandManager.literal("player")
                        .then(CommandManager.argument("player", StringArgumentType.string())
                                .suggests(ModCommands::suggestProfileNames)
                                // 省略字段输出档案摘要；指定字段输出单值
                                .executes(ModCommands::dataGetPlayer)
                                .then(CommandManager.argument("field", StringArgumentType.word())
                                        .suggests(ModCommands::suggestPlayerGetFields)
                                        .executes(ModCommands::dataGetPlayer))))
                .then(CommandManager.literal("clan")
                        .then(CommandManager.argument("clan", StringArgumentType.string())
                                .suggests(ModCommands::suggestClanNames)
                                // 省略字段输出战队摘要；指定字段输出单值
                                .executes(ModCommands::dataGetClan)
                                .then(CommandManager.argument("field", StringArgumentType.word())
                                        .suggests(ModCommands::suggestClanGetFields)
                                        .executes(ModCommands::dataGetClan))))
                .then(CommandManager.literal("maps")
                        .executes(ctx -> dataGetConfigItem(ctx, "maps")))
                .then(CommandManager.literal("global")
                        .executes(ctx -> dataGetConfigItem(ctx, "global")))
                .then(CommandManager.literal("clans")
                        .executes(ctx -> dataGetConfigItem(ctx, "clans"))));

        // ----- edit player / edit clan -----
        data.then(CommandManager.literal("edit")
                .executes(usage("/cstmm data edit player <玩家名|UUID> <字段> <值> | clan <战队名> <字段> <值>"))
                .then(CommandManager.literal("player")
                        .then(CommandManager.argument("player", StringArgumentType.string())
                                .suggests(ModCommands::suggestProfileNames)
                                .then(CommandManager.argument("field", StringArgumentType.word())
                                        .suggests(ModCommands::suggestPlayerFields)
                                        .then(CommandManager.argument("value", IntegerArgumentType.integer(0))
                                                .executes(ModCommands::dataEditPlayer)))))
                .then(CommandManager.literal("clan")
                        .then(CommandManager.argument("clan", StringArgumentType.string())
                                .suggests(ModCommands::suggestClanNames)
                                .then(CommandManager.argument("field", StringArgumentType.word())
                                        .suggests(ModCommands::suggestClanFields)
                                        .then(CommandManager.argument("value", StringArgumentType.greedyString())
                                                .executes(ModCommands::dataEditClan))))));

        // ----- delete player / delete clan（管理员删除数据） -----
        data.then(CommandManager.literal("delete")
                .executes(usage("/cstmm data delete player <玩家名|UUID> [profile|bags|all] | clan <战队名>"))
                .then(CommandManager.literal("player")
                        .then(CommandManager.argument("player", StringArgumentType.string())
                                .suggests(ModCommands::suggestProfileNames)
                                // 省略数据类型默认 all（战绩档案 + 背包快照）
                                .executes(ModCommands::dataDeletePlayer)
                                .then(CommandManager.argument("target", StringArgumentType.word())
                                        .suggests(ModCommands::suggestDeleteTargets)
                                        .executes(ModCommands::dataDeletePlayer))))
                .then(CommandManager.literal("clan")
                        .then(CommandManager.argument("clan", StringArgumentType.string())
                                .suggests(ModCommands::suggestClanNames)
                                .executes(ModCommands::dataDeleteClan))));

        cstmm.then(data);
    }

    /** data get player：查看玩家档案（支持离线玩家与 UUID；可选字段输出单值） */
    private static int dataGetPlayer(CommandContext<ServerCommandSource> ctx) {
        String input = StringArgumentType.getString(ctx, "player");
        String field = optionalField(ctx);
        PlayerProfile profile = PlayerDataManager.getInstance().findProfile(input);
        if (profile == null) {
            ctx.getSource().sendMessage(Text.literal(
                    "§e未找到玩家档案: §f" + input + " §7（离线玩家需曾进入服务器）"));
            return 0;
        }
        if (field != null) {
            String value = switch (field) {
                case "kills" -> String.valueOf(profile.getTotalKills());
                case "deaths" -> String.valueOf(profile.getTotalDeaths());
                case "matches" -> String.valueOf(profile.getTotalMatches());
                case "wins" -> String.valueOf(profile.getTotalWins());
                case "penaltydeaths" -> String.valueOf(profile.getPenaltyDeaths());
                case "kd" -> profile.getKDString();
                case "name" -> profile.getPlayerName();
                case "uuid" -> profile.getPlayerUuid().toString();
                default -> null;
            };
            if (value == null) {
                ctx.getSource().sendMessage(Text.literal(
                        "§c未知字段: " + field + "（可选: kills deaths matches wins penaltydeaths kd name uuid）"));
                return 0;
            }
            ctx.getSource().sendMessage(Text.literal(
                    "§6[玩家档案." + field + "] §f" + value));
            return 1;
        }
        int matches = profile.getTotalMatches();
        double winRate = matches > 0 ? profile.getTotalWins() * 100.0 / matches : 0;
        ctx.getSource().sendMessage(Text.literal(
                "§6=== 玩家档案 ===\n" +
                        "§e玩家: §f" + profile.getPlayerName() + " §7(" + profile.getPlayerUuid() + ")\n" +
                        "§e击杀/死亡: §f" + profile.getTotalKills() + " / " + profile.getTotalDeaths()
                        + " §7(KD " + profile.getKDString() + ")\n" +
                        "§e场次/胜场: §f" + matches + " / " + profile.getTotalWins()
                        + String.format(" §7(胜率 %.1f%%)", winRate) + "\n" +
                        "§e惩罚死亡: §f" + profile.getPenaltyDeaths()));
        return 1;
    }

    /** data get clan：查看战队信息（成员/上限/徽标/创建时间；可选字段输出单值） */
    private static int dataGetClan(CommandContext<ServerCommandSource> ctx) {
        String name = StringArgumentType.getString(ctx, "clan");
        String field = optionalField(ctx);
        Clan clan = ClanManager.getInstance().getClan(name);
        if (clan == null) {
            ctx.getSource().sendMessage(Text.literal("§c未找到战队: " + name));
            return 0;
        }
        String leaderName = "?";
        for (Clan.Member m : clan.getMembers()) {
            if (m.getUuid().equals(clan.getLeader())) {
                leaderName = m.getName();
                break;
            }
        }
        if (field != null) {
            String value = switch (field) {
                case "name" -> clan.getName();
                case "abbr" -> clan.getAbbreviation();
                case "limit" -> String.valueOf(clan.getMemberLimit());
                case "leader" -> leaderName;
                case "members" -> memberListPreview(clan);
                case "createdat" -> CREATED_AT_FORMAT.format(Instant.ofEpochMilli(clan.getCreatedAt()));
                case "badge" -> badgeValueOrHint(clan);
                default -> null;
            };
            if (value == null) {
                ctx.getSource().sendMessage(Text.literal(
                        "§c未知字段: " + field + "（可选: name abbr limit leader members badge createdat）"));
                return 0;
            }
            ctx.getSource().sendMessage(Text.literal(
                    "§6[战队." + field + "] §f" + value));
            return 1;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("§6=== 战队信息 ===\n")
                .append("§e名称: §f").append(clan.getName()).append(" §7[§e").append(clan.getAbbreviation()).append("§7]\n")
                .append("§e队长: §f").append(leaderName).append('\n')
                .append("§e成员: §f").append(clan.getMembers().size())
                .append(clan.getMemberLimit() > 0 ? "§7/" + clan.getMemberLimit() : "§7（无上限）").append('\n');
        // 成员列表最多展示 20 名，防止超大战队刷屏
        int shown = Math.min(clan.getMembers().size(), 20);
        for (int i = 0; i < shown; i++) {
            Clan.Member m = clan.getMembers().get(i);
            sb.append("§7- ").append(m.getUuid().equals(clan.getLeader()) ? "§a[队长] " : "")
                    .append("§f").append(m.getName()).append('\n');
        }
        if (clan.getMembers().size() > shown) {
            sb.append("§7...等共 ").append(clan.getMembers().size()).append(" 名成员\n");
        }
        String badge = clan.getBadgeBase64();
        sb.append("§e徽标: ").append(badge.isEmpty() ? "§7无"
                : ClanManager.isBadgeUrl(badge) ? "§bURL §f" + badge
                : "§7base64 " + badge.length() + " 字符").append('\n');
        sb.append("§e创建时间: §f").append(CREATED_AT_FORMAT.format(Instant.ofEpochMilli(clan.getCreatedAt())));
        ctx.getSource().sendMessage(Text.literal(sb.toString()));
        return 1;
    }

    /**
     * 读取可选的 "field" 参数；未提供（上层无该参数节点）时返回 null。
     * 【被谁使用】dataGetPlayer / dataGetClan（get 单字段查询）。
     */
    private static String optionalField(CommandContext<ServerCommandSource> ctx) {
        try {
            String field = StringArgumentType.getString(ctx, "field");
            return field.isEmpty() ? null : field.toLowerCase();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 成员名列表预览（最多 20 名，超出截断），供 get clan members 字段输出 */
    private static String memberListPreview(Clan clan) {
        int shown = Math.min(clan.getMembers().size(), 20);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < shown; i++) {
            if (i > 0) sb.append(", ");
            sb.append(clan.getMembers().get(i).getName());
        }
        if (clan.getMembers().size() > shown) {
            sb.append(" …（共 ").append(clan.getMembers().size()).append(" 名）");
        }
        return sb.length() == 0 ? "（无成员）" : sb.toString();
    }

    /** 徽标字段输出：URL 全文；base64 超过聊天输出上限时给出长度与文件位置提示 */
    private static String badgeValueOrHint(Clan clan) {
        String badge = clan.getBadgeBase64();
        if (badge.isEmpty()) return "（无徽标）";
        if (ClanManager.isBadgeUrl(badge)) return badge;
        if (badge.length() <= CHAT_JSON_MAX_CHARS) return badge;
        return "（base64 共 " + badge.length() + " 字符，过长请从 config/cstmm/data/clans.json 获取）";
    }

    /** data edit player：修改档案战绩字段（支持离线玩家，改后写盘并同步在线客户端） */
    private static int dataEditPlayer(CommandContext<ServerCommandSource> ctx) {
        String input = StringArgumentType.getString(ctx, "player");
        String field = StringArgumentType.getString(ctx, "field").toLowerCase();
        int value = IntegerArgumentType.getInteger(ctx, "value");
        PlayerDataManager dataManager = PlayerDataManager.getInstance();
        PlayerProfile profile = dataManager.findProfile(input);
        if (profile == null) {
            ctx.getSource().sendMessage(Text.literal(
                    "§e未找到玩家档案: §f" + input + " §7（离线玩家需曾进入服务器）"));
            return 0;
        }
        switch (field) {
            case "kills" -> profile.setTotalKills(value);
            case "deaths" -> profile.setTotalDeaths(value);
            case "matches" -> profile.setTotalMatches(value);
            case "wins" -> profile.setTotalWins(value);
            case "penaltydeaths" -> profile.setPenaltyDeaths(value);
            default -> {
                ctx.getSource().sendMessage(Text.literal(
                        "§c未知字段: " + field + "（可选: kills deaths matches wins penaltyDeaths）"));
                return 0;
            }
        }
        dataManager.persistAndSync(profile.getPlayerUuid());
        ctx.getSource().sendMessage(Text.literal(
                "§a已更新 §f" + profile.getPlayerName() + " §a的 §f" + field + " = " + value + " §7（在线玩家已同步）"));
        return 1;
    }

    /** data edit clan：修改战队字段（name/abbr/limit/leader），校验与索引重建复用 ClanManager */
    private static int dataEditClan(CommandContext<ServerCommandSource> ctx) {
        String name = StringArgumentType.getString(ctx, "clan");
        String field = StringArgumentType.getString(ctx, "field").toLowerCase();
        String value = StringArgumentType.getString(ctx, "value").trim();
        ClanManager clans = ClanManager.getInstance();
        Clan clan = clans.getClan(name);
        if (clan == null) {
            ctx.getSource().sendMessage(Text.literal("§c未找到战队: " + name));
            return 0;
        }
        String err = switch (field) {
            case "name" -> clans.adminRename(clan, value);
            case "abbr" -> clans.adminSetAbbr(clan, value);
            case "limit" -> {
                Integer limit = parseIntOrNull(value);
                yield limit == null ? "§c人数上限必须是数字！" : clans.adminSetMemberLimit(clan, limit);
            }
            case "leader" -> clans.adminSetLeader(clan, value);
            case "badge" -> clans.adminSetBadge(clan, value);
            default -> "§c未知字段: " + field + "（可选: name abbr limit leader badge）";
        };
        if (err != null) {
            ctx.getSource().sendMessage(Text.literal(err));
            return 0;
        }
        // badge 的值可能是超长 base64，成功提示只给摘要防刷屏
        String shownValue = field.equals("badge")
                ? (value.isEmpty() || value.equalsIgnoreCase("clear") ? "已清空"
                        : ClanManager.isBadgeUrl(value) ? value
                        : "base64 共 " + value.length() + " 字符")
                : value;
        ctx.getSource().sendMessage(Text.literal(
                "§a战队「" + clan.getName() + "」的 §f" + field + " §a已更新: §f" + shownValue));
        return 1;
    }

    /**
     * data delete player：删除玩家数据（支持离线玩家与 UUID）。
     * target 省略默认 all；profile=战绩档案、bags=背包快照、all=两者。
     * 【被谁使用】registerData 的 delete player 分支。
     */
    private static int dataDeletePlayer(CommandContext<ServerCommandSource> ctx) {
        String input = StringArgumentType.getString(ctx, "player");
        String target = "all";
        try {
            target = StringArgumentType.getString(ctx, "target");
        } catch (IllegalArgumentException ignored) {
            // 可选参数省略：默认删除全部数据
        }
        PlayerProfile profile = PlayerDataManager.getInstance().findProfile(input);
        if (profile == null) {
            ctx.getSource().sendMessage(Text.literal(
                    "§e未找到玩家档案: §f" + input + " §7（离线玩家需曾进入服务器）"));
            return 0;
        }
        UUID uuid = profile.getPlayerUuid();
        PlayerDataManager dataManager = PlayerDataManager.getInstance();
        InventoryManager inventoryManager = InventoryManager.getInstance();

        boolean any;
        String deleted;
        switch (target) {
            case "profile" -> {
                any = dataManager.deleteProfile(uuid);
                deleted = "战绩档案";
            }
            case "bags" -> {
                any = inventoryManager.deleteSavedInventory(uuid);
                deleted = "背包快照";
            }
            case "all" -> {
                boolean p = dataManager.deleteProfile(uuid);
                boolean b = inventoryManager.deleteSavedInventory(uuid);
                any = p || b;
                deleted = (p ? "战绩档案" : "") + (p && b ? "、" : "") + (b ? "背包快照" : "");
            }
            default -> {
                ctx.getSource().sendMessage(Text.literal(
                        "§c无效的数据类型: §f" + target + " §7（可选: profile bags all）"));
                return 0;
            }
        }
        if (!any) {
            ctx.getSource().sendMessage(Text.literal("§e该玩家没有可删除的数据"));
            return 0;
        }
        ctx.getSource().sendMessage(Text.literal(
                "§a已删除玩家 §f" + profile.getPlayerName() + " §a的" + deleted));
        return 1;
    }

    /** data delete clan：删除战队（等同解散：清除索引并落盘，在线成员收到通知与最新 MINE） */
    private static int dataDeleteClan(CommandContext<ServerCommandSource> ctx) {
        String name = StringArgumentType.getString(ctx, "clan");
        String err = ClanManager.getInstance().deleteByAdmin(name);
        if (err != null) {
            ctx.getSource().sendMessage(Text.literal(err));
            return 0;
        }
        ctx.getSource().sendMessage(Text.literal(
                "§a已删除战队「" + name.trim() + "」及其全部数据"));
        return 1;
    }

    /**
     * data get maps|global|clans：读取磁盘上的 JSON 原文。
     * 小内容直接聊天输出；大内容（含 base64 背景图/徽标的文件可达数 MB）只回
     * 文件路径与开头预览，避免刷爆聊天栏。读磁盘而非内存序列化——返回的即"原文"，
     * 手改未 reload 的差异也如实呈现。
     * item 由各字面量分支直接传入（注册处无名为 "item" 的参数，不能从 ctx 读取）。
     */
    private static int dataGetConfigItem(CommandContext<ServerCommandSource> ctx, String item) {
        Path file = configItemFile(item);
        if (file == null) {
            ctx.getSource().sendMessage(Text.literal(
                    "§c未知配置项: " + item + "（可选: maps global clans）"));
            return 0;
        }
        if (!java.nio.file.Files.exists(file)) {
            ctx.getSource().sendMessage(Text.literal(
                    "§e该配置项暂无数据文件（尚未生成）: §f" + file));
            return 0;
        }
        String json;
        try {
            json = java.nio.file.Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            ctx.getSource().sendMessage(Text.literal("§c读取失败: " + e.getMessage()));
            return 0;
        }
        if (json.length() <= CHAT_JSON_MAX_CHARS) {
            ctx.getSource().sendMessage(Text.literal("§6[" + item + "] §f" + json));
            return 1;
        }
        ctx.getSource().sendMessage(Text.literal(
                "§e[" + item + "] 共 §f" + json.length() + " §e字符，超出聊天展示上限，完整原文见: §f" + file
                        + "\n§7预览: §f" + json.substring(0, 600) + " §7..."));
        return 1;
    }

    /** 聊天直接输出 JSON 原文的字符数上限，超过则只回文件路径 + 预览 */
    private static final int CHAT_JSON_MAX_CHARS = 1500;

    /** data get 配置项 → 磁盘 JSON 文件路径；未知配置项返回 null */
    private static Path configItemFile(String item) {
        Path root = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir();
        return switch (item) {
            case "maps" -> root.resolve("cstmm/configs/maps.json");
            case "global" -> root.resolve("cstmm/configs/global.json");
            case "clans" -> root.resolve("cstmm/data/clans.json");
            default -> null;
        };
    }

    // ==================== 通用执行工具 ====================

    /** 顶层帮助：/cstmm 不带参数时展示子命令一览 */
    private static int showHelp(CommandContext<ServerCommandSource> ctx) {
        ctx.getSource().sendMessage(Text.literal(
                "§6=== CSTMM 命令 ===\n" +
                        "§e/cstmm queue §7- join <地图> [模式] / quick [模式] / leave / status\n" +
                        "§e/cstmm match §7- status / list / forceend <ID>\n" +
                        "§e/cstmm vote §7- kick <玩家> / kickyes / kickno\n" +
                        "§e/cstmm config §7- 打开配置界面（管理员）\n" +
                        "§e/cstmm reload §7- 重载配置（管理员）\n" +
                        "§e/cstmm data §7- get / edit / restore / delete（管理员）"));
        return 1;
    }

    /** 无参数节点的用法提示 */
    private static com.mojang.brigadier.Command<ServerCommandSource> usage(String text) {
        return ctx -> {
            ctx.getSource().sendMessage(Text.literal("§e用法: " + text));
            return 0;
        };
    }

    /** 取执行命令的玩家；非玩家（控制台/命令方块）执行时发提示并返回 null */
    private static ServerPlayerEntity requirePlayer(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendMessage(Text.literal("§c此命令只能由玩家执行"));
        }
        return player;
    }

    /** 模式参数：CASUAL（忽略大小写）按休闲，其余按竞技兜底（与网络包路径同一兜底语义） */
    private static String modeOf(CommandContext<ServerCommandSource> ctx) {
        String mode;
        try {
            mode = StringArgumentType.getString(ctx, "mode");
        } catch (IllegalArgumentException e) {
            return "COMPETITIVE";
        }
        return "CASUAL".equalsIgnoreCase(mode.trim()) ? "CASUAL" : "COMPETITIVE";
    }

    // 安全转 int，失败返回 null
    private static Integer parseIntOrNull(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ==================== Tab 补全 ====================

    /** forceend 的 id 参数补全：列出进行中对局的 8 位短 ID */
    private static CompletableFuture<Suggestions> suggestSessionIds(CommandContext<ServerCommandSource> ctx,
                                                                    SuggestionsBuilder builder) {
        for (MatchSession session : MatchManager.getInstance().getAllActiveSessions()) {
            if (session.getPhase() == MatchSession.GamePhase.ENDED) continue;
            String shortId = session.getSessionId().substring(0, 8);
            if (shortId.startsWith(builder.getRemaining().toLowerCase())) {
                builder.suggest(shortId);
            }
        }
        return builder.buildFuture();
    }

    /** queue join 的地图参数补全：已启用地图的 ID */
    private static CompletableFuture<Suggestions> suggestMapIds(CommandContext<ServerCommandSource> ctx,
                                                                SuggestionsBuilder builder) {
        String remaining = stripQuote(builder.getRemaining()).toLowerCase();
        for (MapConfig map : ConfigManager.getInstance().getMaps()) {
            if (!map.isEnabled()) continue;
            if (map.getId().toLowerCase().startsWith(remaining)) {
                builder.suggest(map.getId());
            }
        }
        return builder.buildFuture();
    }

    /** 匹配模式补全 */
    private static CompletableFuture<Suggestions> suggestModes(CommandContext<ServerCommandSource> ctx,
                                                               SuggestionsBuilder builder) {
        String remaining = builder.getRemaining().toLowerCase();
        if ("competitive".startsWith(remaining)) builder.suggest("COMPETITIVE");
        if ("casual".startsWith(remaining)) builder.suggest("CASUAL");
        return builder.buildFuture();
    }

    /** data get/edit player 的玩家名补全：在线玩家 + 全部已知档案名（含离线） */
    private static CompletableFuture<Suggestions> suggestProfileNames(CommandContext<ServerCommandSource> ctx,
                                                                      SuggestionsBuilder builder) {
        String remaining = stripQuote(builder.getRemaining()).toLowerCase();
        LinkedHashSet<String> names = new LinkedHashSet<>();
        var server = Cstmm.getServer();
        if (server != null) {
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                names.add(p.getName().getString());
            }
        }
        for (PlayerProfile profile : PlayerDataManager.getInstance().getAllProfiles().values()) {
            if (profile.getPlayerName() != null) names.add(profile.getPlayerName());
        }
        for (String name : names) {
            if (name.toLowerCase().startsWith(remaining)) builder.suggest(name);
        }
        return builder.buildFuture();
    }

    /** data get/edit clan 的战队名补全 */
    private static CompletableFuture<Suggestions> suggestClanNames(CommandContext<ServerCommandSource> ctx,
                                                                   SuggestionsBuilder builder) {
        String remaining = stripQuote(builder.getRemaining()).toLowerCase();
        for (String name : ClanManager.getInstance().getAllClanNames()) {
            if (name.toLowerCase().startsWith(remaining)) builder.suggest(name);
        }
        return builder.buildFuture();
    }

    /** data edit player 的字段补全 */
    private static CompletableFuture<Suggestions> suggestPlayerFields(CommandContext<ServerCommandSource> ctx,
                                                                      SuggestionsBuilder builder) {
        for (String field : new String[]{"kills", "deaths", "matches", "wins", "penaltyDeaths"}) {
            if (field.toLowerCase().startsWith(builder.getRemaining().toLowerCase())) builder.suggest(field);
        }
        return builder.buildFuture();
    }

    /** data edit clan 的字段补全 */
    private static CompletableFuture<Suggestions> suggestClanFields(CommandContext<ServerCommandSource> ctx,
                                                                    SuggestionsBuilder builder) {
        for (String field : new String[]{"name", "abbr", "limit", "leader", "badge"}) {
            if (field.startsWith(builder.getRemaining().toLowerCase())) builder.suggest(field);
        }
        return builder.buildFuture();
    }

    /** data get player 的单字段补全 */
    private static CompletableFuture<Suggestions> suggestPlayerGetFields(CommandContext<ServerCommandSource> ctx,
                                                                         SuggestionsBuilder builder) {
        for (String field : new String[]{"kills", "deaths", "matches", "wins", "penaltydeaths", "kd", "name", "uuid"}) {
            if (field.startsWith(builder.getRemaining().toLowerCase())) builder.suggest(field);
        }
        return builder.buildFuture();
    }

    /** data get clan 的单字段补全 */
    private static CompletableFuture<Suggestions> suggestClanGetFields(CommandContext<ServerCommandSource> ctx,
                                                                       SuggestionsBuilder builder) {
        for (String field : new String[]{"name", "abbr", "limit", "leader", "members", "badge", "createdat"}) {
            if (field.startsWith(builder.getRemaining().toLowerCase())) builder.suggest(field);
        }
        return builder.buildFuture();
    }

    /** data delete player 的数据类型补全 */
    private static CompletableFuture<Suggestions> suggestDeleteTargets(CommandContext<ServerCommandSource> ctx,
                                                                       SuggestionsBuilder builder) {
        for (String target : new String[]{"profile", "bags", "all"}) {
            if (target.startsWith(builder.getRemaining().toLowerCase())) builder.suggest(target);
        }
        return builder.buildFuture();
    }

    /** 带引号参数（StringArgumentType.string()）补全时剥掉前导引号再匹配 */
    private static String stripQuote(String remaining) {
        return remaining.startsWith("\"") ? remaining.substring(1) : remaining;
    }

    // ==================== 对局查询/管理 ====================

    /**
     * 【作用】/cstmm match status：展示执行玩家当前对局详情（地图/队伍/阶段/比分/剩余时间），未在对局时提示其队列状态。
     * 【被谁使用】ModCommands 内部：registerMatch 挂接的命令执行体（仅玩家可用）。
     */
    private static int matchStatus(CommandContext<ServerCommandSource> context) {
        ServerPlayerEntity player = requirePlayer(context);
        if (player == null) return 0;

        UUID uuid = player.getUuid();
        MatchSession session = MatchManager.getInstance().getPlayerSession(uuid);

        if (session != null) {
            String team = session.getPlayerTeam(uuid) == 1 ? "红队" : "蓝队";
            String phase = switch (session.getPhase()) {
                case PREPARING -> "准备中 (" + session.getPrepCounter() + "s)";
                case FIGHTING -> "战斗中";
                case ENDED -> "已结束";
                default -> "空闲";
            };
            player.sendMessage(Text.literal(
                    "§6=== 当前对局 ===\n" +
                            "§e地图: §f" + session.getMapName() + "\n" +
                            "§e队伍: §f" + team + "\n" +
                            "§e阶段: §f" + phase + "\n" +
                            "§e击杀数: §c" + session.getRedKills() + " §7- §9" + session.getBlueKills() + "\n" +
                            (session.getRemainingSeconds() > 0 ? "§e剩余时间: §f" + session.getRemainingSeconds() + "秒" : "")
            ), false);
            return 1;
        }

        if (QueueManager.getInstance().isInQueue(uuid)) {
            player.sendMessage(Text.literal("§e你当前在匹配队列中，请等待匹配..."), false);
            return 1;
        }

        player.sendMessage(Text.literal("§e你当前不在任何队列或游戏中"), false);
        return 1;
    }

    /**
     * 【作用】/cstmm match list：列出全部进行中对局（地图/红蓝人数/阶段/8 位短 ID），供管理员配合 forceend 使用。
     * 【被谁使用】ModCommands 内部：registerMatch 挂接的命令执行体。
     */
    private static int matchList(CommandContext<ServerCommandSource> context) {
        List<MatchSession> sessions = MatchManager.getInstance().getAllActiveSessions();

        if (sessions.isEmpty()) {
            context.getSource().sendMessage(Text.literal("§e当前没有进行中的对局"));
            return 1;
        }

        context.getSource().sendMessage(Text.literal("§6=== 进行中的对局 ==="));
        for (MatchSession session : sessions) {
            String phase = switch (session.getPhase()) {
                case PREPARING -> "§e准备中";
                case FIGHTING -> "§a战斗中";
                case ENDED -> "§7已结束";
                default -> "§7空闲";
            };
            String line = String.format("§f%s §7| 红:§c%d §7蓝:§9%d §7| %s §7| ID: §f%s",
                    session.getMapName(),
                    session.getRedPlayers().size(),
                    session.getBluePlayers().size(),
                    phase,
                    session.getSessionId().substring(0, 8)
            );
            context.getSource().sendMessage(Text.literal(line));
        }
        return 1;
    }

    /**
     * 【作用】/cstmm match forceend <ID>（OP≥2）：按完整或前缀匹配对局 ID 强制结束指定对局（前缀命中多个时列出让管理员细化）。
     * 【被谁使用】ModCommands 内部：registerMatch 挂接的命令执行体。
     */
    private static int forceEndSession(CommandContext<ServerCommandSource> context) {
        String sessionId = StringArgumentType.getString(context, "id");
        MatchManager matchManager = MatchManager.getInstance();
        MatchSession session = matchManager.getSession(sessionId);

        // 精确查找失败时按前缀匹配（match list 仅展示前 8 位短 ID）
        if (session == null) {
            List<MatchSession> matched = new ArrayList<>();
            for (MatchSession s : matchManager.getAllActiveSessions()) {
                if (s.getSessionId().startsWith(sessionId)) {
                    matched.add(s);
                }
            }
            if (matched.size() == 1) {
                session = matched.get(0);
            } else if (matched.size() > 1) {
                context.getSource().sendMessage(Text.literal("§cID 前缀匹配到 " + matched.size() + " 个对局，请输入更长的 ID："));
                for (MatchSession s : matched) {
                    context.getSource().sendMessage(Text.literal(
                            "§7- §f" + s.getMapName() + " §7| ID: §f" + s.getSessionId().substring(0, 8)));
                }
                return 0;
            }
        }

        if (session == null) {
            context.getSource().sendMessage(Text.literal("§c未找到对局: " + sessionId));
            return 0;
        }
        if (session.getPhase() == MatchSession.GamePhase.ENDED) {
            context.getSource().sendMessage(Text.literal("§e该对局已结束"));
            return 1;
        }

        MatchManager.getInstance().endMatch(session, "§c管理员强制结束游戏！", 0);
        context.getSource().sendMessage(Text.literal("§a已强制结束对局: " + session.getSessionId().substring(0, 8)));
        return 1;
    }
}
