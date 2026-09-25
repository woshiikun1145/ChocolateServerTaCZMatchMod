package cn.woshiikun_1145.mcmod.choco.cstmm.network;

import cn.woshiikun_1145.mcmod.choco.cstmm.Cstmm;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.Clan;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.config.GlobalConfig;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.config.MapConfig;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.MatchSession;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.PlayerProfile;
import cn.woshiikun_1145.mcmod.choco.cstmm.manager.*;
import cn.woshiikun_1145.mcmod.choco.cstmm.network.payload.*;
import cn.woshiikun_1145.mcmod.choco.cstmm.util.BlockPosAdapter;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 【作用】网络收发中枢：注册全部 S2C/C2S 数据包编解码器与服务端 C2S 接收器，并统一封装各类 S2C 发送（分片、限频、去重、按 UTF-8 字节截断）。
 *         配置同步采用哈希握手省带宽：JOIN 先发 ConfigMetaPayload（配置 SHA-256 + inUseMaps），
 *         客户端磁盘缓存哈希一致则只回报哈希、不再重复接收全量配置 JSON；3 秒未回应兜底全量下发。
 * 【被谁使用】Cstmm#onInitialize（服务端启动时 register）；MatchManager（sendHudData/sendMatchStatus）、VoteManager（sendMatchStatus）、
 * MatchScheduler（tickHandshake）、QueueManager（pushQueueStatusToSubscribers/deliverMessage）、ClanManager（sendClanMineTo）、
 * PlayerDataManager（sendPlayerProfile）、EventListener（sendPlayerProfile）、HudDataPayload（truncateByUtf8Bytes）。
 */
public class NetworkHandler {

    // 防重复注册标志（register 幂等）
    private static boolean registered = false;
    // JSON 序列化器：BlockPos 需经 BlockPosAdapter 自定义转换
    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(BlockPos.class, new BlockPosAdapter())
            .create();

    /** 配置同步分包大小（UTF-8 字节），须小于 writeString 上限 32767 字节与 C2S 整包 32768 字节限制 */
    private static final int CONFIG_CHUNK_BYTES = 30000;

    /** RequestConfigSync 每秒最大请求数，超过则拒绝并聊天栏提示 */
    private static final int MAX_SYNC_REQUESTS_PER_SECOND = 2;

    /** 配置哈希握手等待时长（毫秒）：JOIN 发出 ConfigMetaPayload 后超过该时长
     *  未收到客户端回应（哈希回报或同步请求），则兜底全量下发配置（防旧版客户端/丢包） */
    private static final long CONFIG_SYNC_ACK_TIMEOUT_MS = 3000L;

    /** 配置哈希握手待回应玩家：key: 玩家 UUID, value: JOIN 发出 meta 包的时间戳 */
    private static final Map<UUID, Long> pendingConfigSyncs = new ConcurrentHashMap<>();

    /** 核心配置 JSON 缓存（{"hash":..,"maps":..,"global":..}，不含 inUseMaps）：
     *  按 ConfigManager 版本号失效，避免每个玩家 JOIN 都重新序列化大 JSON */
    private static long cachedConfigVersion = -1;
    private static String cachedCoreConfigJson = null;
    /** 核心配置哈希（SHA-256 hex，64 字符）：与 cachedCoreConfigJson 同步重建 */
    private static String cachedConfigHash = "";

    /** 握手重试次数（首次发送后最多再重试 2 次） */
    private static final int MAX_HANDSHAKE_RETRIES = 2;
    /** key: 玩家 UUID, value: 已重试次数 */
    private static final Map<UUID, Integer> pendingHandshakes = new ConcurrentHashMap<>();

    /** C2S 配置更新分包重组缓冲：key: 玩家 UUID */
    private static final Map<UUID, StringBuilder> pendingConfigUpdates = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> pendingConfigUpdateTotals = new ConcurrentHashMap<>();
    /** 已收分片位掩码：bit i 置位表示 partIndex i 已收到 */
    private static final Map<UUID, Long> receivedConfigUpdateParts = new ConcurrentHashMap<>();
    /** 已收分片累积 UTF-8 字节数：key: 玩家 UUID */
    private static final Map<UUID, Integer> pendingConfigUpdateBytes = new ConcurrentHashMap<>();

    /** C2S 配置更新重组累积字节上限，超过则清空该玩家 pending 状态，防止 OOM */
    private static final int MAX_CONFIG_UPDATE_BYTES = 2_000_000;

    /** RequestConfigSync 限频状态：value: [窗口起始毫秒, 窗口内计数] */
    private static final Map<UUID, long[]> syncRequestWindows = new ConcurrentHashMap<>();

    /** 队列状态订阅者（"队列"页打开的玩家）：队列变化时服务端主动推送，替代客户端每秒轮询 */
    private static final Set<UUID> queueStatusSubscribers = ConcurrentHashMap.newKeySet();

    /**
     * 【作用】注册全部 S2C/C2S 数据包编解码器与服务端各 C2S 接收器，并挂接玩家加入/断线连接事件（幂等）。
     * 【被谁使用】Cstmm#onInitialize（服务端启动）。
     */
    public static void register() {
        if (registered) return;
        registered = true;

        // ===== S2C 数据包注册 =====
        // 【作用】注册服务端→客户端（S2C）各数据包的编解码器，客户端按相同 ID 的 Codec 解码
        PayloadTypeRegistry.playS2C().register(HudDataPayload.ID, HudDataPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(MatchStatusPayload.ID, MatchStatusPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ConfigSyncPayload.ID, ConfigSyncPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ConfigMetaPayload.ID, ConfigMetaPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(OpenConfigScreenPayload.ID, OpenConfigScreenPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(PlayerProfilePayload.ID, PlayerProfilePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(HandshakeS2CPayload.ID, HandshakeS2CPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ShopDataS2CPayload.ID, ShopDataS2CPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ClanDataPayload.ID, ClanDataPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(QueueStatusPayload.ID, QueueStatusPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(PopupPayload.ID, PopupPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(BadgePayload.ID, BadgePayload.CODEC);

        PayloadTypeRegistry.playC2S().register(MatchActionPayload.ID, MatchActionPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(ConfigUpdatePayload.ID, ConfigUpdatePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(RequestConfigSyncPayload.ID, RequestConfigSyncPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(HandshakeC2SPayload.ID, HandshakeC2SPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(ClanActionPayload.ID, ClanActionPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(RequestQueueStatusPayload.ID, RequestQueueStatusPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(RequestBadgePayload.ID, RequestBadgePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(BadgeKnownPayload.ID, BadgeKnownPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(SetFacePayload.ID, SetFacePayload.CODEC);

        // ===== C2S 接收器（服务端处理客户端请求） =====
        // 【作用】注册客户端→服务端（C2S）各数据包的编解码器与全局接收器，回调统一转服务端主线程执行
        // 【作用】C2S 对局操作（入队/退队/投票/购买/商店请求等）：转主线程执行 handleMatchAction
        ServerPlayNetworking.registerGlobalReceiver(MatchActionPayload.ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            if (player == null) return;
            context.server().execute(() -> handleMatchAction(payload, player));
        });

        // 【作用】C2S 配置更新分片：转主线程重组，集齐后应用配置（handleConfigUpdatePart）
        ServerPlayNetworking.registerGlobalReceiver(ConfigUpdatePayload.ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            if (player == null) return;
            context.server().execute(() -> handleConfigUpdatePart(payload, player));
        });

        ServerPlayNetworking.registerGlobalReceiver(RequestConfigSyncPayload.ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            if (player == null) return;
            context.server().execute(() -> {
                // 限频：每玩家每秒最多 2 次，防止恶意客户端打满 CPU/带宽
                if (!tryAcquireSyncRequest(player.getUuid())) {
                    player.sendMessage(Text.literal("§c配置同步请求过于频繁，每秒最多 "
                            + MAX_SYNC_REQUESTS_PER_SECOND + " 次！"), false);
                    return;
                }
                // 哈希比对：客户端缓存的配置哈希与服务端当前一致 → 仅回元数据包（更新 inUseMaps），
                // 省掉全量配置 JSON 下发；不一致（或客户端无缓存/旧版空载荷）→ 全量下发
                pendingConfigSyncs.remove(player.getUuid());
                String clientHash = payload.clientHash() == null ? "" : payload.clientHash();
                if (!clientHash.isEmpty() && clientHash.equals(getConfigHash())) {
                    sendConfigMeta(player);
                } else {
                    sendConfigSync(player);
                }
            });
        });

        // 握手：客户端对服务端主动请求的应答，收到即视为已响应（版本校验由客户端本地完成）
        ServerPlayNetworking.registerGlobalReceiver(HandshakeC2SPayload.ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            if (player == null) return;
            context.server().execute(() -> {
                pendingHandshakes.remove(player.getUuid());
                if (!getModVersion().equals(payload.clientVersion())) {
                    Cstmm.LOGGER.warn("[CSTMM - Network] Handshake version mismatch: client {}, server {}",
                            payload.clientVersion(), getModVersion());
                }
            });
        });

        // ===== 战队操作 / 队列状态请求 / 徽标缺失请求 =====
        ServerPlayNetworking.registerGlobalReceiver(ClanActionPayload.ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            if (player == null) return;
            context.server().execute(() -> handleClanActionMaybeChunked(payload, player));
        });

        ServerPlayNetworking.registerGlobalReceiver(RequestBadgePayload.ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            if (player == null) return;
            context.server().execute(() -> {
                Clan clan = ClanManager.getInstance().getClanByBadgeId(payload.badgeId());
                // URL 徽标不走势片下发通道（客户端直接拿 URL 自行下载）
                if (clan != null && !ClanManager.isBadgeUrl(clan.getBadgeBase64())) {
                    sendBadgeParts(player, clan.getBadgeId(), clan.getBadgeBase64());
                }
            });
        });

        // 【作用】客户端徽标缓存上报：把客户端磁盘已有的徽标 id 加入其"已下发"集合，
        //        后续 MINE/DETAIL 引用这些徽标时跳过分片重发（省带宽）；非法 id 静默忽略
        ServerPlayNetworking.registerGlobalReceiver(BadgeKnownPayload.ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            if (player == null) return;
            context.server().execute(() -> {
                Set<String> sent = sentBadges.computeIfAbsent(player.getUuid(), k -> ConcurrentHashMap.newKeySet());
                int accepted = 0;
                for (String id : payload.badgeIds().split(",")) {
                    if (!BadgePayload.isValidBadgeId(id)) continue;
                    if (accepted >= BadgeKnownPayload.MAX_IDS) break;
                    sent.add(id);
                    accepted++;
                }
            });
        });

        // 【作用】客户端设置/清除自己的头像：校验后落盘，并同步档案、战队成员列表与队列快照
        ServerPlayNetworking.registerGlobalReceiver(SetFacePayload.ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            if (player == null) return;
            context.server().execute(() -> handleSetFace(payload, player));
        });

        ServerPlayNetworking.registerGlobalReceiver(RequestQueueStatusPayload.ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            if (player == null) return;
            context.server().execute(() -> {
                UUID uuid = player.getUuid();
                if (payload.subscribe()) {
                    // 加入订阅集并立即回发一次快照；之后队列变化时服务端主动推送
                    queueStatusSubscribers.add(uuid);
                    ServerPlayNetworking.send(player, new QueueStatusPayload(
                            QueueManager.getInstance().buildQueueStatusJson(player)));
                } else {
                    queueStatusSubscribers.remove(uuid);
                }
            });
        });

        // 注意：OpenConfigScreenPayload 是 S2C，服务端不注册接收器，客户端注册。

        // ===== 玩家加入事件：服务端主动发起握手 =====
        // （履历同步/背包恢复统一由 EventListener 的 JOIN 处理；配置同步在此做哈希握手：
        //   先发小包元数据（哈希 + inUseMaps），客户端磁盘缓存哈希一致则回报哈希、
        //   服务端跳过全量下发，省掉重连玩家的大 JSON 重复传输）
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            sendHandshakeRequest(player);
            pendingHandshakes.put(player.getUuid(), 0);
            // 配置哈希握手：登记待回应并发出首包元数据；超时未回应由 tickHandshake 兜底全量下发
            pendingConfigSyncs.put(player.getUuid(), System.currentTimeMillis());
            sendConfigMeta(player);
            // 刷新战队成员显示名（支持离线后按名踢出/展示）
            ClanManager.getInstance().updateMemberName(player.getUuid(), player.getName().getString());
        });

        // 断线清理：分包重组缓冲与限频状态、队列状态订阅、徽标上传缓冲/已发集合、配置哈希握手状态
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID uuid = handler.getPlayer().getUuid();
            clearPendingConfigUpdate(uuid);
            syncRequestWindows.remove(uuid);
            queueStatusSubscribers.remove(uuid);
            pendingBadgeUploads.remove(uuid);
            sentBadges.remove(uuid);
            lastHudSent.remove(uuid); // 重连后客户端 HUD 状态已重置，首包必须重发
            pendingConfigSyncs.remove(uuid);
        });

        Cstmm.LOGGER.info("[CSTMM - Network] Registered network handlers");
    }

    // ==================== 战队 ====================

    // 徽标分片上传缓冲（C2S）：玩家 → 未集齐的徽标分片
    private static final Map<UUID, BadgeUploadBuf> pendingBadgeUploads = new ConcurrentHashMap<>();
    // 已完整下发过分片的徽标（S2C）：玩家 → badgeId 集合（内容寻址，同一徽标不重复下发）
    private static final Map<UUID, Set<String>> sentBadges = new ConcurrentHashMap<>();

    /** C2S 徽标分片上传缓冲 */
    private static final class BadgeUploadBuf {
        ClanActionPayload.ClanAction action;
        String text1, text2;
        int number;
        int totalParts;
        int receivedCount;
        final String[] parts;
        int accumulatedChars;

        BadgeUploadBuf(int totalParts) {
            this.totalParts = totalParts;
            this.parts = new String[totalParts];
        }
    }

    /**
     * C2S 大徽标分片上传：totalParts>1 时先缓存分片（只有最后一片触发执行），
     * 集齐后拼接为单片 payload 走正常处理。防护与配置同步分片一致：
     * totalParts ∈ [1,2]、partIndex 越界拒绝、累计大小上限、新序列覆盖旧序列。
     */
    private static void handleClanActionMaybeChunked(ClanActionPayload payload, ServerPlayerEntity player) {
        if (payload.badgeTotalParts() <= 1) {
            handleClanAction(payload, player);
            return;
        }
        UUID uuid = player.getUuid();
        int total = payload.badgeTotalParts();
        int index = payload.badgePartIndex();
        if (total > ClanActionPayload.MAX_TOTAL_PARTS || index < 0 || index >= total) {
            Cstmm.LOGGER.warn("[CSTMM - Network] Invalid badge upload chunk (index={}, total={}) from {}",
                    index, total, player.getName().getString());
            pendingBadgeUploads.remove(uuid);
            return;
        }
        BadgeUploadBuf buf = pendingBadgeUploads.get(uuid);
        // 新序列（第 0 片）或元数据不匹配时重置缓冲
        if (buf == null || index == 0
                || buf.totalParts != total || buf.action != payload.action()
                || !Objects.equals(buf.text1, payload.text1()) || !Objects.equals(buf.text2, payload.text2())
                || buf.number != payload.number()) {
            buf = new BadgeUploadBuf(total);
            buf.action = payload.action();
            buf.text1 = payload.text1();
            buf.text2 = payload.text2();
            buf.number = payload.number();
            pendingBadgeUploads.put(uuid, buf);
        }
        if (buf.parts[index] == null) {
            buf.parts[index] = payload.badge();
            buf.receivedCount++;
            buf.accumulatedChars += payload.badge().length();
            if (buf.accumulatedChars > ClanManager.MAX_BADGE_LENGTH) {
                pendingBadgeUploads.remove(uuid);
                Cstmm.LOGGER.warn("[CSTMM - Network] Badge upload exceeds size limit from {}", player.getName().getString());
                return;
            }
        }
        if (buf.receivedCount == total) {
            pendingBadgeUploads.remove(uuid);
            StringBuilder sb = new StringBuilder(buf.accumulatedChars);
            for (String part : buf.parts) sb.append(part);
            handleClanAction(ClanActionPayload.single(buf.action, buf.text1, buf.text2, sb.toString(), buf.number), player);
        }
    }

    /**
     * 【作用】处理战队操作请求（C2S，客户端→服务端）：按 action 分发到 ClanManager（增删改/加入/踢人/转让等），成功则回弹窗与最新 MINE 数据，失败回错误提示。
     * 【被谁使用】NetworkHandler 内部：ClanActionPayload 接收器（经 handleClanActionMaybeChunked 分片重组后调用，服务端主线程）。
     */
    private static void handleClanAction(ClanActionPayload payload, ServerPlayerEntity player) {
        ClanManager clans = ClanManager.getInstance();
        String err;
        switch (payload.action()) {
            case REQUEST_LIST -> sendClanData(player, "LIST", buildClanListJson(payload.text1()));
            case REQUEST_MINE -> sendMine(player);
            case REQUEST_DETAIL -> {
                Clan clan = clans.getClan(payload.text1());
                sendDetail(player, clan);
            }
            case CREATE -> {
                err = clans.create(player, payload.text1(), payload.text2(), payload.badge(), payload.number());
                if (err == null) {
                    sendPopup(player, "§a战队「" + payload.text1().trim() + "」创建成功！");
                    sendMine(player);
                } else {
                    deliverMessage(player, err);
                }
            }
            case JOIN -> {
                err = clans.join(player, payload.text1());
                if (err == null) {
                    sendPopup(player, "§a你已加入战队「" + payload.text1().trim() + "」！");
                    sendMine(player);
                } else {
                    deliverMessage(player, err);
                }
            }
            case LEAVE -> {
                err = clans.leave(player);
                if (err == null) {
                    sendPopup(player, "§e你已退出战队。");
                    sendMine(player);
                } else {
                    deliverMessage(player, err);
                }
            }
            case DISBAND -> {
                err = clans.disband(player);
                if (err == null) {
                    player.sendMessage(Text.literal("§e战队已解散。"), false);
                    sendMine(player);
                } else {
                    player.sendMessage(Text.literal(err), false);
                }
            }
            case TRANSFER -> {
                err = clans.transfer(player, payload.text1());
                if (err == null) {
                    player.sendMessage(Text.literal("§a已将队长转让给 " + payload.text1().trim() + "。"), false);
                    sendMine(player);
                } else {
                    player.sendMessage(Text.literal(err), false);
                }
            }
            case KICK -> {
                err = clans.kick(player, payload.text1());
                if (err == null) {
                    player.sendMessage(Text.literal("§e已将 " + payload.text1().trim() + " 移出战队。"), false);
                    sendMine(player);
                } else {
                    deliverMessage(player, err);
                }
            }
            case EDIT -> {
                err = clans.edit(player, payload.text1(), payload.text2(), payload.badge(), payload.number());
                if (err == null) {
                    sendPopup(player, "§a战队信息已更新！");
                    sendMine(player);
                } else {
                    deliverMessage(player, err);
                }
            }
        }
    }

    /** 发送我的战队状态：徽标分片（如未发过）先行，随后 MINE JSON（badge 字段为 badgeId） */
    private static void sendMine(ServerPlayerEntity player) {
        Clan clan = ClanManager.getInstance().getClanByPlayer(player.getUuid());
        sendBadgeIfMissing(player, clan);
        sendClanData(player, "MINE", buildClanMineJson(player));
    }

    /** 发送战队详情：徽标分片（如未发过）先行，随后 DETAIL JSON；clan 为 null 时回未找到 */
    private static void sendDetail(ServerPlayerEntity player, Clan clan) {
        if (clan == null) {
            sendClanData(player, "DETAIL", "{\"found\":false}");
        } else {
            sendBadgeIfMissing(player, clan);
            sendClanData(player, "DETAIL", buildClanJson(clan, true));
        }
    }

    /** 徽标尚未向该玩家下发过时发送全部分片（内容寻址，同一徽标只发一次） */
    private static void sendBadgeIfMissing(ServerPlayerEntity player, Clan clan) {
        if (clan == null || clan.getBadgeBase64().isEmpty()) return;
        // URL 徽标由客户端直接从 clan JSON 中的 URL 自行下载，不走服务器带宽
        if (ClanManager.isBadgeUrl(clan.getBadgeBase64())) return;
        String badgeId = clan.getBadgeId();
        Set<String> sent = sentBadges.computeIfAbsent(player.getUuid(), k -> ConcurrentHashMap.newKeySet());
        if (!sent.add(badgeId)) return;
        // 48KiB 上限生效前存储的遗留超大徽标不再下发（sent 已标记，仅警告一次）；
        // 队长编辑战队更换徽标后自动恢复
        if (clan.getBadgeBase64().length() > ClanManager.MAX_BADGE_LENGTH) {
            Cstmm.LOGGER.warn("[CSTMM - Network] Skipped oversized legacy badge of clan {} ({} chars > {}), ask the leader to re-upload",
                    clan.getName(), clan.getBadgeBase64().length(), ClanManager.MAX_BADGE_LENGTH);
            return;
        }
        sendBadgeParts(player, badgeId, clan.getBadgeBase64());
    }

    /** 按每片 ≤30000 字符拆分下发徽标（TCP 保序，客户端收齐后再收到引用该 id 的 JSON） */
    private static void sendBadgeParts(ServerPlayerEntity player, String badgeId, String badgeBase64) {
        if (badgeBase64 == null || badgeBase64.isEmpty()) return;
        int total = (badgeBase64.length() + BadgePayload.MAX_PART_CHARS - 1) / BadgePayload.MAX_PART_CHARS;
        for (int i = 0; i < total; i++) {
            int from = i * BadgePayload.MAX_PART_CHARS;
            int to = Math.min(from + BadgePayload.MAX_PART_CHARS, badgeBase64.length());
            ServerPlayNetworking.send(player, new BadgePayload(badgeId, i, total, badgeBase64.substring(from, to)));
        }
    }

    // 发送战队数据包（S2C ClanDataPayload）：kind 为 LIST/MINE/DETAIL，json 为对应负载
    private static void sendClanData(ServerPlayerEntity player, String kind, String json) {
        ServerPlayNetworking.send(player, new ClanDataPayload(kind, json));
    }

    /** 弹窗通知（客户端在当前界面内弹出对话框，无界面回退聊天栏） */
    private static void sendPopup(ServerPlayerEntity player, String message) {
        ServerPlayNetworking.send(player, new PopupPayload(message));
    }

    /**
     * 队列发生变化时由 QueueManager 调用：向所有订阅者（"队列"页打开的玩家）推送最新快照。
     * 事件驱动——只在变化时发包，替代客户端每秒轮询。
     */
    public static void pushQueueStatusToSubscribers() {
        var server = Cstmm.getServer();
        if (server == null || queueStatusSubscribers.isEmpty()) return;
        for (UUID uuid : queueStatusSubscribers) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(uuid);
            if (p != null) {
                ServerPlayNetworking.send(p, new QueueStatusPayload(
                        QueueManager.getInstance().buildQueueStatusJson(p)));
            }
        }
    }

    /**
     * 【作用】向单个玩家推送其战队状态（MINE，S2C ClanDataPayload）；玩家不在线则跳过。
     * 【被谁使用】ClanManager（战队成员变动/编辑后同步在线成员客户端）。
     */
    public static void sendClanMineTo(UUID playerUuid) {
        var server = Cstmm.getServer();
        if (server == null) return;
        ServerPlayerEntity p = server.getPlayerManager().getPlayer(playerUuid);
        if (p != null) {
            sendMine(p);
        }
    }

    /**
     * 【作用】按内容路由消息：携带 "POPUP:" 前缀的消息走弹窗（如"已在战队"、重复入队类提示），
     * 其余走聊天栏。服务端任意模块需要"按内容路由弹窗/聊天"时统一调用本方法——
     * 直接 player.sendMessage 会把 "POPUP:" 前缀原样发给客户端聊天栏。
     * 【被谁使用】QueueManager（重复入队提示）、NetworkHandler 内部 handleClanAction（战队操作失败提示）。
     */
    public static void deliverMessage(ServerPlayerEntity player, String message) {
        if (message != null && message.startsWith("POPUP:")) {
            sendPopup(player, message.substring(6));
        } else {
            player.sendMessage(Text.literal(message), false);
        }
    }

    /** 我的战队状态：{"inClan":bool, "clan":{...含成员列表}}（注意必须带 inClan 字段，客户端据此切换已加入视图） */
    private static String buildClanMineJson(ServerPlayerEntity player) {
        Clan clan = ClanManager.getInstance().getClanByPlayer(player.getUuid());
        if (clan == null) return "{\"inClan\":false}";
        JsonObject root = new JsonObject();
        root.addProperty("inClan", true);
        root.add("clan", buildClanObject(clan, true));
        return GSON.toJson(root);
    }

    /** 战队列表：query 为空时随机取 10 条，否则按名称/缩写/队长搜索：{"clans":[...]} */
    private static String buildClanListJson(String query) {
        List<Clan> clanList = (query == null || query.isBlank())
                ? ClanManager.getInstance().getRandomClans()
                : ClanManager.getInstance().search(query);
        JsonObject root = new JsonObject();
        JsonArray arr = new JsonArray();
        for (Clan clan : clanList) {
            JsonObject o = new JsonObject();
            o.addProperty("name", clan.getName());
            o.addProperty("abbr", clan.getAbbreviation());
            o.addProperty("leaderName", leaderName(clan));
            o.addProperty("memberCount", clan.getMembers().size());
            o.addProperty("limit", clan.getMemberLimit());
            arr.add(o);
        }
        root.add("clans", arr);
        return GSON.toJson(root);
    }

    /** 战队详情：{"found":true,"clan":{name,abbr,badge,leaderName,memberCount,limit,members:[...]}} */
    private static String buildClanJson(Clan clan, boolean withBadge) {
        JsonObject root = new JsonObject();
        root.addProperty("found", true);
        root.add("clan", buildClanObject(clan, withBadge));
        return GSON.toJson(root);
    }

    /** 战队对象：{name,abbr,badge?,leaderName,memberCount,limit,members:[{name,isLeader,online,matchState}]}；
     *  badge 字段：URL 徽标直接携带 URL（客户端自行下载，不占服务器带宽）；base64 徽标携带
     *  内容寻址 id（16 字符 hex），完整 base64 由 BadgePayload 分片单独下发 */
    private static JsonObject buildClanObject(Clan clan, boolean withBadge) {
        JsonObject c = new JsonObject();
        c.addProperty("name", clan.getName());
        c.addProperty("abbr", clan.getAbbreviation());
        if (withBadge && !clan.getBadgeBase64().isEmpty()) {
            c.addProperty("badge", ClanManager.isBadgeUrl(clan.getBadgeBase64())
                    ? clan.getBadgeBase64() : clan.getBadgeId());
        }
        c.addProperty("leaderName", leaderName(clan));
        c.addProperty("memberCount", clan.getMembers().size());
        c.addProperty("limit", clan.getMemberLimit());
        JsonArray members = new JsonArray();
        for (Clan.Member m : clan.getMembers()) {
            JsonObject mo = new JsonObject();
            mo.addProperty("name", m.getName());
            mo.addProperty("isLeader", m.getUuid().equals(clan.getLeader()));
            UUID memberUuid = parseUuidOrNull(m.getUuid());
            mo.addProperty("online", memberUuid != null && isOnline(memberUuid));
            // 匹配状态："" = 空闲，否则 "地图显示名-模式名"（快速匹配为 "快速匹配-模式名"）
            mo.addProperty("matchState", memberUuid == null ? "" : QueueManager.getInstance().matchStateOf(memberUuid));
            // 头像绑定（个性化设置，档案文件 avatarType/avatarId 字段）：只下发绑定，
            // 图片由各客户端自行获取；无档案/未绑定为空串
            PlayerProfile memberProfile = memberUuid == null ? null : PlayerDataManager.getInstance().getProfile(memberUuid);
            mo.addProperty("avatarType", memberProfile == null ? "" : memberProfile.getAvatarType());
            mo.addProperty("avatarId", memberProfile == null ? "" : memberProfile.getAvatarId());
            members.add(mo);
        }
        c.add("members", members);
        return c;
    }

    // 解析 UUID 字符串，非法格式返回 null
    private static UUID parseUuidOrNull(String uuid) {
        try {
            return UUID.fromString(uuid);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // 判断指定 UUID 的玩家当前是否在线
    private static boolean isOnline(UUID uuid) {
        var server = Cstmm.getServer();
        return server != null && server.getPlayerManager().getPlayer(uuid) != null;
    }

    // 查找战队队长成员名，找不到返回 "?"
    private static String leaderName(Clan clan) {
        for (Clan.Member m : clan.getMembers()) {
            if (m.getUuid().equals(clan.getLeader())) return m.getName();
        }
        return "?";
    }

    /**
     * 【作用】处理客户端设置/清除头像绑定请求（C2S SetFacePayload）：校验并写入玩家档案
     *         （config/cstmm/data/players/<uuid>.json 的 avatarType/avatarId 字段，进服已建档），
     *         随后同步三处展示点——自己的档案（履历页）、所在战队全部成员的 MINE 数据
     *         （成员列表头像广播给战队内所有玩家）、自己的队列快照（队列页徽标右侧头像）。
     *         服务端只存/发绑定，图片由各客户端自行获取。
     * 【被谁使用】NetworkHandler 内部：SetFacePayload 接收器（服务端主线程）。
     */
    private static void handleSetFace(SetFacePayload payload, ServerPlayerEntity player) {
        String type = payload.avatarType() == null ? "" : payload.avatarType().trim().toLowerCase();
        String id = payload.avatarId() == null ? "" : payload.avatarId().trim();
        String err = PlayerDataManager.getInstance().setAvatarBinding(player.getUuid(), type, id);
        if (err != null) {
            deliverMessage(player, err);
            return;
        }
        sendPopup(player, type.isEmpty() ? "§e头像已清除" : "§a头像已更新！");
        // 同步自己的档案（履历页头像，绑定随档案序列化下发）
        sendPlayerProfile(player);
        // 战队成员列表展示全成员头像：推 MINE 给本战队所有在线成员（含自己），刷新成员绑定字段
        Clan clan = ClanManager.getInstance().getClanByPlayer(player.getUuid());
        if (clan != null) {
            for (Clan.Member m : clan.getMembers()) {
                UUID memberUuid = parseUuidOrNull(m.getUuid());
                if (memberUuid != null) sendClanMineTo(memberUuid);
            }
        }
        // 队列页"战队徽标右侧"头像：向本人重发一份最新队列快照（头像变化本身不触发队列推送）
        ServerPlayNetworking.send(player, new QueueStatusPayload(
                QueueManager.getInstance().buildQueueStatusJson(player)));
    }

    /**
     * 【作用】处理对局操作请求（C2S，客户端→服务端）：加入/快速匹配/退出队列、踢人投票、档案请求、竞技局内商店查询与购买等动作分发到各 Manager。
     * 【被谁使用】NetworkHandler 内部：MatchActionPayload 接收器（服务端主线程）。
     */
    private static void handleMatchAction(MatchActionPayload payload, ServerPlayerEntity player) {
        switch (payload.action()) {
            case JOIN_QUEUE -> {
                String mapId = payload.mapName();
                int team = payload.team();
                if (mapId == null || mapId.isEmpty()) {
                    player.sendMessage(Text.literal("§c无效的地图"), false);
                    return;
                }
                // 客户端可注入任意整数，非法队伍值按无偏好处理
                if (team != 1 && team != 2) {
                    team = 0;
                }
                // 模式由玩家加入队列时主动选择，放在第 4 个字段（target）传递；
                // 空/非法值由 QueueManager 按竞技模式兜底
                QueueManager.getInstance().joinQueue(player, mapId, team, payload.target());
            }
            case JOIN_QUICK -> {
                // 快速匹配专用动作：不再以 "quick" 伪地图 ID 复用 JOIN_QUEUE，
                // 消除与真实地图 ID "quick" 的冲突；模式仍在 target 字段传递
                QueueManager.getInstance().joinQuickQueue(player, payload.target());
            }
            case LEAVE_QUEUE -> QueueManager.getInstance().leaveQueue(player);
            case VOTE_YES -> VoteManager.getInstance().handleVote(player, true);
            case VOTE_NO -> VoteManager.getInstance().handleVote(player, false);
            case REQUEST_PROFILE -> PlayerDataManager.getInstance().syncProfileToPlayer(player);
            case BUY_ITEM -> {
                // 资格校验：仅竞技模式对局中的玩家可购买（模式按会话判定，地图配置已无模式）
                MatchSession buySession = MatchManager.getInstance().getPlayerSession(player.getUuid());
                if (buySession == null || buySession.getPhase() == MatchSession.GamePhase.ENDED
                        || !buySession.isCompetitive()) {
                    player.sendMessage(Text.literal("§c仅竞技模式对局中可购买商品"), false);
                    return;
                }
                try {
                    int itemIndex = Integer.parseInt(payload.mapName());
                    boolean success = EquipmentManager.getInstance().givePurchasedItem(player, itemIndex);
                    if (success) {
                        player.sendMessage(Text.literal("§a购买成功！"), false);
                    } else {
                        player.sendMessage(Text.literal("§c购买失败，物品不存在或索引无效"), false);
                    }
                } catch (NumberFormatException e) {
                    player.sendMessage(Text.literal("§c无效的物品索引"), false);
                }
            }
            case REQUEST_SHOP -> {
                // 按玩家所在地图下发商店数据：仅竞技模式活跃对局中的玩家有购买资格（按会话判定）
                MatchSession session = MatchManager.getInstance().getPlayerSession(player.getUuid());
                MapConfig map = session != null
                        ? ConfigManager.getInstance().getMap(session.getMapName()) : null;
                boolean eligible = session != null && session.getPhase() != MatchSession.GamePhase.ENDED
                        && session.isCompetitive() && map != null;
                List<GlobalConfig.ShopItem> items = eligible && map != null
                        ? map.getShopItems() : List.of();
                ServerPlayNetworking.send(player, ShopDataS2CPayload.create(eligible, items));
            }
            case SELECT_TEAM -> {
                int team = payload.team();
                if (team == 1 || team == 2) {
                    player.sendMessage(Text.literal("§e已选择队伍: " + (team == 1 ? "红队" : "蓝队")), false);
                }
            }
            default -> Cstmm.LOGGER.debug("[CSTMM - Network] Unknown action: {} from {}", payload.action(), player.getName());
        }
    }

    // ===== C2S 配置更新分包重组 =====

    /**
     * 【作用】接收配置更新分片（C2S，客户端→服务端）：权限校验（OP≥2）、分片重组（位掩码去重、首包缺失丢弃、累积字节上限防护），集齐后交给 handleConfigUpdate。
     * 【被谁使用】NetworkHandler 内部：ConfigUpdatePayload 接收器（服务端主线程）。
     */
    private static void handleConfigUpdatePart(ConfigUpdatePayload payload, ServerPlayerEntity player) {
        UUID uuid = player.getUuid();

        if (!player.hasPermissionLevel(2)) {
            player.sendMessage(Text.literal("§c你没有权限修改配置！"), false);
            return;
        }

        int partIndex = payload.partIndex();
        int totalParts = payload.totalParts();

        // 重组防护：totalParts 必须在 [1, 64]，partIndex 必须在 [0, totalParts)，越界直接丢弃
        if (totalParts < 1 || totalParts > 64 || partIndex < 0 || partIndex >= totalParts) {
            Cstmm.LOGGER.warn("[CSTMM - Network] Invalid config update part (index {}, total {}) from {}, dropped",
                    partIndex, totalParts, player.getName());
            return;
        }

        if (partIndex == 0) {
            pendingConfigUpdates.put(uuid, new StringBuilder());
            pendingConfigUpdateTotals.put(uuid, totalParts);
            receivedConfigUpdateParts.put(uuid, 1L); // bit 0
            pendingConfigUpdateBytes.put(uuid, 0);
        } else if (!pendingConfigUpdates.containsKey(uuid)) {
            // 丢失首包，放弃本次更新
            Cstmm.LOGGER.warn("[CSTMM - Network] Config update missed first part from {}, dropped", player.getName());
            return;
        } else {
            long mask = receivedConfigUpdateParts.getOrDefault(uuid, 0L);
            if ((mask & (1L << partIndex)) != 0) {
                // 同一分片重复到达，直接丢弃，防止重复拼接
                Cstmm.LOGGER.warn("[CSTMM - Network] Duplicate config update part {} from {}, dropped",
                        partIndex, player.getName());
                return;
            }
            receivedConfigUpdateParts.put(uuid, mask | (1L << partIndex));
        }

        // 累积总量上限：UTF-8 字节数超过上限时清空该玩家 pending 状态，防止恶意刷包导致 OOM
        int partBytes = payload.data().getBytes(StandardCharsets.UTF_8).length;
        if (pendingConfigUpdateBytes.merge(uuid, partBytes, Integer::sum) > MAX_CONFIG_UPDATE_BYTES) {
            clearPendingConfigUpdate(uuid);
            Cstmm.LOGGER.warn("[CSTMM - Network] Config update from {} exceeded {} UTF-8 bytes, pending state cleared",
                    player.getName(), MAX_CONFIG_UPDATE_BYTES);
            return;
        }

        pendingConfigUpdates.get(uuid).append(payload.data());

        if (Long.bitCount(receivedConfigUpdateParts.getOrDefault(uuid, 0L))
                >= pendingConfigUpdateTotals.getOrDefault(uuid, -1)) {
            String json = pendingConfigUpdates.remove(uuid).toString();
            clearPendingConfigUpdate(uuid);
            handleConfigUpdate(json, player);
        }
    }

    /** 清空指定玩家的配置更新重组缓冲 */
    private static void clearPendingConfigUpdate(UUID uuid) {
        pendingConfigUpdates.remove(uuid);
        pendingConfigUpdateTotals.remove(uuid);
        receivedConfigUpdateParts.remove(uuid);
        pendingConfigUpdateBytes.remove(uuid);
    }

    /**
     * 【作用】应用重组完成的配置 JSON（需 OP≥2）：解析校验、禁止删除正在对局中使用的地图、统一写回 ConfigManager，随后向全服在线玩家广播新配置。
     * 【被谁使用】NetworkHandler 内部：handleConfigUpdatePart（分片集齐后调用）。
     */
    private static void handleConfigUpdate(String json, ServerPlayerEntity player) {
        if (!player.hasPermissionLevel(2)) {
            player.sendMessage(Text.literal("§c你没有权限修改配置！"), false);
            return;
        }
        try {
            var obj = GSON.fromJson(json, com.google.gson.JsonObject.class);
            if (obj == null) {
                player.sendMessage(Text.literal("§c配置数据为空，保存已取消"), false);
                return;
            }
            var mapListType = new com.google.gson.reflect.TypeToken<List<MapConfig>>() {}.getType();
            List<MapConfig> maps = GSON.fromJson(obj.get("maps"), mapListType);
            GlobalConfig global = GSON.fromJson(obj.get("global"), GlobalConfig.class);

            if (maps == null || global == null) {
                player.sendMessage(Text.literal("§c配置数据不完整（缺少 maps 或 global），保存已取消"), false);
                return;
            }
            if (global.getDefaultGear() == null) global.setDefaultGear(new java.util.ArrayList<>());

            ConfigManager configManager = ConfigManager.getInstance();

            // 客户端删除的地图 = 服务端存在但不在本次提交中的地图；
            // 正在对局中使用的地图禁止删除，否则该对局将永久僵死
            Set<String> payloadIds = new HashSet<>();
            for (MapConfig map : maps) {
                payloadIds.add(map.getId());
            }
            List<String> removedIds = new ArrayList<>();
            for (MapConfig existing : configManager.getMaps()) {
                if (!payloadIds.contains(existing.getId())) {
                    removedIds.add(existing.getId());
                }
            }
            for (String removedId : removedIds) {
                boolean inUse = MatchManager.getInstance().getAllActiveSessions().stream()
                        .anyMatch(s -> s.getMapName().equals(removedId)
                                && s.getPhase() != cn.woshiikun_1145.mcmod.choco.cstmm.data.MatchSession.GamePhase.ENDED);
                if (inUse) {
                    player.sendMessage(Text.literal(
                            "§c地图 \"" + removedId + "\" 正在对局中使用，禁止删除！本次保存已取消"), false);
                    return;
                }
            }

            // 先解析校验完毕，再统一应用，避免中途异常导致半写状态
            for (MapConfig map : maps) {
                configManager.updateMap(map);
            }
            for (String removedId : removedIds) {
                configManager.removeMap(removedId);
            }
            configManager.updateGlobalConfig(global);

            sendPopup(player, "§a配置已保存！");

            // 广播给所有在线玩家：使用服务端权威重建的数据，而非客户端原始 JSON。
            // 配置刚变更、全员哈希必然不匹配，全量下发不可避免；元数据随行更新 inUseMaps
            String syncJson = buildCoreConfigJson();
            if (syncJson != null) {
                for (ServerPlayerEntity p : player.getServer().getPlayerManager().getPlayerList()) {
                    sendConfigSync(p, syncJson);
                    sendConfigMeta(p);
                }
            }
        } catch (Exception e) {
            Cstmm.LOGGER.error("[CSTMM - Network] Failed to handle config update", e);
            player.sendMessage(Text.literal("§c配置保存失败: " + e.getMessage()), false);
        }
    }

    /** 每玩家每秒最多 2 次配置同步请求，返回是否放行 */
    private static boolean tryAcquireSyncRequest(UUID uuid) {
        long now = System.currentTimeMillis();
        long[] window = syncRequestWindows.computeIfAbsent(uuid, k -> new long[]{now, 0});
        if (now - window[0] >= 1000L) {
            window[0] = now;
            window[1] = 0;
        }
        if (window[1] >= MAX_SYNC_REQUESTS_PER_SECOND) {
            return false;
        }
        window[1]++;
        return true;
    }

    // ===== UTF-8 字节工具 =====

    /**
     * 【作用】按 UTF-8 字节边界切分字符串：每片最多 maxBytes 字节，且不切断多字节字符。
     * PacketByteBuf.writeString 校验的是 UTF-8 编码后的字节数而非字符数，
     * 含中文等非 ASCII 字符时必须按字节切分，否则编码后会超出上限抛 EncoderException。
     * 【被谁使用】NetworkHandler 内部：sendConfigSync（配置 JSON 分包发送）。
     */
    public static List<String> splitByUtf8Bytes(String s, int maxBytes) {
        List<String> parts = new ArrayList<>();
        if (s == null || s.isEmpty()) return parts;
        int len = s.length();
        int start = 0;
        while (start < len) {
            int byteCount = 0;
            int end = start;
            while (end < len) {
                char c = s.charAt(end);
                int cb;
                if (Character.isHighSurrogate(c) && end + 1 < len && Character.isLowSurrogate(s.charAt(end + 1))) {
                    cb = 4; // 增补字符（代理对，UTF-8 占 4 字节）
                } else if (c < 0x80) {
                    cb = 1;
                } else if (c < 0x800) {
                    cb = 2;
                } else {
                    // BMP 多字节字符占 3 字节（未配对代理实际编码为 1 字节 '?'，按 3 估算只会更保守）
                    cb = 3;
                }
                if (byteCount + cb > maxBytes) break;
                byteCount += cb;
                end += cb == 4 ? 2 : 1;
            }
            if (end == start) end = start + 1; // 单字符超过 maxBytes 的极端兜底，防止死循环
            parts.add(s.substring(start, end));
            start = end;
        }
        return parts;
    }

    /**
     * 【作用】按 UTF-8 字节上限截断字符串：不切断多字节字符，超限时在最后一个完整字符处截断。
     * null 视为空串，保证 writeString 永不因长度/空值抛异常。
     * 【被谁使用】HudDataPayload 编码（mapName 截断）、NetworkHandler 内部 sendMatchStatus（message 截断）。
     */
    public static String truncateByUtf8Bytes(String s, int maxBytes) {
        if (s == null || s.isEmpty()) return "";
        int len = s.length();
        int byteCount = 0;
        for (int i = 0; i < len; ) {
            char c = s.charAt(i);
            int cb;
            if (Character.isHighSurrogate(c) && i + 1 < len && Character.isLowSurrogate(s.charAt(i + 1))) {
                cb = 4;
            } else if (c < 0x80) {
                cb = 1;
            } else if (c < 0x800) {
                cb = 2;
            } else {
                cb = 3;
            }
            if (byteCount + cb > maxBytes) {
                return s.substring(0, i);
            }
            byteCount += cb;
            i += cb == 4 ? 2 : 1;
        }
        return s;
    }

    // ===== 发送方法 =====

    /**
     * 【作用】获取服务端本模组版本号（用于握手版本校验）。
     * 【被谁使用】NetworkHandler 内部：sendHandshakeRequest、HandshakeC2SPayload 接收器（版本比对告警）。
     */
    public static String getModVersion() {
        return net.fabricmc.loader.api.FabricLoader.getInstance()
                .getModContainer(Cstmm.MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    /**
     * 【作用】发送 HUD 数据（订阅式推送：内容与该玩家上次收到的快照一致时跳过发包，节省带宽）。
     * 所有 HUD 发送（每秒广播、结算清除）统一经此漏斗去重；对局内多数秒数据无变化，
     * KILLS 制地图尤其明显。快照按玩家记录而非按对局——中途补位/外部 API 加入的玩家
     * 无历史快照，下一次广播必发，避免 KILLS 图上补位玩家长期无 HUD。
     * 断线时清除记录（重连后首包必然重发）。
     * 【被谁使用】MatchManager（每秒对局广播、结算清除 HUD）。
     */
    public static void sendHudData(ServerPlayerEntity player, HudDataPayload payload) {
        HudSnapshot snapshot = new HudSnapshot(payload.mapName(), payload.redKills(),
                payload.blueKills(), payload.remainingSeconds(), payload.inGame());
        if (!Objects.equals(lastHudSent.put(player.getUuid(), snapshot), snapshot)) {
            ServerPlayNetworking.send(player, payload);
        }
    }

    /** HUD 已发送快照（订阅式推送去重）：key: 玩家 UUID */
    private static final Map<UUID, HudSnapshot> lastHudSent = new ConcurrentHashMap<>();

    private record HudSnapshot(String mapName, int redKills, int blueKills, int remainingSeconds, boolean inGame) {}

    /**
     * 【作用】发送对局状态消息包（S2C MatchStatusPayload），发送前统一将 message 截断到 128 UTF-8 字节以内，保证 writeString 永不抛异常。
     * 【被谁使用】MatchManager（对局状态推送）、VoteManager（踢人投票状态推送）。
     */
    public static void sendMatchStatus(ServerPlayerEntity player, MatchStatusPayload payload) {
        // message 上限 128 UTF-8 字节：writeString 校验编码后字节数，超长时在发送链路统一截断
        // （不切断多字节字符），确保 writeString 永不抛异常；所有发送点均经由本方法
        String message = truncateByUtf8Bytes(payload.message(), 128);
        if (message != payload.message()) {
            payload = new MatchStatusPayload(payload.type(), message, payload.redKills(), payload.blueKills());
        }
        ServerPlayNetworking.send(player, payload);
    }

    /**
     * 【作用】向客户端发送握手请求（S2C HandshakeS2CPayload，携带服务端模组版本号），客户端本地校验版本后回 C2S 应答。
     * 【被谁使用】NetworkHandler 内部：ServerPlayConnectionEvents.JOIN 回调与 tickHandshake（未应答重试）。
     */
    public static void sendHandshakeRequest(ServerPlayerEntity player) {
        ServerPlayNetworking.send(player, new HandshakeS2CPayload(getModVersion()));
    }

    /** 每秒调用：未收到客户端握手应答则重试（最多重试 2 次）；配置哈希握手超时未回应则兜底全量下发 */
    public static void tickHandshake() {
        if (pendingHandshakes.isEmpty() && pendingConfigSyncs.isEmpty()) return;
        var server = Cstmm.getServer();
        if (server == null) return;

        for (Map.Entry<UUID, Integer> entry : new HashMap<>(pendingHandshakes).entrySet()) {
            int retries = entry.getValue();
            if (retries >= MAX_HANDSHAKE_RETRIES) {
                pendingHandshakes.remove(entry.getKey());
                continue;
            }
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(entry.getKey());
            if (player == null) {
                pendingHandshakes.remove(entry.getKey());
                continue;
            }
            pendingHandshakes.put(entry.getKey(), retries + 1);
            sendHandshakeRequest(player);
        }

        // 配置哈希握手兜底：JOIN 发出元数据后超时未收到客户端回应
        // （旧版客户端不认识 ConfigMetaPayload、新版客户端卡顿或丢包），直接全量下发配置
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Long> entry : new HashMap<>(pendingConfigSyncs).entrySet()) {
            if (now - entry.getValue() < CONFIG_SYNC_ACK_TIMEOUT_MS) continue;
            pendingConfigSyncs.remove(entry.getKey());
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(entry.getKey());
            if (player != null) {
                sendConfigSync(player);
            }
        }
    }

    /**
     * 【作用】向单个玩家全量同步配置：分片下发核心配置 JSON（含哈希）+ 元数据包（inUseMaps）。
     * 【被谁使用】NetworkHandler 内部：RequestConfigSyncPayload 接收器（客户端哈希不匹配/无缓存）、
     *           tickHandshake（哈希握手超时兜底）。
     */
    public static void sendConfigSync(ServerPlayerEntity player) {
        String json = buildCoreConfigJson();
        if (json == null) return;
        sendConfigSync(player, json);
        sendConfigMeta(player);
    }

    /**
     * 分包发送配置 JSON：按 UTF-8 字节边界切分（writeString 校验的是编码后字节数，
     * 按字符切分在含中文时会超限），规避单包 32767 字节上限（背景图 base64 可能很大）。
     * 【被谁使用】NetworkHandler 内部：sendConfigSync（全量同步）、handleConfigUpdate（保存后广播）。
     */
    public static void sendConfigSync(ServerPlayerEntity player, String json) {
        List<String> chunks = splitByUtf8Bytes(json, CONFIG_CHUNK_BYTES);
        int totalParts = chunks.size();
        for (int i = 0; i < totalParts; i++) {
            ServerPlayNetworking.send(player, new ConfigSyncPayload(i, totalParts, chunks.get(i)));
        }
    }

    /**
     * 【作用】向单个玩家发送配置元数据小包（核心配置 SHA-256 哈希 + inUseMaps JSON，约百字节）。
     * 【被谁使用】NetworkHandler 内部：JOIN 哈希握手首包、RequestConfigSync 哈希匹配回应、sendConfigSync（全量随行）。
     */
    private static void sendConfigMeta(ServerPlayerEntity player) {
        ServerPlayNetworking.send(player, new ConfigMetaPayload(getConfigHash(), buildInUseMapsJson()));
    }

    /**
     * 【作用】读取核心配置（maps+global）的 SHA-256 哈希（hex，64 字符），必要时先重建缓存。
     * 【被谁使用】NetworkHandler 内部：RequestConfigSync 哈希比对、sendConfigMeta。
     */
    private static String getConfigHash() {
        ensureConfigCache();
        return cachedConfigHash;
    }

    /**
     * 【作用】按 ConfigManager 版本号惰性重建"核心配置 JSON + 哈希"缓存（同一版本只序列化一次，
     *         避免每个玩家 JOIN 都重复序列化含背景图 base64 的大 JSON）。
     *         核心配置不含 inUseMaps——对局开始/结束不改变哈希，客户端磁盘缓存可长期复用。
     * 【被谁使用】NetworkHandler 内部：getConfigHash、buildCoreConfigJson。
     */
    private static void ensureConfigCache() {
        long version = ConfigManager.getInstance().getConfigVersion();
        if (version == cachedConfigVersion && cachedCoreConfigJson != null) return;
        try {
            ConfigManager cm = ConfigManager.getInstance();
            com.google.gson.JsonObject core = new com.google.gson.JsonObject();
            core.add("maps", GSON.toJsonTree(cm.getMaps()));
            core.add("global", GSON.toJsonTree(cm.getGlobalConfig()));
            String json = GSON.toJson(core);
            cachedConfigHash = sha256Hex(json);
            // 哈希字段写入 JSON：客户端解析后与磁盘缓存关联，哈希匹配时免收全量
            com.google.gson.JsonObject withHash = new com.google.gson.JsonObject();
            withHash.addProperty("hash", cachedConfigHash);
            withHash.add("maps", core.get("maps"));
            withHash.add("global", core.get("global"));
            cachedCoreConfigJson = GSON.toJson(withHash);
            cachedConfigVersion = version;
        } catch (Exception e) {
            Cstmm.LOGGER.error("[CSTMM - Network] Failed to build config json", e);
            cachedCoreConfigJson = null;
            cachedConfigHash = "";
        }
    }

    /**
     * 【作用】获取缓存的核心配置 JSON（{"hash":..,"maps":[..],"global":{..}}，不含 inUseMaps）；构建失败返回 null。
     * 【被谁使用】NetworkHandler 内部：sendConfigSync（全量下发）。
     */
    private static String buildCoreConfigJson() {
        ensureConfigCache();
        return cachedCoreConfigJson;
    }

    /**
     * 【作用】构建 inUseMaps（正在对局中使用的地图 ID 列表）JSON 数组字符串，供客户端配置界面阻止删除。
     * 【被谁使用】NetworkHandler 内部：sendConfigMeta。
     */
    private static String buildInUseMapsJson() {
        List<String> inUseMaps = new ArrayList<>();
        for (MatchSession session : MatchManager.getInstance().getAllActiveSessions()) {
            if (session.getPhase() != cn.woshiikun_1145.mcmod.choco.cstmm.data.MatchSession.GamePhase.ENDED) {
                inUseMaps.add(session.getMapName());
            }
        }
        return GSON.toJson(inUseMaps);
    }

    /** 计算字符串 SHA-256 并返回 64 字符小写 hex；哈希仅用于两端缓存比对，安全强度要求低 */
    private static String sha256Hex(String s) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            // JVM 均支持 SHA-256，此分支理论不可达；回退 hashCode 保证功能可用（两端一致即可）
            return "h" + Integer.toHexString(s.hashCode());
        }
    }

    /**
     * 【作用】发送玩家档案 JSON（S2C PlayerProfilePayload），供客户端履历展示/背包恢复；超过 65536 UTF-8 字节时跳过并告警。
     * 【被谁使用】EventListener（JOIN 时）、PlayerDataManager（档案变更后同步在线客户端）。
     */
    public static void sendPlayerProfile(ServerPlayerEntity player) {
        PlayerProfile profile = PlayerDataManager.getInstance().getProfile(player.getUuid());
        if (profile == null) return;
        // 头像绑定随档案一起序列化下发（PlayerProfile 的 avatarType/avatarId 字段，履历页/个性化页展示）
        String json = GSON.toJson(profile);
        // PlayerProfilePayload 的 writeString 上限为 65536，且校验的是 UTF-8 编码后的字节数
        // （调用点位于 JOIN 回调），超长会抛异常导致背包恢复逻辑被跳过，这里显式防护
        int jsonBytes = json.getBytes(StandardCharsets.UTF_8).length;
        if (jsonBytes >= 65536) {
            Cstmm.LOGGER.warn("[CSTMM - Network] Player profile json too large ({} UTF-8 bytes) for {}, skip sync",
                    jsonBytes, player.getName());
            return;
        }
        ServerPlayNetworking.send(player, new PlayerProfilePayload(json));
    }

    /**
     * 【作用】通知客户端打开配置界面（S2C OpenConfigScreenPayload）。
     * 【被谁使用】当前无调用方（ModCommands 的 /cstmm config 直接经 ServerPlayNetworking 发送 OpenConfigScreenPayload，未走本方法）。
     */
    public static void sendOpenConfigScreen(ServerPlayerEntity player) {
        ServerPlayNetworking.send(player, new OpenConfigScreenPayload());
    }
}
