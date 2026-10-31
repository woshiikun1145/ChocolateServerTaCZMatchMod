package cn.woshiikun_1145.mcmod.choco.cstmm.manager;

import cn.woshiikun_1145.mcmod.choco.cstmm.Cstmm;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.MatchSession;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.config.MapConfig;
import cn.woshiikun_1145.mcmod.choco.cstmm.entity.BombEntity;
import cn.woshiikun_1145.mcmod.choco.cstmm.entity.ModEntities;
import cn.woshiikun_1145.mcmod.choco.cstmm.item.ModItems;
import cn.woshiikun_1145.mcmod.choco.cstmm.network.NetworkHandler;
import cn.woshiikun_1145.mcmod.choco.cstmm.sound.ModSounds;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.UUID;

/**
 * 【作用】爆破交互管理器（阶段③，每服务器 tick 推进）：
 *         安放——T 方玩家手持 cstmm:c4 站在包点内长按右键（使用键；客户端按键状态翻转时发
 *         PLANT_BOMB start/stop），服务端逐 tick 校验并累计进度，满 plantTimeSeconds
 *         后移除手中 C4、生成 BombEntity（绿色长方体）、标记 bombPlanted 并启动 C4 倒计时；
 *         拆除——CT 方玩家靠近已安放 C4（2.5 格）长按右键发 DEFUSE_BOMB start/stop，
 *         满 defuseSeconds（带拆弹装备 defuseKitSeconds，以玩家 tag cstmm_defuse_kit 判定，非物品）
 *         后拆除成功 → CT 赢得回合 + 个人拆除奖励。
 *         中断条件：松键 / 自行移动（被他人推动不算）/ 死亡 / 离开包点或炸弹范围 / C4 离身 / 回合结束，进度清零。
 * 【被谁使用】MatchScheduler（每 tick 调 tick）、NetworkHandler（PLANT_BOMB/DEFUSE_BOMB 分支）、
 *           BombRoundManager（回合重置/爆炸/拆除清理实体复用 discardBombs）。仅服务端。
 */
public class BombInteractionManager {

    private static BombInteractionManager instance;

    /** 拆除判定距离（与 C4 实体的距离，方块） */
    private static final double DEFUSE_RANGE = 2.5;
    /** 客户端检测 C4 的距离（拆除按键提示与 start 请求的前置条件，客户端与服务端一致） */
    public static final double DEFUSE_RANGE_CLIENT = 3.0;

    /**
     * 拆弹装备的持有标记（玩家 command tag，不是物品）：休闲每回合自动打给 CT、
     * 竞技阵亡清除/商店购买获得、对局结束统一清除；拆除时间（带 kit 5s / 裸拆 10s）
     * 与 HUD 拆弹器标记均以此 tag 为准。
     */
    public static final String DEFUSE_KIT_TAG = "cstmm_defuse_kit";

    /**
     * debug 开关：允许任何情况下安放 C4（任意位置/阵营/阶段，含**对局外**）。
     * OP≥2 经 /cstmm debug allow_c4 to_be_planted_freely <t|f> 切换，仅内存态不持久化，
     * 重启后恢复默认（不允许）。
     */
    private static volatile boolean freePlantingAllowed = false;

    public static boolean isFreePlantingAllowed() { return freePlantingAllowed; }

    public static void setFreePlantingAllowed(boolean allowed) { freePlantingAllowed = allowed; }

    // 对局外自由安放/拆除进度（自由模式下无 MatchSession 承载，用独立内存表；uuid → 已累计 tick）
    private final java.util.Map<UUID, Integer> freePlantProgress = new java.util.HashMap<>();
    private final java.util.Map<UUID, Integer> freeDefuseProgress = new java.util.HashMap<>();
    // 安放/拆除期间的"自行移动"中断由客户端判定：玩家按住移动/跳跃键时客户端发 stop 请求，
    // 服务端不做位置锚点校验（因此被他人推动、击退等被动位移不会中断进度）
    // 对局内炸弹哔声节奏：sessionId → 下一声时间戳（millis；discardBombs 清除）
    private final java.util.Map<String, Long> beepNextAt = new java.util.HashMap<>();

    // ==================== C4 哔声节奏（响的快慢体现距爆炸时间） ====================

    /** 哔声间隔（millis，对局内按剩余秒数分档）：>10s 每秒一响 → 10~5s 每半秒 → 5~2s 每四分之一秒 → <2s 每八分之一秒 */
    private static long beepIntervalMillis(int remainingSeconds) {
        if (remainingSeconds > 10) return 1000;
        if (remainingSeconds > 5) return 500;
        if (remainingSeconds > 2) return 250;
        return 125;
    }

    /** 哔声间隔（tick，自由炸弹实体用）：与 millis 版同档（125ms ≈ 3 tick）。 */
    public static int beepIntervalTicks(int remainingSeconds) {
        if (remainingSeconds > 10) return 20;
        if (remainingSeconds > 5) return 10;
        if (remainingSeconds > 2) return 5;
        return 3;
    }

    /** 哔声音高固定 1.0（不随剩余时间升调，紧迫感仅由间隔快慢体现）。 */
    public static float beepPitch(int remainingSeconds) {
        return 1.0f;
    }

    /**
     * 对局内炸弹哔声推进（每 tick）：炸弹已安放时按剩余秒数分档的间隔，在安放位置播放
     * cstmm:c4_beep（音量 2.0 ≈ 32 格可闻）。计时表在 discardBombs（拆除/爆炸/回合重置）清除。
     */
    private void tickBombBeep(MatchSession session) {
        if (!session.isBombPlanted()) return;
        int[] pos = session.getPlantedPos();
        if (pos == null) return;
        String key = session.getSessionId();
        long now = System.currentTimeMillis();
        Long next = beepNextAt.get(key);
        if (next != null && now < next) return;

        int remaining = session.getBombTimerSeconds();
        ServerWorld world = QueueManager.getWorld();
        if (world != null) {
            world.playSound(null, pos[0] + 0.5, pos[1] + 0.5, pos[2] + 0.5,
                    ModSounds.C4_BEEP.value(), net.minecraft.sound.SoundCategory.BLOCKS,
                    2.0f, beepPitch(remaining));
        }
        beepNextAt.put(key, now + beepIntervalMillis(remaining));
    }

    private BombInteractionManager() {}

    public static BombInteractionManager getInstance() {
        if (instance == null) {
            instance = new BombInteractionManager();
        }
        return instance;
    }

    // ==================== 交互请求（C2S：按键状态翻转时触发） ====================

    /**
     * 【作用】设置/取消安放状态（客户端手持 C4 长按右键状态翻转时调用）：
     *         start 时校验对局/阵营/包点/手持，条件不满足发提示；stop 清空进度。
     * 【被谁使用】NetworkHandler 的 PLANT_BOMB 分支。仅服务端。
     */
    public void setPlanting(ServerPlayerEntity player, boolean start) {
        MatchSession session = MatchManager.getInstance().getPlayerSession(player.getUuid());
        UUID uuid = player.getUuid();

        if (!start) {
            freePlantProgress.remove(uuid);
            if (session != null && uuid.equals(session.getPlantingPlayer())) {
                session.setPlantingPlayer(null);
                session.setPlantProgress(0);
            }
            return;
        }

        // 对局外：仅自由安放模式允许（存活 + 手持 C4，无其他限制），进度走独立内存表
        if (session == null || !session.isBombMode()) {
            if (!freePlantingAllowed) return;
            if (player.isDead()) return;
            if (!player.getMainHandStack().isOf(ModItems.C4)) return;
            // 首次开始安放 → 播放安放进行音（键入声）
            if (freePlantProgress.putIfAbsent(uuid, 0) == null) {
                player.getWorld().playSound(null, player.getBlockPos(),
                        ModSounds.C4_PLANTING.value(), net.minecraft.sound.SoundCategory.PLAYERS, 1.0f, 1.0f);
            }
            return;
        }

        MapConfig config = ConfigManager.getInstance().getMap(session.getMapName());
        String reject = plantRejectReason(session, config, player, true);
        if (reject != null) {
            player.sendMessage(Text.literal(reject), false);
            return;
        }
        if (!uuid.equals(session.getPlantingPlayer())) {
            session.setPlantingPlayer(uuid);
            session.setPlantProgress(0);
            // 开始安放 → 播放安放进行音（键入声）
            player.getWorld().playSound(null, player.getBlockPos(),
                    ModSounds.C4_PLANTING.value(), net.minecraft.sound.SoundCategory.PLAYERS, 1.0f, 1.0f);
        }
    }

    /**
     * 【作用】设置/取消拆除状态（客户端靠近 C4 长按使用键状态翻转时调用）：
     *         start 时校验对局/阵营/炸弹范围，条件不满足发提示；stop 清空进度。
     *         对局外（自由安放模式）可拆除附近的自由炸弹。
     * 【被谁使用】NetworkHandler 的 DEFUSE_BOMB 分支。仅服务端。
     */
    public void setDefusing(ServerPlayerEntity player, boolean start) {
        MatchSession session = MatchManager.getInstance().getPlayerSession(player.getUuid());
        UUID uuid = player.getUuid();

        if (!start) {
            freeDefuseProgress.remove(uuid);
            if (session != null && uuid.equals(session.getDefusingPlayer())) {
                session.setDefusingPlayer(null);
                session.setDefuseProgress(0);
            }
            return;
        }

        // 对局外：仅自由安放模式允许拆除附近的自由炸弹（matchId 为空的实体）
        if (session == null || !session.isBombMode()) {
            if (!freePlantingAllowed) return;
            if (player.isDead()) return;
            if (findFreeBombNear(player) == null) return;
            // 首次开始拆除 → 播放拆除进行音（滴答）
            if (freeDefuseProgress.putIfAbsent(uuid, 0) == null) {
                player.getWorld().playSound(null, player.getBlockPos(),
                        ModSounds.C4_DEFUSE.value(), net.minecraft.sound.SoundCategory.PLAYERS, 1.0f, 1.0f);
            }
            return;
        }

        MapConfig config = ConfigManager.getInstance().getMap(session.getMapName());
        String reject = defuseRejectReason(session, config, player);
        if (reject != null) {
            player.sendMessage(Text.literal(reject), false);
            return;
        }
        if (!uuid.equals(session.getDefusingPlayer())) {
            session.setDefusingPlayer(uuid);
            session.setDefuseProgress(0);
            // 开始拆除 → 播放拆除进行音（滴答）
            player.getWorld().playSound(null, player.getBlockPos(),
                    ModSounds.C4_DEFUSE.value(), net.minecraft.sound.SoundCategory.PLAYERS, 1.0f, 1.0f);
        }
    }

    // ==================== 每 tick 推进 ====================

    /**
     * 【作用】每服务器 tick 推进：①对局外自由安放/拆除进度（仅自由模式开关开启时有内容）；
     *         ②全部活跃爆破对局的安放/拆除进度——常规规则下仅战斗阶段推进（冻结/间歇/
     *         已结束跳过），自由安放开关开启时放宽为仅排除已结束对局。
     * 【被谁使用】MatchScheduler#onTick。仅服务端。
     */
    public void tick(MinecraftServer server) {
        if (server == null) return;
        tickFreePlanting(server);
        tickFreeDefusing(server);
        for (MatchSession session : MatchManager.getInstance().getAllActiveSessions()) {
            if (!session.isBombMode() || session.getPhase() == MatchSession.GamePhase.ENDED) continue;
            if (!freePlantingAllowed && (session.getPhase() != MatchSession.GamePhase.FIGHTING
                    || session.getRoundEndCounter() > 0)) continue;
            MapConfig config = ConfigManager.getInstance().getMap(session.getMapName());
            if (config == null || !config.isBombMap()) continue;
            tickBombBeep(session);
            tickPlanting(server, session, config);
            tickDefusing(server, session, config);
        }
    }

    /** 对局外自由安放推进：玩家需持续存活且 C4 仍在背包，满 plantTimeSeconds 生成自由炸弹实体。 */
    private void tickFreePlanting(MinecraftServer server) {
        if (freePlantProgress.isEmpty()) return;
        MapConfig.BombSettings settings = defaultBombSettings();
        int totalTicks = settings.getPlantTimeSeconds() * 20;
        for (var it = freePlantProgress.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(entry.getKey());
            // 玩家离线/死亡/C4 离身（切手持不算）→ 中断
            if (player == null || player.isDead() || !containsC4(player)) {
                it.remove();
                continue;
            }
            int progress = entry.getValue() + 1;
            entry.setValue(progress);
            if (progress % 10 == 0) {
                player.sendMessage(Text.literal("§e安放中 " + progressBar(progress, totalTicks)), true);
            }
            if (progress >= totalTicks) {
                it.remove();
                spawnFreeBomb(player, settings);
            }
        }
    }

    /** 对局外自由拆除推进：玩家需持续靠近自由炸弹，满拆除时间后拆除成功。 */
    private void tickFreeDefusing(MinecraftServer server) {
        if (freeDefuseProgress.isEmpty()) return;
        MapConfig.BombSettings settings = defaultBombSettings();
        for (var it = freeDefuseProgress.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(entry.getKey());
            BombEntity bomb = player == null ? null : findFreeBombNear(player);
            // 玩家离线/死亡/离开炸弹范围 → 中断
            if (player == null || player.isDead() || bomb == null) {
                it.remove();
                continue;
            }
            boolean hasKit = hasDefuseKitItem(player);
            int totalTicks = (hasKit ? settings.getDefuseKitSeconds() : settings.getDefuseSeconds()) * 20;
            int progress = entry.getValue() + 1;
            entry.setValue(progress);
            if (progress % 10 == 0) {
                player.sendMessage(Text.literal(
                        "§9" + (hasKit ? "带装备拆除中 " : "裸拆中 ") + progressBar(progress, totalTicks)), true);
            }
            if (progress >= totalTicks) {
                it.remove();
                // 拆除成功：实体保留 10 秒后自动消失（不再立即清除）
                bomb.beginDespawn(10);
                // 拆除成功确认音（上行三音阶）
                player.getWorld().playSound(null, player.getBlockPos(),
                        ModSounds.C4_DEFUSED.value(), net.minecraft.sound.SoundCategory.PLAYERS, 1.2f, 1.0f);
                player.sendMessage(Text.literal("§a拆除成功！"), true);
            }
        }
    }

    /** 对局外默认炸弹参数（无地图配置可取，用 CS 标准默认值）。 */
    private MapConfig.BombSettings defaultBombSettings() {
        return new MapConfig.BombSettings();
    }

    /** 生成自由炸弹实体（matchId 留空 = 自行倒计时自爆；倒计时取默认 c4TimerSeconds）。 */
    private void spawnFreeBomb(ServerPlayerEntity player, MapConfig.BombSettings settings) {
        BlockPos pos = player.getBlockPos();
        ServerWorld world = player.getServerWorld();
        BombEntity bomb = new BombEntity(ModEntities.C4, world);
        bomb.setPosition(pos.getX() + 0.5, pos.getY() + 0.2, pos.getZ() + 0.5);
        bomb.setMatchId(""); // 自由炸弹：无对局归属
        bomb.setFuseTicks(settings.getC4TimerSeconds() * 20);
        world.spawnEntity(bomb);
        // 安放完成确认音（双响上行）
        world.playSound(null, bomb.getX(), bomb.getY(), bomb.getZ(),
                ModSounds.C4_PLANT.value(), net.minecraft.sound.SoundCategory.BLOCKS, 1.5f, 1.0f);

        consumeOneC4(player);
        player.sendMessage(Text.literal("§aC4 安放成功！" + settings.getC4TimerSeconds() + " 秒后爆炸"), true);
        Cstmm.LOGGER.info("[CSTMM - BombInteraction] Free C4 planted at {} by {}",
                pos.toShortString(), player.getName());
    }

    /** 取玩家附近（拆除距离内）可拆除的自由炸弹（matchId 为空且未被拆除的实体；无返回 null）。 */
    private BombEntity findFreeBombNear(ServerPlayerEntity player) {
        Box box = player.getBoundingBox().expand(DEFUSE_RANGE);
        List<BombEntity> bombs = player.getWorld().getEntitiesByClass(BombEntity.class, box,
                b -> b.isFree() && !b.isDefused());
        return bombs.isEmpty() ? null : bombs.get(0);
    }

    /** 安放进度推进：逐 tick 校验条件并累计，满时 plantBomb。 */
    private void tickPlanting(MinecraftServer server, MatchSession session, MapConfig config) {
        UUID uuid = session.getPlantingPlayer();
        if (uuid == null) return;
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
        String reject = plantRejectReason(session, config, player, false);
        if (reject != null) {
            cancelPlanting(session, player, reject);
            return;
        }

        int totalTicks = config.getBombSettings().getPlantTimeSeconds() * 20;
        int progress = session.getPlantProgress() + 1;
        session.setPlantProgress(progress);
        if (progress % 10 == 0) {
            player.sendMessage(Text.literal("§e安放中 " + progressBar(progress, totalTicks)), true);
        }
        if (progress >= totalTicks) {
            plantBomb(server, session, config, player);
        }
    }

    /** 拆除进度推进：逐 tick 校验条件并累计，满时 defuseBomb。 */
    private void tickDefusing(MinecraftServer server, MatchSession session, MapConfig config) {
        UUID uuid = session.getDefusingPlayer();
        if (uuid == null) return;
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
        String reject = defuseRejectReason(session, config, player);
        if (reject != null) {
            cancelDefusing(session, player, reject);
            return;
        }

        // 拆除时间：持拆弹装备（玩家 tag cstmm_defuse_kit）用 defuseKitSeconds，否则 defuseSeconds
        boolean hasKit = hasDefuseKitItem(player);
        int totalTicks = (hasKit ? config.getBombSettings().getDefuseKitSeconds()
                : config.getBombSettings().getDefuseSeconds()) * 20;
        int progress = session.getDefuseProgress() + 1;
        session.setDefuseProgress(progress);
        if (progress % 10 == 0) {
            player.sendMessage(Text.literal(
                    "§9" + (hasKit ? "带装备拆除中 " : "裸拆中 ") + progressBar(progress, totalTicks)), true);
        }
        if (progress >= totalTicks) {
            defuseBomb(server, session, config, player);
        }
    }

    // ==================== 安放 / 拆除成功 ====================

    /** 安放成功：移除手中 C4 → 会话标记 → 生成 C4 实体 → 个人奖励 + 全员广播。 */
    private void plantBomb(MinecraftServer server, MatchSession session, MapConfig config, ServerPlayerEntity player) {
        MapConfig.Bombsite site = findSiteIn(session, config, player);
        session.setPlantingPlayer(null);
        session.setPlantProgress(0);
        if (site == null && !freePlantingAllowed) return; // 并发兜底（tick 内已校验）

        session.setBombPlanted(true);
        session.setBombEverPlantedThisRound(true);
        session.setBombTimerSeconds(config.getBombSettings().getC4TimerSeconds());
        // 自由安放（不在包点内）时无包点名，plantedSite 留空
        session.setPlantedSite(site != null ? site.getName() : "");
        BlockPos pos = player.getBlockPos();
        session.setPlantedPos(new int[]{pos.getX(), pos.getY(), pos.getZ()});
        session.setBombCarrier(null);

        // 移除背包中的 C4 物品（安放后实体接管可视化；进行中允许切换手持，故按背包查找扣除）
        consumeOneC4(player);

        // 生成 C4 实体（绿色长方体，占位建模）挂接所属对局；
        // 自由安放模式允许重复安放——先清掉本局旧实体避免残留
        discardBombs(server, session);
        ServerWorld world = player.getServerWorld();
        BombEntity bomb = new BombEntity(ModEntities.C4, world);
        bomb.setPosition(pos.getX() + 0.5, pos.getY() + 0.2, pos.getZ() + 0.5);
        bomb.setMatchId(session.getSessionId());
        world.spawnEntity(bomb);
        // 安放完成确认音（双响上行）
        world.playSound(null, bomb.getX(), bomb.getY(), bomb.getZ(),
                ModSounds.C4_PLANT.value(), net.minecraft.sound.SoundCategory.BLOCKS, 1.5f, 1.0f);

        // 个人安放奖励（战败也保留）
        EconomyManager.getInstance().awardPlant(session, player.getUuid());
        // 订阅制商店推送：安放奖励改钱（同时是购买窗口关闭的兜底信号）
        cn.woshiikun_1145.mcmod.choco.cstmm.network.NetworkHandler.sendShopData(player, session);

        player.sendMessage(Text.literal("§aC4 安放成功！" + config.getBombSettings().getC4TimerSeconds()
                + " 秒后爆炸"), true);
        // 广播不含包点位置：CT 需自行寻找炸弹（与 HUD 状态行口径一致）
        NetworkHandler.broadcastToMatch(session,
                "§c*** 炸弹已安放！"
                        + config.getBombSettings().getC4TimerSeconds() + " 秒后爆炸！CT 方速去拆除！ ***");
        Cstmm.LOGGER.info("[CSTMM - BombInteraction] C4 planted at site {} in match {} by {}",
                site != null ? site.getName() : "自由安放", session.getSessionId(), player.getName());
    }

    /** 拆除成功：会话清理 → 清实体 → 个人拆除奖励 → CT 赢得回合（目标金档）。 */
    private void defuseBomb(MinecraftServer server, MatchSession session, MapConfig config, ServerPlayerEntity player) {
        session.setDefusingPlayer(null);
        session.setDefuseProgress(0);
        session.setBombPlanted(false);
        // 拆除成功确认音（上行三音阶，全体可闻）
        server.getOverworld().playSound(null, player.getBlockPos(),
                ModSounds.C4_DEFUSED.value(), net.minecraft.sound.SoundCategory.PLAYERS, 2.0f, 1.0f);
        // 炸弹被拆除语音播报（与确认音不同，非定位）：仅对局内玩家单播可闻；
        // CT 回合胜利音效在本播报响起 1 秒后由 endRound 延迟播放
        cn.woshiikun_1145.mcmod.choco.cstmm.sound.MatchSoundPlayer.playToMatch(
                session, ModSounds.C4_DEFUSED_ANNOUNCE.value(), 1.0f, 1.0f);
        EconomyManager.getInstance().awardDefuse(session, player.getUuid());
        // 订阅制商店推送：拆除奖励改钱
        cn.woshiikun_1145.mcmod.choco.cstmm.network.NetworkHandler.sendShopData(player, session);
        // 对局内炸弹拆除后不立即消失：保留原地直到下一回合开始（startRound 的 discardBombs 清理）

        player.sendMessage(Text.literal("§a拆除成功！"), true);
        // endRound 内部广播回合结果并做经济结算（reason 含"拆除"→ 目标金档位）
        BombRoundManager.getInstance().endRound(session, config,
                session.getOppositeTeam(session.getTTeam()), "炸弹已被拆除");
    }

    // ==================== 条件校验 ====================

    /**
     * 安放条件校验（start 请求与逐 tick 复用）：返回 null = 条件满足，否则返回拒绝/中断原因。
     * starting=true（开始安放）要求主手手持 C4；starting=false（进行中）只要求 C4 仍在背包内，
     * 因此进行中切换手持不中断（C4 被丢弃/移出背包才中断）。
     */
    private String plantRejectReason(MatchSession session, MapConfig config, ServerPlayerEntity player,
                                     boolean starting) {
        // debug 开关（/cstmm debug allow_c4 to_be_planted_freely t）开启时允许"任何情况下安放"：
        // 跳过阵营/包点/阶段/重复安放等全部情境限制，仅保留存活与手持 C4 两个操作前提
        if (freePlantingAllowed) {
            if (player == null || player.isDead()) return "§c安放中断";
            if (starting ? !player.getMainHandStack().isOf(ModItems.C4) : !containsC4(player)) {
                return "§c需要手持 C4 才能安放";
            }
            return null;
        }
        if (player == null || player.isDead()
                || session.getPhase() != MatchSession.GamePhase.FIGHTING
                || session.getRoundEndCounter() > 0) {
            return "§c安放中断";
        }
        if (session.isBombPlanted()) return "§c炸弹已安放，无法重复安放";
        if (!session.isTTeam(session.getPlayerTeam(player.getUuid()))) return "§c只有 T 方能安放 C4";
        if (starting ? !player.getMainHandStack().isOf(ModItems.C4) : !containsC4(player)) {
            return "§c需要手持 C4 才能安放";
        }
        if (findSiteIn(session, config, player) == null) return "§c安放中断（需站在包点内）";
        return null;
    }

    /** 拆除条件校验（start 请求与逐 tick 复用）：返回 null = 条件满足，否则返回拒绝/中断原因。 */
    private String defuseRejectReason(MatchSession session, MapConfig config, ServerPlayerEntity player) {
        if (player == null || player.isDead()
                || session.getPhase() != MatchSession.GamePhase.FIGHTING
                || session.getRoundEndCounter() > 0) {
            return "§c拆除中断";
        }
        if (!session.isBombPlanted()) return "§c当前没有已安放的炸弹";
        // C4 引信已点燃（倒计时归零，引信音播放中）：不可拆除，1.5 秒后必然爆炸
        if (BombRoundManager.getInstance().isDetonationPending(session.getSessionId())) {
            return "§c你不能拆除一个正在起爆的C4！";
        }
        if (session.isTTeam(session.getPlayerTeam(player.getUuid()))) return "§c只有 CT 方能拆除炸弹！";
        if (findBombNear(session, player) == null) return "§c拆除中断（你离C4太远了）";
        return null;
    }

    /** 取玩家所在的包点（不在任何包点内返回 null；站在包点立方体范围内即可，无需注视）。 */
    private MapConfig.Bombsite findSiteIn(MatchSession session, MapConfig config, ServerPlayerEntity player) {
        Vec3d pos = player.getPos();
        for (MapConfig.Bombsite site : config.getBombsites()) {
            if (site.contains(pos.x, pos.y, pos.z)) return site;
        }
        return null;
    }

    /** 取玩家附近（拆除距离内）所属本局的 C4 实体（无返回 null）。 */
    private BombEntity findBombNear(MatchSession session, ServerPlayerEntity player) {
        Box box = player.getBoundingBox().expand(DEFUSE_RANGE);
        List<BombEntity> bombs = player.getWorld().getEntitiesByClass(BombEntity.class, box,
                e -> session.getSessionId().equals(e.getMatchId()));
        return bombs.isEmpty() ? null : bombs.get(0);
    }

    // ==================== 清理 ====================

    /**
     * 【作用】清理本局已安放的全部 C4 实体（拆除成功/爆炸/回合重置时调用；
     *         按 matchId 过滤，避免同维度多场爆破对局误删）。
     * 【被谁使用】defuseBomb、BombRoundManager#detonateBomb/startRound。仅服务端。
     */
    public void discardBombs(MinecraftServer server, MatchSession session) {
        ServerWorld world = QueueManager.getWorld();
        if (world == null) return;
        String matchId = session.getSessionId();
        for (Entity entity : world.iterateEntities()) {
            if (entity instanceof BombEntity bomb && matchId.equals(bomb.getMatchId())) {
                bomb.discard();
            }
        }
        // 炸弹消失（拆除/爆炸/回合重置）：哔声计时一并清除
        beepNextAt.remove(matchId);
    }

    // ==================== 工具 ====================

    /** 中断安放并提示玩家（条件校验失败路径）。 */
    private void cancelPlanting(MatchSession session, ServerPlayerEntity player, String reason) {
        session.setPlantingPlayer(null);
        session.setPlantProgress(0);
        if (player != null) {
            player.sendMessage(Text.literal(reason), true);
        }
    }

    /** 中断拆除并提示玩家（条件校验失败路径）。 */
    private void cancelDefusing(MatchSession session, ServerPlayerEntity player, String reason) {
        session.setDefusingPlayer(null);
        session.setDefuseProgress(0);
        if (player != null) {
            player.sendMessage(Text.literal(reason), true);
        }
    }

    /** 进度条文本：已填充 ■ 与空槽 □，总长 10。 */
    private String progressBar(int progress, int total) {
        int filled = total <= 0 ? 10 : Math.min(10, progress * 10 / total);
        return "§a" + "■".repeat(filled) + "§7" + "□".repeat(10 - filled);
    }

    /** 玩家是否持有拆弹装备（tag 判定：cstmm_defuse_kit，非物品）。 */
    private boolean hasDefuseKitItem(ServerPlayerEntity player) {
        return player.getCommandTags().contains(DEFUSE_KIT_TAG);
    }

    /** 玩家背包（含主手/副手/盔甲槽）中是否仍有 C4：进行中允许切换手持，但 C4 不能离身。 */
    private boolean containsC4(ServerPlayerEntity player) {
        PlayerInventory inventory = player.getInventory();
        for (int i = 0; i < inventory.size(); i++) {
            if (inventory.getStack(i).isOf(ModItems.C4)) return true;
        }
        return false;
    }

    /** 安放完成：从背包任意槽位扣除一件 C4（进行中可能已把 C4 切到别的槽位）。 */
    private void consumeOneC4(ServerPlayerEntity player) {
        PlayerInventory inventory = player.getInventory();
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack.isOf(ModItems.C4)) {
                stack.decrement(1);
                return;
            }
        }
    }
}
