package cn.woshiikun_1145.mcmod.choco.cstmm.command;

import cn.woshiikun_1145.mcmod.choco.cstmm.Cstmm;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.MatchSession;
import cn.woshiikun_1145.mcmod.choco.cstmm.manager.*;
import cn.woshiikun_1145.mcmod.choco.cstmm.network.payload.OpenConfigScreenPayload;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class ModCommands {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher,
                                CommandRegistryAccess registryAccess,
                                CommandManager.RegistrationEnvironment environment) {

        var cstmm = CommandManager.literal("cstmm")
                .requires(source -> source.hasPermissionLevel(0));

        // ========== /cstmm match ==========
        var match = CommandManager.literal("match");

        // match leave
        match.then(CommandManager.literal("leave")
                .executes(ctx -> {
                    ServerPlayerEntity player = ctx.getSource().getPlayer();
                    if (player == null) {
                        ctx.getSource().sendMessage(Text.literal("§c此命令只能由玩家执行"));
                        return 0;
                    }
                    QueueManager.getInstance().leaveQueue(player);
                    return 1;
                }));

        // match status
        match.then(CommandManager.literal("status")
                .executes(ModCommands::matchStatus));

        // match status_quick
        match.then(CommandManager.literal("status_quick")
                .executes(ctx -> {
                    ServerPlayerEntity player = ctx.getSource().getPlayer();
                    if (player == null) {
                        ctx.getSource().sendMessage(Text.literal("§c此命令只能由玩家执行"));
                        return 0;
                    }
                    int quickSize = QueueManager.getInstance().getQuickQueueSize();
                    player.sendMessage(Text.literal(
                            "§6=== 快速匹配状态 ===\n" +
                                    "§e队列中人数: §f" + quickSize + "\n" +
                                    (quickSize >= 2 ? "§a已有人数，正在尝试匹配..." : "§e等待更多玩家加入...")
                    ), false);
                    return 1;
                }));

        // match list
        match.then(CommandManager.literal("list")
                .executes(ModCommands::matchList));

        // match forceend <id>（支持 match list 显示的 8 位短 ID，Tab 可补全）
        match.then(CommandManager.literal("forceend")
                .requires(source -> source.hasPermissionLevel(2))
                .then(CommandManager.argument("id", StringArgumentType.string())
                        .suggests(ModCommands::suggestSessionIds)
                        .executes(ModCommands::forceEndSession)));

        cstmm.then(match);

        // ========== /cstmm vote ==========
        var vote = CommandManager.literal("vote");

        // vote kickyes
        vote.then(CommandManager.literal("kickyes")
                .executes(ctx -> {
                    ServerPlayerEntity player = ctx.getSource().getPlayer();
                    if (player == null) {
                        ctx.getSource().sendMessage(Text.literal("§c此命令只能由玩家执行"));
                        return 0;
                    }
                    VoteManager.getInstance().handleVote(player, true);
                    return 1;
                }));

        // vote kickno
        vote.then(CommandManager.literal("kickno")
                .executes(ctx -> {
                    ServerPlayerEntity player = ctx.getSource().getPlayer();
                    if (player == null) {
                        ctx.getSource().sendMessage(Text.literal("§c此命令只能由玩家执行"));
                        return 0;
                    }
                    VoteManager.getInstance().handleVote(player, false);
                    return 1;
                }));

        // vote kick <player>
        vote.then(CommandManager.literal("kick")
                .then(CommandManager.argument("player", EntityArgumentType.player())
                        .executes(ctx -> {
                            ServerPlayerEntity player = ctx.getSource().getPlayer();
                            if (player == null) {
                                ctx.getSource().sendMessage(Text.literal("§c此命令只能由玩家执行"));
                                return 0;
                            }
                            ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
                            if (target == player) {
                                player.sendMessage(Text.literal("§c你不能投票踢出自己！"), false);
                                return 0;
                            }
                            VoteManager.getInstance().startKickVote(player, target);
                            return 1;
                        })));

        cstmm.then(vote);

        // ========== /cstmm config ==========
        cstmm.then(CommandManager.literal("config")
                .requires(source -> source.hasPermissionLevel(2))
                .executes(ctx -> {
                    ServerPlayerEntity player = ctx.getSource().getPlayer();
                    if (player == null) {
                        ctx.getSource().sendMessage(Text.literal("§c此命令只能由玩家执行"));
                        return 0;
                    }
                    ServerPlayNetworking.send(player, new OpenConfigScreenPayload());
                    player.sendMessage(Text.literal("§a正在打开配置界面..."), false);
                    return 1;
                }));

        // ========== /cstmm reload ==========
        cstmm.then(CommandManager.literal("reload")
                .requires(source -> source.hasPermissionLevel(2))
                .executes(ctx -> {
                    ConfigManager.getInstance().reload();
                    ctx.getSource().sendMessage(Text.literal("§a配置已重新加载！"));
                    return 1;
                }));

        // ========== /cstmm data restore bags ==========
        var restoreBags = CommandManager.literal("restore")
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
                                        }))));

        cstmm.then(CommandManager.literal("data")
                .requires(source -> source.hasPermissionLevel(2))
                .then(restoreBags));

        dispatcher.register(cstmm);
        Cstmm.LOGGER.info("[CSTMM - Commands] Registered commands under /cstmm");
    }

    // ==================== 命令执行方法 ====================

    private static int matchStatus(CommandContext<ServerCommandSource> context) {
        ServerPlayerEntity player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendMessage(Text.literal("§c此命令只能由玩家执行"));
            return 0;
        }

        UUID uuid = player.getUuid();
        MatchSession session = MatchManager.getInstance().getPlayerSession(uuid);

        if (session != null) {
            String team = session.getPlayerTeam(uuid) == 1 ? "红队" : (session.getPlayerTeam(uuid) == 2 ? "蓝队" : "观战");
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

    /** forceend 的 id 参数补全：列出进行中对局的 8 位短 ID */
    private static CompletableFuture<Suggestions> suggestSessionIds(CommandContext<ServerCommandSource> context,
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
}