package cn.woshiikun_1145.mcmod.choco.cstmm.command;

import cn.woshiikun_1145.mcmod.choco.cstmm.Cstmm;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.Clan;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.InventorySnapshot;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.MatchSession;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.PlayerProfile;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.config.GlobalConfig;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.config.MapConfig;
import cn.woshiikun_1145.mcmod.choco.cstmm.manager.*;
import cn.woshiikun_1145.mcmod.choco.cstmm.network.CommandHintCache;
import cn.woshiikun_1145.mcmod.choco.cstmm.network.NetworkHandler;
import cn.woshiikun_1145.mcmod.choco.cstmm.network.payload.OpenConfigScreenPayload;
import cn.woshiikun_1145.mcmod.choco.cstmm.util.BandwidthTracker;
import com.mojang.brigadier.Command;
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
import net.minecraft.item.ItemStack;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
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
 * /cstmm data     get player|clan|map|global / edit player|clan|map|global / restore bags / delete player|clan|map   （OP≥2，支持离线玩家）
 * /cstmm debug    match_info_hud &lt;t|f&gt; / bandwidth start|stop|get|view             （OP≥2 调试）
 * </pre>
 * 每个节点按 registerXxx 方法拆分注册，执行逻辑为独立静态方法，便于增删子命令。
 */
public class ModCommands {

    // data get clan 展示战队创建时间用的格式化器（服务器本地时区）
    private static final DateTimeFormatter CREATED_AT_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    // data get/edit map 支持的全部字段名（小写规范化后）
    private static final String[] MAP_FIELDS = {
            "id", "displayname", "enabled", "wincondition", "targetkills", "maxduration", "tierule",
            "boundary", "redboundary", "blueboundary", "redspawns", "bluespawns",
            "minplayers", "cooldownseconds", "minredplayers", "minblueplayers", "preparetime",
            "boundarywarningtime", "boundarypenaltykills", "kickcooldownseconds",
            "reinforcementmode", "maxredplayers", "maxblueplayers", "reinforceable",
            "dimension", "background", "shopitems"
    };

    // 默认装备合法槽位简写（EquipmentManager.applyToSlot 直接映射装备栏）
    private static final List<String> GEAR_SLOTS = List.of("head", "chest", "legs", "feet", "mainhand", "offhand");

    /**
     * 【作用】默认装备槽位的等价归一键：同一装备栏的不同写法（简写 head 与 /item 语法 armor.head、
     *         mainhand 与 weapon.mainhand）归一为同一 key，供 gear set 覆盖同槽旧配置、
     *         gear remove 精确删除跨写法条目；无别名的写法（armor.body、container.N）原样小写返回。
     *         null/空槽位（JSON 显式 null 等异常数据）返回空串，永不抛异常。
     * 【被谁使用】dataEditGlobal 的 gear set/remove 分支与 isValidGearSlot。
     */
    private static String gearSlotKey(String slot) {
        if (slot == null) return "";
        return switch (slot.toLowerCase()) {
            case "head", "armor.head" -> "head";
            case "chest", "armor.chest" -> "chest";
            case "legs", "armor.legs" -> "legs";
            case "feet", "armor.feet" -> "feet";
            case "mainhand", "weapon.mainhand" -> "mainhand";
            case "offhand", "weapon.offhand" -> "offhand";
            default -> slot.toLowerCase();
        };
    }

    /**
     * 【作用】校验默认装备槽位（与配置界面 ConfigScreenSupport.validateGearSlots 同口径）：
     *         ① 简写 head/chest/legs/feet/mainhand/offhand；② /item replace 语法
     *         armor.head/chest/legs/feet/body、weapon.mainhand/offhand 与 container.0~35
     *         （armor.body 玩家无该槽位，发放时进背包/掉落；container.N 写主背包 N 号槽）。
     * 【被谁使用】dataEditGlobal 的 gear set 分支。
     */
    private static boolean isValidGearSlot(String slot) {
        String key = gearSlotKey(slot);
        if (GEAR_SLOTS.contains(key)) return true;
        if (key.equals("armor.body")) return true;
        if (key.startsWith("container.")) {
            try {
                int idx = Integer.parseInt(key.substring("container.".length()));
                return idx >= 0 && idx <= 35;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
    }

    // data get clan 的合法单查字段
    private static final List<String> CLAN_GET_FIELDS =
            List.of("name", "abbr", "limit", "leader", "members", "badge", "createdat");
    // data edit clan 的合法字段
    private static final List<String> CLAN_EDIT_FIELDS = List.of("name", "abbr", "limit", "leader", "badge");

    /**
     * 【作用】注册 /cstmm 命令树根节点，并挂接 queue/match/vote/config/reload/data 各子命令分支。
     * 【被谁使用】Cstmm#onInitialize（以方法引用注册到 Fabric CommandRegistrationCallback，服务端命令注册阶段调用）。
     */
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher,
                                CommandRegistryAccess registryAccess,
                                CommandManager.RegistrationEnvironment environment) {

        var cstmm = CommandManager.literal("cstmm")
                .requires(source -> source.hasPermissionLevel(0))
                .executes(guard("cstmm", ModCommands::showHelp));

        registerQueue(cstmm);
        registerMatch(cstmm);
        registerVote(cstmm);
        registerConfigAndReload(cstmm);
        registerData(cstmm);
        registerDebug(cstmm);

        dispatcher.register(cstmm);
        Cstmm.LOGGER.info("[CSTMM - Commands] Registered commands under /cstmm");
    }

    // ==================== /cstmm debug（OP≥2 调试） ====================

    /**
     * 【作用】注册 /cstmm debug 调试子命令树：match_info_hud（强制显示比赛信息栏）、
     *         bandwidth（服务器带宽记录 start/stop/get/view）。
     * 【被谁使用】register（命令注册阶段调用）。仅服务端。
     */
    private static void registerDebug(LiteralArgumentBuilder<ServerCommandSource> cstmm) {
        var debug = CommandManager.literal("debug")
                .requires(source -> source.hasPermissionLevel(2))
                .executes(usage("/cstmm debug match_info_hud <t|f> | bandwidth start|stop|get|view | exception_protect_test [confirm]"));

        // ----- match_info_hud：强制显示/关闭比赛信息栏（仅作用于执行者自己的客户端） -----
        debug.then(CommandManager.literal("match_info_hud")
                .then(CommandManager.argument("enabled", StringArgumentType.word())
                        .suggests(ModCommands::suggestDebugToggle)
                        .executes(guard("debug.match_info_hud", ModCommands::debugMatchInfoHud))));

        // ----- bandwidth：服务器带宽记录 -----
        debug.then(CommandManager.literal("bandwidth")
                .executes(usage("/cstmm debug bandwidth start（开始记录）| stop（停止记录）| get（当前带宽）| view（查看记录）"))
                .then(CommandManager.literal("start").executes(guard("debug.bandwidth.start", ctx -> debugBandwidth(ctx, "start"))))
                .then(CommandManager.literal("stop").executes(guard("debug.bandwidth.stop", ctx -> debugBandwidth(ctx, "stop"))))
                .then(CommandManager.literal("get").executes(guard("debug.bandwidth.get", ctx -> debugBandwidth(ctx, "get"))))
                .then(CommandManager.literal("view").executes(guard("debug.bandwidth.view", ctx -> debugBandwidth(ctx, "view")))));

        // ----- exception_protect_test：保护模式触发测试（两步确认，验证全局异常保护链路） -----
        debug.then(CommandManager.literal("exception_protect_test")
                .executes(guard("debug.exception_protect_test", ctx -> exceptionProtectTest(ctx, false)))
                .then(CommandManager.literal("confirm")
                        .executes(guard("debug.exception_protect_test.confirm", ctx -> exceptionProtectTest(ctx, true)))));

        cstmm.then(debug);
    }

    /**
     * debug match_info_hud <t|f>：强制显示/关闭比赛信息栏（仅作用于执行者自己的客户端）。
     * 开启后仅执行命令的管理员每秒收到首个活跃对局的 HUD（服务端单目标推送）；
     * 关闭时立即向执行者发清空包复位其客户端 HUD。
     * 【被谁使用】registerDebug 的 match_info_hud 分支。
     */
    private static int debugMatchInfoHud(CommandContext<ServerCommandSource> ctx) {
        // 信息栏是客户端显示：仅支持游戏内玩家执行，只影响执行者自己的客户端
        if (!(ctx.getSource().getEntity() instanceof ServerPlayerEntity executor)) {
            ctx.getSource().sendMessage(Text.literal(
                    "§c该调试命令仅限游戏内玩家执行（信息栏为客户端显示，只作用于执行者）"));
            return 0;
        }
        String arg = StringArgumentType.getString(ctx, "enabled").trim().toLowerCase();
        Boolean enable = switch (arg) {
            case "t", "true", "1", "开" -> true;
            case "f", "false", "0", "关" -> false;
            default -> null;
        };
        if (enable == null) {
            ctx.getSource().sendMessage(Text.literal(
                    "§c用法: /cstmm debug match_info_hud <t|f>（true/false/1/0）"));
            return 0;
        }
        MatchManager.getInstance().setMatchHudForced(enable, executor.getUuid());
        if (enable) {
            ctx.getSource().sendMessage(Text.literal(
                    "§a比赛信息栏强制显示已开启：仅你的客户端会收到首个活跃对局的 HUD §7（信息栏位于屏幕顶部中央，淡入约 1 秒）"));
            ctx.getSource().sendMessage(Text.literal(
                    "§7当前无活跃对局时显示调试预览计分板（地图名\"HUD 调试预览\"，仅供验证显示链路）；用 §fcstmm debug match_info_hud f§7 关闭并复位"));
        } else {
            ctx.getSource().sendMessage(Text.literal(
                    "§e比赛信息栏强制显示已关闭（你的客户端 HUD 已复位）"));
        }
        return 1;
    }

    /**
     * debug bandwidth <start|stop|get|view>：服务器带宽记录（Netty 管线字节统计）。
     * 【被谁使用】registerDebug 的 bandwidth 分支（四个字面量子命令共用本执行器）。
     */
    private static int debugBandwidth(CommandContext<ServerCommandSource> ctx, String action) {
        switch (action) {
            case "start" -> {
                BandwidthTracker.startRecording();
                ctx.getSource().sendMessage(Text.literal("§a开始记录服务器带宽（每秒采样一次）"));
            }
            case "stop" -> {
                ctx.getSource().sendMessage(Text.literal("§6[带宽] §f" + BandwidthTracker.stopRecording()));
            }
            case "get" -> {
                ctx.getSource().sendMessage(Text.literal("§6[带宽] §f" + BandwidthTracker.getCurrentBandwidth()));
            }
            case "view" -> {
                ctx.getSource().sendMessage(Text.literal("§6[带宽] §f" + BandwidthTracker.getViewReport()));
            }
            default -> {
                return 0;
            }
        }
        return 1;
    }

    /** 保护模式测试确认的有效期（毫秒）：发起后需在该窗口内 confirm */
    private static final long PROTECT_TEST_CONFIRM_WINDOW_MS = 30_000;
    /** 保护模式测试确认截止时间戳（0 = 未发起）；单一调试开关，不区分发起者 */
    private static volatile long protectTestConfirmUntil = 0;

    /**
     * debug exception_protect_test [confirm]：保护模式触发测试（两步确认）。
     * 不带 confirm：记录确认窗口（30 秒）并发出危险操作警告；
     * 带 confirm：在窗口内则从执行器内抛出人为测试异常——由本命令的 guard 包装捕获，
     * 走与生产完全相同的保护链路（ModGuardian.engage → 控制台堆栈 → 全服广播 → 强制结算对局 → 模组停用）。
     * 保护模式已启用时本命令会被 guard 直接拦截并提示。
     * 【被谁使用】registerDebug 的 exception_protect_test 分支。
     */
    private static int exceptionProtectTest(CommandContext<ServerCommandSource> ctx, boolean confirmed) {
        if (ModGuardian.isDisabled()) {
            // 正常情况下 guard 已拦截；双保险提示
            ctx.getSource().sendMessage(Text.literal("§e保护模式已处于启用状态，无需再次触发"));
            return 0;
        }
        if (!confirmed) {
            protectTestConfirmUntil = System.currentTimeMillis() + PROTECT_TEST_CONFIRM_WINDOW_MS;
            ctx.getSource().sendMessage(Text.literal(
                    "§c危险操作：确认后将抛出测试异常触发全局保护模式——模组全部功能停用、活跃对局强制结算，直到重启服务器！"));
            ctx.getSource().sendMessage(Text.literal(
                    "§e如确认无误，请在 30 秒内执行 §f/cstmm debug exception_protect_test confirm"));
            Cstmm.LOGGER.warn("[CSTMM - ModCommands] Protection-mode test initiated, awaiting confirm within {}s",
                    PROTECT_TEST_CONFIRM_WINDOW_MS / 1000);
            return 1;
        }
        if (System.currentTimeMillis() > protectTestConfirmUntil) {
            ctx.getSource().sendMessage(Text.literal(
                    "§c确认已超时，请重新执行 /cstmm debug exception_protect_test 发起后再 confirm"));
            return 0;
        }
        protectTestConfirmUntil = 0;
        ctx.getSource().sendMessage(Text.literal("§c确认收到，正在抛出测试异常触发保护模式..."));
        throw new RuntimeException("[CSTMM 保护模式测试] 人为抛出的测试异常（exception_protect_test）");
    }

    // ==================== /cstmm queue ====================

    private static void registerQueue(LiteralArgumentBuilder<ServerCommandSource> cstmm) {
        var queue = CommandManager.literal("queue")
                .executes(usage("/cstmm queue join <地图> [模式] | quick [模式] | leave | status"));

        queue.then(CommandManager.literal("join")
                .then(CommandManager.argument("map", StringArgumentType.string())
                        .suggests(ModCommands::suggestMapIds)
                        .executes(guard("queue.join", ModCommands::queueJoin))
                        .then(CommandManager.argument("mode", StringArgumentType.word())
                                .suggests(ModCommands::suggestModes)
                                .executes(guard("queue.join", ModCommands::queueJoin)))));

        queue.then(CommandManager.literal("quick")
                .executes(guard("queue.quick", ModCommands::queueQuick))
                .then(CommandManager.argument("mode", StringArgumentType.word())
                        .suggests(ModCommands::suggestModes)
                        .executes(guard("queue.quick", ModCommands::queueQuick))));

        queue.then(CommandManager.literal("leave")
                .executes(guard("queue.leave", ModCommands::queueLeave)));

        queue.then(CommandManager.literal("status")
                .executes(guard("queue.status", ModCommands::queueStatus)));

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
                .executes(guard("match.status", ModCommands::matchStatus)));

        match.then(CommandManager.literal("list")
                .executes(guard("match.list", ModCommands::matchList)));

        // forceend <id>：支持 match list 显示的 8 位短 ID（Tab 可补全）
        match.then(CommandManager.literal("forceend")
                .requires(source -> source.hasPermissionLevel(2))
                .then(CommandManager.argument("id", StringArgumentType.string())
                        .suggests(ModCommands::suggestSessionIds)
                        .executes(guard("match.forceend", ModCommands::forceEndSession))));

        cstmm.then(match);
    }

    // ==================== /cstmm vote ====================

    private static void registerVote(LiteralArgumentBuilder<ServerCommandSource> cstmm) {
        var vote = CommandManager.literal("vote")
                .executes(usage("/cstmm vote kick <玩家> | kickyes | kickno"));

        vote.then(CommandManager.literal("kickyes")
                .executes(guard("vote.kickyes", ctx -> {
                    ServerPlayerEntity player = requirePlayer(ctx);
                    if (player == null) return 0;
                    VoteManager.getInstance().handleVote(player, true);
                    return 1;
                })));

        vote.then(CommandManager.literal("kickno")
                .executes(guard("vote.kickno", ctx -> {
                    ServerPlayerEntity player = requirePlayer(ctx);
                    if (player == null) return 0;
                    VoteManager.getInstance().handleVote(player, false);
                    return 1;
                })));

        vote.then(CommandManager.literal("kick")
                .then(CommandManager.argument("player", EntityArgumentType.player())
                        .executes(guard("vote.kick", ctx -> {
                            ServerPlayerEntity player = requirePlayer(ctx);
                            if (player == null) return 0;
                            ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
                            if (target == player) {
                                player.sendMessage(Text.literal("§c你不能投票踢出自己！"), false);
                                return 0;
                            }
                            VoteManager.getInstance().startKickVote(player, target);
                            return 1;
                        }))));

        cstmm.then(vote);
    }

    // ==================== /cstmm config | reload ====================

    private static void registerConfigAndReload(LiteralArgumentBuilder<ServerCommandSource> cstmm) {
        cstmm.then(CommandManager.literal("config")
                .requires(source -> source.hasPermissionLevel(2))
                .executes(guard("config", ctx -> {
                    ServerPlayerEntity player = requirePlayer(ctx);
                    if (player == null) return 0;
                    ServerPlayNetworking.send(player, new OpenConfigScreenPayload());
                    player.sendMessage(Text.literal("§a正在打开配置界面..."), false);
                    return 1;
                })));

        cstmm.then(CommandManager.literal("reload")
                .requires(source -> source.hasPermissionLevel(2))
                .executes(guard("reload", ctx -> {
                    ConfigManager.getInstance().reload();
                    ctx.getSource().sendMessage(Text.literal("§a配置已重新加载！"));
                    return 1;
                })));
    }

    // ==================== /cstmm data（OP≥2） ====================

    private static void registerData(LiteralArgumentBuilder<ServerCommandSource> cstmm) {
        var data = CommandManager.literal("data")
                .requires(source -> source.hasPermissionLevel(2))
                .executes(usage("/cstmm data get player|clan|map|global <名称> [字段] / edit player|clan|map|global <名称> <字段> <值> / restore bags <玩家> [槽位] / delete player|clan|map <名称> [数据类型]"));

        // ----- restore bags（保留原有能力） -----
        data.then(CommandManager.literal("restore")
                .then(CommandManager.literal("bags")
                        .then(CommandManager.argument("player", EntityArgumentType.player())
                                .executes(guard("data.restore.bags", ctx -> {
                                    ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
                                    boolean restored = InventoryManager.getInstance().restoreInventory(target);
                                    if (restored) {
                                        ctx.getSource().sendMessage(Text.literal("§a已恢复 " + target.getName() + " 的全部背包"));
                                    } else {
                                        ctx.getSource().sendMessage(Text.literal("§e该玩家没有保存的背包数据"));
                                    }
                                    return 1;
                                }))
                                .then(CommandManager.argument("slot", IntegerArgumentType.integer(0, 40))
                                        .executes(guard("data.restore.bags.slot", ctx -> {
                                            ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
                                            int slot = IntegerArgumentType.getInteger(ctx, "slot");
                                            boolean restored = InventoryManager.getInstance().restoreSlot(target, slot);
                                            if (restored) {
                                                ctx.getSource().sendMessage(Text.literal("§a已恢复 " + target.getName() + " 的槽位 " + slot));
                                            } else {
                                                ctx.getSource().sendMessage(Text.literal("§e该玩家没有保存的背包数据或槽位无效"));
                                            }
                                            return 1;
                                        }))))));

        // ----- get player / get clan / get map|global（单图/全局字段查询）/ get <配置项>（JSON 原文） -----
        data.then(CommandManager.literal("get")
                .executes(usage("/cstmm data get player <名称> [字段] | clan \"<战队名>\" [字段] | map <地图ID> [字段] | global [字段] | maps|clans（战队名必须带引号）"))
                .then(CommandManager.literal("player")
                        .then(CommandManager.argument("player", StringArgumentType.string())
                                .suggests(ModCommands::suggestProfileNames)
                                // 省略字段输出档案摘要；指定字段输出单值
                                .executes(guard("data.get.player", ModCommands::dataGetPlayer))
                                .then(CommandManager.argument("field", StringArgumentType.word())
                                        .suggests(ModCommands::suggestPlayerGetFields)
                                        .executes(guard("data.get.player", ModCommands::dataGetPlayer)))))
                // 战队名强制带引号：literal(") 由 Brigadier 消耗开引号（无引号输入解析失败），
                // QuotedNameArgumentType 读"名字+闭引号"并自带建议（候选=裸名+闭引号，Tab 一击补全合法引号名）
                .then(CommandManager.literal("clan")
                        .then(CommandManager.literal("\"")
                                .then(CommandManager.argument("clan", QuotedNameArgumentType.quotedName())
                                        // 省略字段输出战队摘要；指定字段输出单值
                                        .executes(guard("data.get.clan", ModCommands::dataGetClan))
                                        .then(CommandManager.argument("field", StringArgumentType.word())
                                                .suggests(ModCommands::suggestClanGetFields)
                                                .executes(guard("data.get.clan", ModCommands::dataGetClan))))))
                // 单张地图查询：省略字段输出全字段摘要；指定字段输出单值
                .then(CommandManager.literal("map")
                        .then(CommandManager.argument("mapId", StringArgumentType.string())
                                .suggests(ModCommands::suggestAnyMapIds)
                                .executes(guard("data.get.map", ModCommands::dataGetMap))
                                .then(CommandManager.argument("field", StringArgumentType.word())
                                        .suggests(ModCommands::suggestMapFields)
                                        .executes(guard("data.get.map", ModCommands::dataGetMap)))))
                // 全局配置单字段查询（不带字段保持原行为：输出 global.json 原文）
                .then(CommandManager.literal("global")
                        .executes(guard("data.get.global.raw", ctx -> dataGetConfigItem(ctx, "global")))
                        .then(CommandManager.argument("field", StringArgumentType.word())
                                .suggests(ModCommands::suggestGlobalFields)
                                .executes(guard("data.get.global", ModCommands::dataGetGlobal))))
                .then(CommandManager.literal("maps")
                        .executes(guard("data.get.maps", ctx -> dataGetConfigItem(ctx, "maps"))))
                .then(CommandManager.literal("clans")
                        .executes(guard("data.get.clans", ctx -> dataGetConfigItem(ctx, "clans")))));

        // ----- edit player / edit clan / edit map / edit global -----
        data.then(CommandManager.literal("edit")
                .executes(usage("/cstmm data edit player <玩家名|UUID> <字段> <值>（头像: avatar <qq|bili|clear> [账号ID]，改名: name <新名字>） | clan \"<战队名>\" <字段> <值> | map <地图ID> <字段> <值> | global <字段> <值>（战队名必须带引号）"))
                .then(CommandManager.literal("player")
                        .then(CommandManager.argument("player", StringArgumentType.string())
                                .suggests(ModCommands::suggestProfileNames)
                                .then(CommandManager.argument("field", StringArgumentType.word())
                                        .suggests(ModCommands::suggestPlayerFields)
                                        .then(CommandManager.argument("value", IntegerArgumentType.integer(0))
                                                .executes(guard("data.edit.player", ModCommands::dataEditPlayer))))
                                // 头像编辑分支：/cstmm data edit player <玩家> avatar <qq|bili|clear> [账号ID]
                                //（Brigadier 字面量优先于同名字段参数，"avatar" 会进入本分支而非 field）
                                .then(CommandManager.literal("avatar")
                                        .then(CommandManager.argument("type", StringArgumentType.word())
                                                .suggests(ModCommands::suggestAvatarTypes)
                                                .executes(guard("data.edit.player.avatar", ModCommands::dataEditPlayerAvatar))
                                                .then(CommandManager.argument("id", StringArgumentType.string())
                                                        .executes(guard("data.edit.player.avatar", ModCommands::dataEditPlayerAvatar)))))
                                // 改名分支：/cstmm data edit player <玩家> name <新名字>
                                .then(CommandManager.literal("name")
                                        .then(CommandManager.argument("newName", StringArgumentType.greedyString())
                                                .executes(guard("data.edit.player.name", ModCommands::dataEditPlayerName))))))
                // 战队名强制带引号（同 get）：字段 word、值 greedy 均为独立参数
                .then(CommandManager.literal("clan")
                        .then(CommandManager.literal("\"")
                                .then(CommandManager.argument("clan", QuotedNameArgumentType.quotedName())
                                        .then(CommandManager.argument("field", StringArgumentType.word())
                                                .suggests(ModCommands::suggestClanFields)
                                                .then(CommandManager.argument("value", StringArgumentType.greedyString())
                                                        .suggests(ModCommands::suggestClanValue)
                                                        .executes(guard("data.edit.clan", ModCommands::dataEditClan)))))))
                // 地图配置命令侧编辑：全部字段走 <字段> <值(greedy)>，列表用 add/remove/clear 子操作
                .then(CommandManager.literal("map")
                        .then(CommandManager.argument("mapId", StringArgumentType.string())
                                .suggests(ModCommands::suggestAnyMapIds)
                                .then(CommandManager.argument("field", StringArgumentType.word())
                                        .suggests(ModCommands::suggestMapFields)
                                        .then(CommandManager.argument("value", StringArgumentType.greedyString())
                                                .executes(guard("data.edit.map", ModCommands::dataEditMap))))))
                // 全局配置命令侧编辑：quicktimeout / gear set|remove|clear
                .then(CommandManager.literal("global")
                        .then(CommandManager.argument("field", StringArgumentType.word())
                                .suggests(ModCommands::suggestGlobalFields)
                                .then(CommandManager.argument("value", StringArgumentType.greedyString())
                                        .executes(guard("data.edit.global", ModCommands::dataEditGlobal))))));

        // ----- delete player / delete clan / delete map（管理员删除数据） -----
        data.then(CommandManager.literal("delete")
                .executes(usage("/cstmm data delete player <玩家名|UUID> [profile|bags|avatar|all] | clan \"<战队名>\" | map <地图ID>（对局占用时拒绝；战队名必须带引号）"))
                .then(CommandManager.literal("player")
                        .then(CommandManager.argument("player", StringArgumentType.string())
                                .suggests(ModCommands::suggestProfileNames)
                                // 省略数据类型默认 all（战绩档案 + 背包快照）
                                .executes(guard("data.delete.player", ModCommands::dataDeletePlayer))
                                .then(CommandManager.argument("target", StringArgumentType.word())
                                        .suggests(ModCommands::suggestDeleteTargets)
                                        .executes(guard("data.delete.player", ModCommands::dataDeletePlayer)))))
                // 战队名强制带引号（同 get/edit）
                .then(CommandManager.literal("clan")
                        .then(CommandManager.literal("\"")
                                .then(CommandManager.argument("clan", QuotedNameArgumentType.quotedName())
                                        .executes(guard("data.delete.clan", ModCommands::dataDeleteClan)))))
                // 删除地图：正在对局中使用的地图拒绝删除（防对局僵死）
                .then(CommandManager.literal("map")
                        .then(CommandManager.argument("mapId", StringArgumentType.string())
                                .suggests(ModCommands::suggestAnyMapIds)
                                .executes(guard("data.delete.map", ModCommands::dataDeleteMap)))));

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
                case "avatar" -> profile.getAvatarType().isEmpty()
                        ? "未绑定" : profile.getAvatarType() + " " + profile.getAvatarId();
                // bags 不属于档案字段：读 InventoryManager 的已保存快照概要
                case "bags" -> bagSummary(profile.getPlayerUuid());
                default -> null;
            };
            if (value == null) {
                ctx.getSource().sendMessage(Text.literal(
                        "§c未知字段: " + field + "（可选: kills deaths matches wins penaltydeaths kd name uuid avatar bags）"));
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
                        "§e惩罚死亡: §f" + profile.getPenaltyDeaths() + "\n" +
                        "§e头像绑定: §f" + (profile.getAvatarType().isEmpty()
                                ? "未绑定" : profile.getAvatarType() + " " + profile.getAvatarId())));
        return 1;
    }

    /** data get clan：查看战队信息（成员/上限/徽标/创建时间；可选字段输出单值）。
     *  战队名含空格必须加引号（string() 参数由 Brigadier 原生读引号、剥引号、处理转义）；
     *  名字与字段是独立参数，无切分歧义。 */
    private static int dataGetClan(CommandContext<ServerCommandSource> ctx) {
        String name = StringArgumentType.getString(ctx, "clan");
        String field = optionalField(ctx);
        Clan clan = ClanManager.getInstance().getClan(name);
        if (clan == null) {
            ctx.getSource().sendMessage(Text.literal("§c未找到战队: " + name
                    + " §7（名字含空格需用引号包裹，如 §f\"Team Kun\"§7）"));
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

    /**
     * data edit player ... avatar：管理员设置/清除玩家头像绑定（支持离线玩家，改后写盘并同步在线客户端）。
     * 用法：avatar qq <QQ号> / avatar bili <UID> / avatar clear（清除）；
     * 校验复用 PlayerDataManager.setAvatarBinding（平台限 qq/bili、账号 ID 纯数字），
     * 与客户端个性化页提交同一规则；成功后经 NetworkHandler.syncAvatarBinding 同步三处展示点。
     * 【被谁使用】registerData 的 edit player avatar 分支（type 节点带/不带 id 两个 execute 路径）。
     */
    private static int dataEditPlayerAvatar(CommandContext<ServerCommandSource> ctx) {
        String input = StringArgumentType.getString(ctx, "player");
        String type = StringArgumentType.getString(ctx, "type").trim().toLowerCase();
        // avatar clear 在 type 节点即执行（无 id 参数）；qq/bili 绑定路径才读取 id
        String id = "";
        try {
            id = StringArgumentType.getString(ctx, "id").trim();
        } catch (IllegalArgumentException ignored) {
            // clear 路径没有 id 参数
        }
        PlayerProfile profile = PlayerDataManager.getInstance().findProfile(input);
        if (profile == null) {
            ctx.getSource().sendMessage(Text.literal(
                    "§e未找到玩家档案: §f" + input + " §7（离线玩家需曾进入服务器）"));
            return 0;
        }
        // clear = 清除头像绑定（服务端存空串）；qq/bili 缺账号 ID 时给出用法提示
        if (type.equals("clear")) {
            type = "";
            id = "";
        } else if (id.isEmpty()) {
            ctx.getSource().sendMessage(Text.literal(
                    "§c用法: /cstmm data edit player <玩家> avatar <qq|bili> <账号ID> §7或 §favatar clear"));
            return 0;
        }
        String err = PlayerDataManager.getInstance().setAvatarBinding(profile.getPlayerUuid(), type, id);
        if (err != null) {
            ctx.getSource().sendMessage(Text.literal(err));
            return 0;
        }
        // 在线玩家同步三处展示点（档案/战队成员列表/队列页）；离线仅落盘，进服自动读取
        NetworkHandler.syncAvatarBinding(profile.getPlayerUuid());
        ctx.getSource().sendMessage(Text.literal(
                "§a已更新 §f" + profile.getPlayerName() + " §a的头像绑定: §f"
                        + (type.isEmpty() ? "已清除" : type + " " + id + " §7（在线玩家已同步）")));
        return 1;
    }

    /** data edit clan：修改战队字段（name/abbr/limit/leader），校验与索引重建复用 ClanManager。
     *  战队名含空格必须加引号（string() 参数由 Brigadier 原生处理）；字段/值为独立参数。 */
    private static int dataEditClan(CommandContext<ServerCommandSource> ctx) {
        String name = StringArgumentType.getString(ctx, "clan");
        String field = StringArgumentType.getString(ctx, "field").toLowerCase();
        String value = StringArgumentType.getString(ctx, "value").trim();
        ClanManager clans = ClanManager.getInstance();
        Clan clan = clans.getClan(name);
        if (clan == null) {
            ctx.getSource().sendMessage(Text.literal("§c未找到战队: " + name
                    + " §7（名字含空格需用引号包裹，如 §f\"Team Kun\"§7）"));
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
     * data edit player ... name：管理员修改玩家档案的显示名（支持离线玩家，改后写盘并同步在线客户端）。
     * 仅影响档案展示名（履历/查询），不改玩家本体 UUID。
     * 【被谁使用】registerData 的 edit player name 分支。
     */
    private static int dataEditPlayerName(CommandContext<ServerCommandSource> ctx) {
        String input = StringArgumentType.getString(ctx, "player");
        String newName = StringArgumentType.getString(ctx, "newName").trim();
        PlayerProfile profile = PlayerDataManager.getInstance().findProfile(input);
        if (profile == null) {
            ctx.getSource().sendMessage(Text.literal(
                    "§e未找到玩家档案: §f" + input + " §7（离线玩家需曾进入服务器）"));
            return 0;
        }
        if (newName.isEmpty() || newName.length() > 64) {
            ctx.getSource().sendMessage(Text.literal("§c名字不能为空且不超过 64 字符"));
            return 0;
        }
        String oldName = profile.getPlayerName();
        profile.setPlayerName(newName);
        PlayerDataManager.getInstance().persistAndSync(profile.getPlayerUuid());
        ctx.getSource().sendMessage(Text.literal(
                "§a已更新玩家档案名: §f" + oldName + " §7→ §f" + newName));
        return 1;
    }

    /** data get player bags 的快照概要：保存时间 + 非空物品件数 */
    private static String bagSummary(UUID uuid) {
        InventorySnapshot snap = InventoryManager.getInstance().getSnapshot(uuid);
        if (snap == null) return "无快照";
        int count = 0;
        // 统计主背包 + 盔甲 + 副手中的非空物品
        for (ItemStack st : snap.getMainInventory()) if (st != null && !st.isEmpty()) count++;
        for (ItemStack st : snap.getArmor()) if (st != null && !st.isEmpty()) count++;
        if (!snap.getOffhand().isEmpty()) count++;
        return "有快照（" + CREATED_AT_FORMAT.format(Instant.ofEpochMilli(snap.getSavedAt())) + " 保存，非空物品 " + count + " 件）";
    }

    // ==================== data get/edit/delete map（地图配置命令侧管理） ====================

    /**
     * data get map：查看单张地图配置（省略字段输出全字段摘要，指定字段输出单值）。
     * 【被谁使用】registerData 的 get map 分支。
     */
    private static int dataGetMap(CommandContext<ServerCommandSource> ctx) {
        String mapId = StringArgumentType.getString(ctx, "mapId");
        String field = optionalField(ctx);
        MapConfig map = ConfigManager.getInstance().getMap(mapId);
        if (map == null) {
            ctx.getSource().sendMessage(Text.literal(
                    "§e未找到地图: §f" + mapId + " §7（用 /cstmm data get maps 查看 ID 列表）"));
            return 0;
        }
        if (field != null) {
            String f = normalizeMapField(field);
            String value = mapFieldSingleValue(map, f);
            if (value == null) {
                ctx.getSource().sendMessage(Text.literal(
                        "§c未知字段: " + field + "（可选: " + String.join(" ", MAP_FIELDS) + "）"));
                return 0;
            }
            ctx.getSource().sendMessage(Text.literal("§6[地图配置." + f + "] §f" + value));
            return 1;
        }
        // 全字段摘要
        ctx.getSource().sendMessage(Text.literal(
                "§6=== 地图配置: " + map.getId() + "（" + map.getDisplayName() + "）===\n" +
                "§e启用: §f" + map.isEnabled()
                        + " §e| 胜负: §f" + map.getWinCondition().name()
                        + " §7(目标 " + map.getTargetKills() + " 杀)"
                        + " §e| 时长: §f" + map.getMaxDuration() + " 秒"
                        + " §e| 平局: §f" + map.getTieRule().name() + "\n" +
                "§e最低人数 红/蓝: §f" + map.getMinRedPlayers() + "/" + map.getMinBluePlayers()
                        + " §e| 上限 红/蓝: §f" + (map.getMaxRedPlayers() == 0 ? "无上限" : map.getMaxRedPlayers())
                        + "/" + (map.getMaxBluePlayers() == 0 ? "无上限" : map.getMaxBluePlayers()) + "\n" +
                "§e补位: §f" + map.getReinforcementMode() + (map.isReinforceable() ? "（允许补位）" : "（不允许补位）")
                        + " §e| 准备: §f" + map.getPrepareTime() + " 秒"
                        + " §e| 冷却: §f" + map.getCooldownSeconds() + " 秒"
                        + " §e| 踢人冷却: §f" + map.getKickCooldownSeconds() + " 秒\n" +
                "§e边界警告: §f" + map.getBoundaryWarningTime() + " 秒"
                        + " §e| 惩罚击杀: §f" + map.getBoundaryPenaltyKills()
                        + " §e| 维度: §f" + map.getDimension() + "\n" +
                "§e公共边界: §f" + boundaryText(map.getBoundary()) + "\n" +
                "§e红队边界: §f" + boundaryText(map.getRedBoundary())
                        + " §e| 蓝队边界: §f" + boundaryText(map.getBlueBoundary()) + "\n" +
                "§e红队出生点: §f" + spawnListText(map.getRedSpawns()) + "\n" +
                "§e蓝队出生点: §f" + spawnListText(map.getBlueSpawns()) + "\n" +
                "§e商店商品: §f" + shopListText(map.getShopItems()) + "\n" +
                "§e背景图: §f" + (map.getBackgroundBase64().isEmpty()
                        ? "无" : "base64 共 " + map.getBackgroundBase64().length() + " 字符")));
        return 1;
    }

    /**
     * data edit map：命令侧修改地图配置的任意字段（改后写盘并广播全服）。
     * 标量/枚举/布尔直接赋值；边界 6 整数或 clear；出生点/商店用 add/remove/clear 子操作。
     * 【被谁使用】registerData 的 edit map 分支。
     */
    private static int dataEditMap(CommandContext<ServerCommandSource> ctx) {
        String mapId = StringArgumentType.getString(ctx, "mapId");
        String field = normalizeMapField(StringArgumentType.getString(ctx, "field").toLowerCase());
        String value = StringArgumentType.getString(ctx, "value").trim();
        MapConfig map = ConfigManager.getInstance().getMap(mapId);
        if (map == null) {
            ctx.getSource().sendMessage(Text.literal(
                    "§e未找到地图: §f" + mapId + " §7（用 /cstmm data get maps 查看 ID 列表）"));
            return 0;
        }
        String err = applyMapFieldEdit(map, field, value);
        if (err != null) {
            ctx.getSource().sendMessage(Text.literal(err));
            return 0;
        }
        // updateMap 内部校验出生点非空、按 id 替换、写盘并递增配置版本
        ConfigManager.getInstance().updateMap(map);
        // 广播全服：在线客户端立即拿到新配置（无需重连）
        NetworkHandler.broadcastConfigUpdate();
        ctx.getSource().sendMessage(Text.literal(
                "§a已更新地图 §f" + map.getId() + " §a的 §f" + field + " §a: §f" + mapFieldSingleValue(map, field)));
        return 1;
    }

    /**
     * data delete map：删除地图配置（正在对局中使用的地图拒绝删除，防对局僵死；删后写盘并广播全服）。
     * 【被谁使用】registerData 的 delete map 分支。
     */
    private static int dataDeleteMap(CommandContext<ServerCommandSource> ctx) {
        String mapId = StringArgumentType.getString(ctx, "mapId");
        ConfigManager configManager = ConfigManager.getInstance();
        MapConfig map = configManager.getMap(mapId);
        if (map == null) {
            ctx.getSource().sendMessage(Text.literal(
                    "§e未找到地图: §f" + mapId + " §7（用 /cstmm data get maps 查看 ID 列表）"));
            return 0;
        }
        // 与配置界面保存同一保护：非 ENDED 对局占用的地图禁止删除
        boolean inUse = MatchManager.getInstance().getAllActiveSessions().stream()
                .anyMatch(s -> s.getMapName().equals(map.getId())
                        && s.getPhase() != MatchSession.GamePhase.ENDED);
        if (inUse) {
            ctx.getSource().sendMessage(Text.literal(
                    "§c地图 \"" + map.getId() + "\" 正在对局中使用，禁止删除！"));
            return 0;
        }
        configManager.removeMap(map.getId());
        NetworkHandler.broadcastConfigUpdate();
        ctx.getSource().sendMessage(Text.literal("§a已删除地图: §f" + map.getId()));
        return 1;
    }

    // ==================== data get/edit global（全局配置命令侧管理） ====================

    /**
     * data get global <字段>：查看全局配置单字段（quicktimeout / gear）。
     * 不带字段时保持原行为（registerData 中直接输出 global.json 原文）。
     * 【被谁使用】registerData 的 get global <字段> 分支。
     */
    private static int dataGetGlobal(CommandContext<ServerCommandSource> ctx) {
        String field = normalizeGlobalField(optionalField(ctx));
        GlobalConfig global = ConfigManager.getInstance().getGlobalConfig();
        String value = switch (field) {
            case "quicktimeout" -> global.getQuickTimeout() + " 秒";
            case "gear" -> gearListText(global);
            default -> null;
        };
        if (value == null) {
            ctx.getSource().sendMessage(Text.literal(
                    "§c未知字段: " + field + "（可选: quicktimeout gear）"));
            return 0;
        }
        ctx.getSource().sendMessage(Text.literal("§6[全局配置." + field + "] §f" + value));
        return 1;
    }

    /**
     * data edit global：命令侧修改全局配置（quicktimeout 直接赋值；gear 用 set/remove/clear 子操作；
     * 改后写盘并广播全服）。
     * 【被谁使用】registerData 的 edit global 分支。
     */
    private static int dataEditGlobal(CommandContext<ServerCommandSource> ctx) {
        String field = normalizeGlobalField(StringArgumentType.getString(ctx, "field").toLowerCase());
        String value = StringArgumentType.getString(ctx, "value").trim();
        // getGlobalConfig 返回共享实例：命令在服务器主线程修改后立即经 updateGlobalConfig 落盘
        GlobalConfig global = ConfigManager.getInstance().getGlobalConfig();
        String[] t = value.isEmpty() ? new String[0] : value.split("\\s+");
        switch (field) {
            case "quicktimeout" -> {
                Integer v = t.length == 1 ? parseIntOrNull(t[0]) : null;
                if (v == null || v < 1) {
                    ctx.getSource().sendMessage(Text.literal("§c快速匹配超时必须是不小于 1 的整数（秒）"));
                    return 0;
                }
                global.setQuickTimeout(v);
            }
            case "gear" -> {
                if (t.length == 0) {
                    ctx.getSource().sendMessage(Text.literal(
                            "§c用法: gear set <槽位> <物品ID> / gear remove <槽位> / gear clear §7（槽位: armor.head/chest/legs/feet/body、container.0~35 或简写 "
                                    + String.join(" ", GEAR_SLOTS) + "）"));
                    return 0;
                }
                switch (t[0].toLowerCase()) {
                    case "set" -> {
                        if (t.length < 3) {
                            ctx.getSource().sendMessage(Text.literal(
                                    "§c用法: gear set <槽位> <物品ID> §7（物品ID 可含数量后缀或为 SNBT）"));
                            return 0;
                        }
                        String slot = t[1].toLowerCase();
                        if (!isValidGearSlot(slot)) {
                            ctx.getSource().sendMessage(Text.literal(
                                    "§c无效槽位: " + t[1] + " §7（可用: armor.head/chest/legs/feet/body、weapon.mainhand/offhand、container.0~35 或简写 "
                                            + String.join(" ", GEAR_SLOTS) + "）"));
                            return 0;
                        }
                        // 物品ID/SNBT 可能含空格，槽位之后全部合并为物品串；
                        // 按等价归一键覆盖同槽旧配置（不同写法指向同一装备栏时互相覆盖，不产生重复条目）
                        String itemId = String.join(" ", Arrays.copyOfRange(t, 2, t.length));
                        String slotKey = gearSlotKey(slot);
                        global.getDefaultGear().removeIf(s -> gearSlotKey(s.getSlot()).equals(slotKey));
                        global.getDefaultGear().add(new GlobalConfig.EquipSlot(slot, itemId));
                    }
                    case "remove" -> {
                        if (t.length != 2) {
                            ctx.getSource().sendMessage(Text.literal("§c用法: gear remove <槽位>"));
                            return 0;
                        }
                        String slot = t[1].toLowerCase();
                        String slotKey = gearSlotKey(slot);
                        boolean removed = global.getDefaultGear().removeIf(s -> gearSlotKey(s.getSlot()).equals(slotKey));
                        if (!removed) {
                            ctx.getSource().sendMessage(Text.literal("§c该槽位未配置默认装备"));
                            return 0;
                        }
                    }
                    case "clear" -> global.getDefaultGear().clear();
                    default -> {
                        ctx.getSource().sendMessage(Text.literal(
                                "§c未知子操作: " + t[0] + "（可用: set remove clear）"));
                        return 0;
                    }
                }
            }
            default -> {
                ctx.getSource().sendMessage(Text.literal(
                        "§c未知字段: " + field + "（可选: quicktimeout gear）"));
                return 0;
            }
        }
        ConfigManager.getInstance().updateGlobalConfig(global);
        NetworkHandler.broadcastConfigUpdate();
        String shown = field.equals("quicktimeout")
                ? global.getQuickTimeout() + " 秒" : gearListText(global);
        ctx.getSource().sendMessage(Text.literal("§a已更新全局配置 §f" + field + " §a: §f" + shown));
        return 1;
    }

    /** 字段名规范化：地图字段的常见别名统一到 MAP_FIELDS 中的规范名 */
    private static String normalizeMapField(String field) {
        return switch (field) {
            case "name" -> "displayname";
            case "backgroundbase64" -> "background";
            case "redspawn" -> "redspawns";
            case "bluespawn" -> "bluespawns";
            case "shop" -> "shopitems";
            default -> field;
        };
    }

    /** 字段名规范化：全局字段别名统一 */
    private static String normalizeGlobalField(String field) {
        if (field == null) return null;
        return switch (field) {
            case "defaultgear" -> "gear";
            default -> field;
        };
    }

    /** 边界的展示文本：未配置（全 0）时给出提示 */
    private static String boundaryText(MapConfig.Boundary b) {
        if (b == null || !b.isConfigured()) return "未配置（全 0）";
        return "min(" + b.getMinX() + ", " + b.getMinY() + ", " + b.getMinZ() + ")"
                + " max(" + b.getMaxX() + ", " + b.getMaxY() + ", " + b.getMaxZ() + ")";
    }

    /** 出生点列表展示文本 */
    private static String spawnListText(List<BlockPos> spawns) {
        if (spawns == null || spawns.isEmpty()) return "空（保存校验要求至少 1 个！）";
        StringBuilder sb = new StringBuilder(spawns.size() + " 个: ");
        for (int i = 0; i < spawns.size(); i++) {
            BlockPos p = spawns.get(i);
            if (i > 0) sb.append("、");
            sb.append("(").append(p.getX()).append(",").append(p.getY()).append(",").append(p.getZ()).append(")");
        }
        return sb.toString();
    }

    /** 商店商品列表展示文本 */
    private static String shopListText(List<GlobalConfig.ShopItem> items) {
        if (items == null || items.isEmpty()) return "空";
        StringBuilder sb = new StringBuilder(items.size() + " 项: ");
        for (int i = 0; i < items.size(); i++) {
            GlobalConfig.ShopItem it = items.get(i);
            if (i > 0) sb.append("、");
            sb.append(it.getItemId())
              .append("（价格 ").append(it.getPrice())
              .append("，限购 ").append(it.getMaxPurchase() == 0 ? "无限" : it.getMaxPurchase()).append("）");
        }
        return sb.toString();
    }

    /** 默认装备列表展示文本 */
    private static String gearListText(GlobalConfig global) {
        List<GlobalConfig.EquipSlot> gear = global.getDefaultGear();
        if (gear == null || gear.isEmpty()) return "空";
        StringBuilder sb = new StringBuilder(gear.size() + " 项: ");
        for (int i = 0; i < gear.size(); i++) {
            if (i > 0) sb.append("、");
            sb.append(gear.get(i).getSlot()).append("=").append(gear.get(i).getItemId());
        }
        return sb.toString();
    }

    /** 地图字段的单值展示文本（未知字段返回 null） */
    private static String mapFieldSingleValue(MapConfig map, String field) {
        return switch (field) {
            case "id" -> map.getId();
            case "displayname" -> map.getDisplayName();
            case "enabled" -> String.valueOf(map.isEnabled());
            case "wincondition" -> map.getWinCondition().name();
            case "targetkills" -> String.valueOf(map.getTargetKills());
            case "maxduration" -> String.valueOf(map.getMaxDuration());
            case "tierule" -> map.getTieRule().name();
            case "boundary" -> boundaryText(map.getBoundary());
            case "redboundary" -> boundaryText(map.getRedBoundary());
            case "blueboundary" -> boundaryText(map.getBlueBoundary());
            case "redspawns" -> spawnListText(map.getRedSpawns());
            case "bluespawns" -> spawnListText(map.getBlueSpawns());
            case "minplayers" -> String.valueOf(map.getMinPlayers());
            case "cooldownseconds" -> String.valueOf(map.getCooldownSeconds());
            case "minredplayers" -> String.valueOf(map.getMinRedPlayers());
            case "minblueplayers" -> String.valueOf(map.getMinBluePlayers());
            case "preparetime" -> String.valueOf(map.getPrepareTime());
            case "boundarywarningtime" -> String.valueOf(map.getBoundaryWarningTime());
            case "boundarypenaltykills" -> String.valueOf(map.getBoundaryPenaltyKills());
            case "kickcooldownseconds" -> String.valueOf(map.getKickCooldownSeconds());
            case "reinforcementmode" -> map.getReinforcementMode();
            case "maxredplayers" -> String.valueOf(map.getMaxRedPlayers());
            case "maxblueplayers" -> String.valueOf(map.getMaxBluePlayers());
            case "reinforceable" -> String.valueOf(map.isReinforceable());
            case "dimension" -> map.getDimension();
            case "background" -> map.getBackgroundBase64().isEmpty()
                    ? "无" : "base64 共 " + map.getBackgroundBase64().length() + " 字符";
            case "shopitems" -> shopListText(map.getShopItems());
            default -> null;
        };
    }

    /**
     * 【作用】把命令值应用到地图字段上（含解析与校验），非法输入返回可直接发给玩家的错误消息。
     * 【被谁使用】dataEditMap（应用成功后由调用方统一写盘并广播）。
     */
    private static String applyMapFieldEdit(MapConfig map, String field, String value) {
        String[] t = value.isEmpty() ? new String[0] : value.split("\\s+");
        switch (field) {
            case "id" -> {
                if (t.length != 1 || t[0].isBlank()) return "§c用法: id <新地图ID>（不含空格）";
                String newId = t[0];
                if (!newId.equals(map.getId())) {
                    // 与删除同一保护：正在对局中使用的地图禁止改名——会话持有的旧 ID 改名后将查不到
                    // 配置（tickFighting 因 config == null 跳过），胜负判定/倒计时/边界检查全部停摆
                    boolean inUse = MatchManager.getInstance().getAllActiveSessions().stream()
                            .anyMatch(s -> s.getMapName().equals(map.getId())
                                    && s.getPhase() != MatchSession.GamePhase.ENDED);
                    if (inUse) return "§c地图 \"" + map.getId() + "\" 正在对局中使用，禁止改名！";
                    boolean dup = ConfigManager.getInstance().getMaps().stream()
                            .anyMatch(m -> m != map && m.getId().equals(newId));
                    if (dup) return "§c地图 ID 已存在: " + newId;
                    map.setId(newId);
                }
            }
            case "displayname" -> {
                if (value.isEmpty()) return "§c用法: displayname <显示名>";
                map.setDisplayName(value);
            }
            case "enabled" -> {
                Boolean b = parseBoolOrNull(value);
                if (b == null) return "§c用法: enabled <true|false>";
                map.setEnabled(b);
            }
            case "reinforceable" -> {
                Boolean b = parseBoolOrNull(value);
                if (b == null) return "§c用法: reinforceable <true|false>";
                map.setReinforceable(b);
            }
            case "wincondition" -> {
                if (t.length != 1) return "§c用法: wincondition <KILLS|TIMER>";
                if (t[0].equalsIgnoreCase("KILLS")) map.setWinCondition(MapConfig.WinCondition.KILLS);
                else if (t[0].equalsIgnoreCase("TIMER")) map.setWinCondition(MapConfig.WinCondition.TIMER);
                else return "§c无效值: " + t[0] + "（可选: KILLS TIMER）";
            }
            case "tierule" -> {
                if (t.length != 1) return "§c用法: tierule <OVERTIME|DRAW>";
                if (t[0].equalsIgnoreCase("OVERTIME")) map.setTieRule(MapConfig.TieRule.OVERTIME);
                else if (t[0].equalsIgnoreCase("DRAW")) map.setTieRule(MapConfig.TieRule.DRAW);
                else return "§c无效值: " + t[0] + "（可选: OVERTIME DRAW）";
            }
            case "reinforcementmode" -> {
                if (t.length != 1) return "§c用法: reinforcementmode <CONDITIONAL|ALWAYS>";
                if (t[0].equalsIgnoreCase("CONDITIONAL")) map.setReinforcementMode("CONDITIONAL");
                else if (t[0].equalsIgnoreCase("ALWAYS")) map.setReinforcementMode("ALWAYS");
                else return "§c无效值: " + t[0] + "（可选: CONDITIONAL ALWAYS）";
            }
            case "boundary", "redboundary", "blueboundary" -> {
                MapConfig.Boundary b = switch (field) {
                    case "boundary" -> map.getBoundary();
                    case "redboundary" -> map.getRedBoundary();
                    default -> map.getBlueBoundary();
                };
                // JSON 显式 null 时兜底重建，避免对 null 边界取 setter NPE
                if (b == null) {
                    b = new MapConfig.Boundary();
                    if (field.equals("boundary")) map.setBoundary(b);
                    else if (field.equals("redboundary")) map.setRedBoundary(b);
                    else map.setBlueBoundary(b);
                }
                return applyBoundaryEdit(b, t);
            }
            case "redspawns", "bluespawns" -> {
                List<BlockPos> spawns = field.equals("redspawns") ? map.getRedSpawns() : map.getBlueSpawns();
                if (spawns == null) {
                    spawns = new ArrayList<>();
                    if (field.equals("redspawns")) map.setRedSpawns(spawns);
                    else map.setBlueSpawns(spawns);
                }
                if (t.length == 0) return "§c用法: add <x> <y> <z> / remove <序号> / clear";
                switch (t[0].toLowerCase()) {
                    case "add" -> {
                        if (t.length != 4) return "§c用法: add <x> <y> <z>";
                        Integer x = parseIntOrNull(t[1]);
                        Integer y = parseIntOrNull(t[2]);
                        Integer z = parseIntOrNull(t[3]);
                        if (x == null || y == null || z == null) return "§c坐标必须都是整数";
                        spawns.add(new BlockPos(x, y, z));
                    }
                    case "remove" -> {
                        if (t.length != 2) return "§c用法: remove <序号（从 0 开始）>";
                        Integer idx = parseIntOrNull(t[1]);
                        if (idx == null || idx < 0 || idx >= spawns.size())
                            return "§c序号无效（当前共 " + spawns.size() + " 个出生点）";
                        if (spawns.size() <= 1) return "§c出生点至少保留 1 个（保存校验要求红蓝队出生点非空）";
                        spawns.remove((int) idx);
                    }
                    case "clear" -> {
                        return "§c出生点不可全部清空（保存校验要求红蓝队出生点非空），请用 remove 逐个删除";
                    }
                    default -> {
                        return "§c未知子操作: " + t[0] + "（可用: add remove clear）";
                    }
                }
            }
            case "shopitems" -> {
                List<GlobalConfig.ShopItem> items = map.getShopItems();
                if (t.length == 0) return "§c用法: add <物品ID> [价格] [限购] / remove <序号> / clear";
                switch (t[0].toLowerCase()) {
                    case "add" -> {
                        if (t.length < 2) return "§c用法: add <物品ID> [价格] [限购次数]";
                        // 末尾两个纯数字 token 解析为价格与限购，其余合并为物品ID
                        //（兼容 "minecraft:cooked_beef 64" 数量后缀：此时需同时给价格与限购以消歧）
                        Integer price = 0;
                        Integer maxP = 0;
                        int itemEnd = t.length;
                        if (t.length >= 4) {
                            Integer p = parseIntOrNull(t[t.length - 2]);
                            Integer m = parseIntOrNull(t[t.length - 1]);
                            if (p != null && m != null) {
                                price = p;
                                maxP = m;
                                itemEnd -= 2;
                            }
                        }
                        if (itemEnd == t.length && t.length >= 3) {
                            Integer p = parseIntOrNull(t[t.length - 1]);
                            if (p != null) {
                                price = p;
                                itemEnd -= 1;
                            }
                        }
                        if (price < 0 || maxP < 0) return "§c价格与限购次数不能为负数";
                        String itemId = String.join(" ", Arrays.copyOfRange(t, 1, itemEnd));
                        if (itemId.isBlank()) return "§c物品ID不能为空";
                        items.add(new GlobalConfig.ShopItem(itemId, price, maxP));
                    }
                    case "remove" -> {
                        if (t.length != 2) return "§c用法: remove <序号（从 0 开始）>";
                        Integer idx = parseIntOrNull(t[1]);
                        if (idx == null || idx < 0 || idx >= items.size())
                            return "§c序号无效（当前共 " + items.size() + " 项商品）";
                        items.remove((int) idx);
                    }
                    case "clear" -> items.clear();
                    default -> {
                        return "§c未知子操作: " + t[0] + "（可用: add remove clear）";
                    }
                }
            }
            case "dimension" -> {
                if (t.length != 1 || t[0].isBlank()) return "§c用法: dimension <维度ID>（如 minecraft:overworld）";
                map.setDimension(t[0]);
            }
            case "background" -> {
                if (t.length == 1 && t[0].equalsIgnoreCase("clear")) {
                    map.setBackgroundBase64("");
                } else {
                    if (value.isEmpty()) return "§c用法: background <base64> 或 clear";
                    map.setBackgroundBase64(value);
                }
            }
            // 全部整数字段统一解析，按字段各自的下限校验后写入对应 setter
            case "targetkills", "maxduration", "minplayers", "cooldownseconds", "minredplayers",
                 "minblueplayers", "preparetime", "boundarywarningtime", "boundarypenaltykills",
                 "kickcooldownseconds", "maxredplayers", "maxblueplayers" -> {
                Integer v = t.length == 1 ? parseIntOrNull(t[0]) : null;
                int min = (field.equals("targetkills") || field.equals("maxduration")
                        || field.equals("boundarywarningtime")
                        || field.equals("minredplayers") || field.equals("minblueplayers")) ? 1 : 0;
                if (v == null || v < min) return "§c" + field + " 必须是不小于 " + min + " 的整数";
                switch (field) {
                    case "targetkills" -> map.setTargetKills(v);
                    case "maxduration" -> map.setMaxDuration(v);
                    case "minplayers" -> map.setMinPlayers(v);
                    case "cooldownseconds" -> map.setCooldownSeconds(v);
                    case "minredplayers" -> map.setMinRedPlayers(v);
                    case "minblueplayers" -> map.setMinBluePlayers(v);
                    case "preparetime" -> map.setPrepareTime(v);
                    case "boundarywarningtime" -> map.setBoundaryWarningTime(v);
                    case "boundarypenaltykills" -> map.setBoundaryPenaltyKills(v);
                    case "kickcooldownseconds" -> map.setKickCooldownSeconds(v);
                    case "maxredplayers" -> map.setMaxRedPlayers(v);
                    default -> map.setMaxBluePlayers(v);
                }
            }
            default -> {
                return "§c未知字段: " + field + "（可选: " + String.join(" ", MAP_FIELDS) + "）";
            }
        }
        return null;
    }

    /** 边界编辑：6 个整数（minX minY minZ maxX maxY maxZ）或 clear 清空（全 0 = 未配置） */
    private static String applyBoundaryEdit(MapConfig.Boundary b, String[] t) {
        if (t.length == 1 && t[0].equalsIgnoreCase("clear")) {
            b.setMinX(0); b.setMinY(0); b.setMinZ(0);
            b.setMaxX(0); b.setMaxY(0); b.setMaxZ(0);
            return null;
        }
        if (t.length != 6) return "§c用法: <minX> <minY> <minZ> <maxX> <maxY> <maxZ>，或 clear 清空";
        int[] v = new int[6];
        for (int i = 0; i < 6; i++) {
            Integer n = parseIntOrNull(t[i]);
            if (n == null) return "§c坐标必须都是整数";
            v[i] = n;
        }
        b.setMinX(v[0]); b.setMinY(v[1]); b.setMinZ(v[2]);
        b.setMaxX(v[3]); b.setMaxY(v[4]); b.setMaxZ(v[5]);
        return null;
    }

    /** 布尔值解析：true/false/1/0，非法返回 null */
    private static Boolean parseBoolOrNull(String s) {
        return switch (s.toLowerCase()) {
            case "true", "1" -> true;
            case "false", "0" -> false;
            default -> null;
        };
    }

    /**
     * data delete player：删除玩家数据（支持离线玩家与 UUID）。
     * target 省略默认 all；profile=战绩档案、bags=背包快照、avatar=清除头像绑定、all=档案+背包。
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
            case "avatar" -> {
                // 清除头像绑定（avatarType/avatarId 置空并落盘，校验/写盘复用 setAvatarBinding）；
                // 原本就无绑定时按无可删数据处理
                boolean hadBinding = !profile.getAvatarType().isEmpty();
                String err = dataManager.setAvatarBinding(uuid, "", "");
                if (err != null) {
                    ctx.getSource().sendMessage(Text.literal(err));
                    return 0;
                }
                any = hadBinding;
                deleted = "头像绑定";
                if (hadBinding) {
                    // 在线玩家同步三处展示点（档案/战队成员列表/队列页）；离线仅落盘
                    NetworkHandler.syncAvatarBinding(uuid);
                }
            }
            case "all" -> {
                boolean p = dataManager.deleteProfile(uuid);
                boolean b = inventoryManager.deleteSavedInventory(uuid);
                any = p || b;
                deleted = (p ? "战绩档案" : "") + (p && b ? "、" : "") + (b ? "背包快照" : "");
            }
            default -> {
                ctx.getSource().sendMessage(Text.literal(
                        "§c无效的数据类型: §f" + target + " §7（可选: profile bags avatar all）"));
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
        // QuotedNameArgumentType 已剥引号：参数即战队名（强制引号格式）
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

    /**
     * 【作用】命令执行器全局异常保护包装：保护模式启用时提示命令不可用；
     *         执行器抛出任意 Throwable 时经 ModGuardian.engage 进入保护模式（错误堆栈打印到
     *         控制台并禁用模组），不再向 Brigadier 上抛导致服务器崩溃。
     * @param where 命令路径（仅用于保护模式日志定位）
     */
    private static Command<ServerCommandSource> guard(String where, Command<ServerCommandSource> inner) {
        return ctx -> {
            if (ModGuardian.isDisabled()) {
                ctx.getSource().sendMessage(Text.literal("§c[CSTMM] 模组已进入保护模式（内部错误停用），该命令不可用"));
                return 0;
            }
            try {
                return inner.run(ctx);
            } catch (Throwable t) {
                ModGuardian.engage("命令 /cstmm " + where, t);
                return 0;
            }
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

    // ==================== 战队补全数据源（按运行环境切换） ====================
    // 建议 provider 在双端执行：ClanManager 战队数据只在服务端进程。
    //  - SERVER（专用服/服务端进程）：直接用 ClanManager（数据在本地，控制台 Tab 也有效）；
    //  - CLIENT（玩家客户端）：只读 clan_hints 包同步的 CommandHintCache，缓存未到时
    //    返回空建议（绝不在客户端触碰 ClanManager——其本地实例为空且无意义）。
    private static final boolean IS_SERVER_ENV =
            net.fabricmc.loader.api.FabricLoader.getInstance().getEnvironmentType()
                    == net.fabricmc.api.EnvType.SERVER;

    /** 【作用】全部战队名（SERVER=ClanManager 本地数据；CLIENT=clan_hints 缓存，未到则空）。
     *  包私有：QuotedNameArgumentType 的名字建议数据源也使用。 */
    static List<String> hintClanNames() {
        if (IS_SERVER_ENV) return ClanManager.getInstance().getAllClanNames();
        return CommandHintCache.isEmpty() ? List.of() : CommandHintCache.getNames();
    }

    /** 【作用】战队成员名列表（同上按环境切换数据源；不存在返回空表）。
     *  供 suggestClanValue 的 leader 字段值建议（转让目标成员列表）。 */
    private static List<String> hintMembers(String clanName) {
        if (IS_SERVER_ENV) {
            Clan clan = ClanManager.getInstance().getClan(clanName);
            if (clan == null) return List.of();
            List<String> names = new ArrayList<>();
            for (Clan.Member m : clan.getMembers()) names.add(m.getName());
            return names;
        }
        List<String> members = CommandHintCache.isEmpty() ? null : CommandHintCache.getMembers(clanName);
        return members == null ? List.of() : members;
    }

    /** data edit player 的字段补全（avatar 走独立字面量分支，仅作提示展示） */
    private static CompletableFuture<Suggestions> suggestPlayerFields(CommandContext<ServerCommandSource> ctx,
                                                                      SuggestionsBuilder builder) {
        for (String field : new String[]{"kills", "deaths", "matches", "wins", "penaltyDeaths", "avatar"}) {
            if (field.toLowerCase().startsWith(builder.getRemaining().toLowerCase())) builder.suggest(field);
        }
        return builder.buildFuture();
    }

    /** data edit player avatar 的类型补全：qq / bili / clear（清除头像） */
    private static CompletableFuture<Suggestions> suggestAvatarTypes(CommandContext<ServerCommandSource> ctx,
                                                                     SuggestionsBuilder builder) {
        for (String type : new String[]{"qq", "bili", "clear"}) {
            if (type.startsWith(builder.getRemaining().toLowerCase())) builder.suggest(type);
        }
        return builder.buildFuture();
    }

    /** data get clan 的字段补全（独立尾参数，裸词建议即可正常展示） */
    private static CompletableFuture<Suggestions> suggestClanGetFields(CommandContext<ServerCommandSource> ctx,
                                                                       SuggestionsBuilder builder) {
        for (String field : CLAN_GET_FIELDS) {
            if (field.startsWith(builder.getRemaining().toLowerCase())) builder.suggest(field);
        }
        return builder.buildFuture();
    }

    /** data edit clan 的字段补全（独立尾参数，裸词建议即可正常展示） */
    private static CompletableFuture<Suggestions> suggestClanFields(CommandContext<ServerCommandSource> ctx,
                                                                    SuggestionsBuilder builder) {
        for (String field : CLAN_EDIT_FIELDS) {
            if (field.startsWith(builder.getRemaining().toLowerCase())) builder.suggest(field);
        }
        return builder.buildFuture();
    }

    /**
     * 【作用】data edit clan 的值补全（value 为独立尾参数，remaining 即值前缀，裸词建议即可）：
     *         按已输入字段取候选——leader=本战队成员名（转让目标）、badge=clear、
     *         limit=常用数字；name/abbr 为自由文本无建议。字段未知时不给建议。
     * 【被谁使用】registerData 的 edit clan value 参数节点。
     */
    private static CompletableFuture<Suggestions> suggestClanValue(CommandContext<ServerCommandSource> ctx,
                                                                   SuggestionsBuilder builder) {
        try {
            // 字段取自同一命令树的上游参数节点（Brigadier 解析成功才会调本 provider）
            String field;
            String clanName;
            try {
                field = StringArgumentType.getString(ctx, "field").toLowerCase();
                clanName = StringArgumentType.getString(ctx, "clan");
            } catch (IllegalArgumentException e) {
                return builder.buildFuture();
            }
            for (String s : clanValueSuggestions(clanName, field)) {
                if (s.startsWith(builder.getRemaining())) builder.suggest(s);
            }
            return builder.buildFuture();
        } catch (Exception e) {
            // 补全期异常不影响命令本身：降级为无建议
            Cstmm.LOGGER.warn("[CSTMM - Commands] suggestClanValue failed", e);
            return Suggestions.empty();
        }
    }

    /**
     * 【作用】data edit clan 的值建议候选：leader=本战队成员名（转让目标）、badge=clear（清空）、
     *         limit=常用数字；name/abbr 为自由文本无建议。
     * 【被谁使用】suggestClanValue。
     */
    private static List<String> clanValueSuggestions(String clanName, String field) {
        switch (field) {
            case "leader":
                return hintMembers(clanName);
            case "badge":
                return List.of("clear");
            case "limit":
                return List.of("10", "20", "30", "50", "0");
            default:
                return List.of();
        }
    }

    /** data get player 的单字段补全 */
    private static CompletableFuture<Suggestions> suggestPlayerGetFields(CommandContext<ServerCommandSource> ctx,
                                                                         SuggestionsBuilder builder) {
        for (String field : new String[]{"kills", "deaths", "matches", "wins", "penaltydeaths", "kd", "name", "uuid", "avatar", "bags"}) {
            if (field.startsWith(builder.getRemaining().toLowerCase())) builder.suggest(field);
        }
        return builder.buildFuture();
    }

    /** data delete player 的数据类型补全 */
    private static CompletableFuture<Suggestions> suggestDeleteTargets(CommandContext<ServerCommandSource> ctx,
                                                                       SuggestionsBuilder builder) {
        for (String target : new String[]{"profile", "bags", "avatar", "all"}) {
            if (target.startsWith(builder.getRemaining().toLowerCase())) builder.suggest(target);
        }
        return builder.buildFuture();
    }

    /** data get/edit/delete map 的地图 ID 补全（含禁用地图，管理操作需要能定位到全部地图） */
    private static CompletableFuture<Suggestions> suggestAnyMapIds(CommandContext<ServerCommandSource> ctx,
                                                                   SuggestionsBuilder builder) {
        String remaining = stripQuote(builder.getRemaining()).toLowerCase();
        for (MapConfig map : ConfigManager.getInstance().getMaps()) {
            if (map.getId().toLowerCase().startsWith(remaining)) builder.suggest(map.getId());
        }
        return builder.buildFuture();
    }

    /** data get/edit map 的字段补全（同一字段集合，edit 侧不含只读限制——当前全部字段均可编辑） */
    private static CompletableFuture<Suggestions> suggestMapFields(CommandContext<ServerCommandSource> ctx,
                                                                   SuggestionsBuilder builder) {
        String remaining = builder.getRemaining().toLowerCase();
        for (String field : MAP_FIELDS) {
            if (field.startsWith(remaining)) builder.suggest(field);
        }
        return builder.buildFuture();
    }

    /** data get/edit global 的字段补全 */
    private static CompletableFuture<Suggestions> suggestGlobalFields(CommandContext<ServerCommandSource> ctx,
                                                                      SuggestionsBuilder builder) {
        for (String field : new String[]{"quicktimeout", "gear"}) {
            if (field.startsWith(builder.getRemaining().toLowerCase())) builder.suggest(field);
        }
        return builder.buildFuture();
    }

    /** debug match_info_hud 的开关值补全（t/f 及别名） */
    private static CompletableFuture<Suggestions> suggestDebugToggle(CommandContext<ServerCommandSource> ctx,
                                                                     SuggestionsBuilder builder) {
        String remaining = builder.getRemaining().toLowerCase();
        if ("true".startsWith(remaining)) builder.suggest("t");
        if ("false".startsWith(remaining)) builder.suggest("f");
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
