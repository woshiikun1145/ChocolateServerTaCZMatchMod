package cn.woshiikun_1145.mcmod.choco.cstmm.listener;

import cn.woshiikun_1145.mcmod.choco.cstmm.Cstmm;
import cn.woshiikun_1145.mcmod.choco.cstmm.manager.InventoryManager;
import cn.woshiikun_1145.mcmod.choco.cstmm.manager.MatchManager;
import cn.woshiikun_1145.mcmod.choco.cstmm.manager.ModGuardian;
import cn.woshiikun_1145.mcmod.choco.cstmm.manager.OriginManager;
import cn.woshiikun_1145.mcmod.choco.cstmm.manager.PlayerDataManager;
import cn.woshiikun_1145.mcmod.choco.cstmm.manager.QueueManager;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.MatchSession;
import cn.woshiikun_1145.mcmod.choco.cstmm.network.NetworkHandler;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.UUID;

/**
 * 【作用】服务端事件监听中枢：玩家加入（建档、同步配置/档案、恢复背包）、复活回传出生点、休闲模式友伤拦截、断线退场处理、对局内击杀计分与播报。
 * 【被谁使用】Cstmm#onInitialize（服务端启动时调用 register）。
 */
public class EventListener {

    // 防重复注册标志（register 幂等）
    private static boolean registered = false;

    /**
     * 【作用】注册全部服务端事件监听（幂等）：JOIN 同步与恢复、AFTER_RESPAWN 回传、ALLOW_DAMAGE 友伤拦截、DISCONNECT 退场清理、AFTER_DEATH 击杀计分播报。
     * 【被谁使用】Cstmm#onInitialize（服务端启动）。
     */
    public static void register() {
        if (registered) return;
        registered = true;

        // 【作用】玩家加入：建档 → 同步配置与档案 → 中断对局重连提示或恢复原点与背包（各步骤独立隔离，异常互不影响）
        //         经 ModGuardian.run 包装：异常进入保护模式而非击穿崩溃；保护模式下本监听器整体跳过
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> ModGuardian.run("玩家加入事件", () -> {
            ServerPlayerEntity player = handler.getPlayer();
            UUID uuid = player.getUuid();

            Cstmm.LOGGER.debug("[CSTMM - EventListener] Player joined: {}", player.getName());

            PlayerDataManager.getInstance().ensureProfile(player);

            // 各步骤独立隔离：某一步抛异常不影响后续步骤（尤其背包恢复）
            // 配置同步已由 NetworkHandler 的 JOIN 回调做哈希握手（先发哈希元数据，
            // 客户端磁盘缓存一致则免收全量），此处不再全量推送

            try {
                NetworkHandler.sendPlayerProfile(player);
            } catch (Exception e) {
                Cstmm.LOGGER.error("[CSTMM - EventListener] Failed to send player profile to {}", player.getName(), e);
            }

            try {
                // 有保存背包时区分三种情况：对局中断线重连仅提示；对局外恢复原点与背包；
                // 仍在死亡界面（对局结束于其死亡期间，结算已跳过恢复）则待重生补恢复，
                // 此处不得向即将被重生丢弃的死亡实体恢复快照（否则原物品永久丢失）
                if (InventoryManager.getInstance().hasSavedInventory(uuid)) {
                    MatchSession session = MatchManager.getInstance().getPlayerSession(uuid);
                    if (session != null && session.getPhase() != MatchSession.GamePhase.ENDED) {
                        player.sendMessage(Text.literal("§e你已重新加入对局！"), false);
                    } else if (player.isDead()) {
                        player.sendMessage(Text.literal("§e你在对局结束时尚未重生，背包与位置将在重生后自动恢复"), false);
                    } else {
                        OriginManager.restoreOrigin(player);
                        InventoryManager.getInstance().restoreInventory(player);
                        player.sendMessage(Text.literal("§a已恢复你的背包"), false);
                    }
                }
            } catch (Exception e) {
                Cstmm.LOGGER.error("[CSTMM - EventListener] Failed to restore inventory for {}", player.getName(), e);
            }
        }));

        // 玩家在对局中死亡重生后，只要对局未结束就传送回其队伍的配置出生点
        ServerPlayerEvents.AFTER_RESPAWN.register((oldEntity, newEntity, alive) ->
                ModGuardian.run("玩家重生事件", () -> MatchManager.getInstance().handleRespawn(newEntity)));

        // 休闲模式禁用友伤：同一活跃对局中同队玩家之间的伤害取消；
        // 竞技模式不拦截（含友伤），对局外玩家的正常 PVP 不受影响
        // 模式按对局会话判定（地图配置已无模式）
        // 保护模式启用时不再拦截任何伤害（回退原版行为）；本监听器异常进入保护模式
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (ModGuardian.isDisabled()) return true;
            try {
                if (!(entity instanceof ServerPlayerEntity victim)) return true;
                if (!(source.getAttacker() instanceof ServerPlayerEntity attacker)) return true;

                MatchSession session = MatchManager.getInstance().getPlayerSession(victim.getUuid());
                if (session == null || session.getPhase() == MatchSession.GamePhase.ENDED) return true;
                // 攻击者必须与受害者处于同一对局
                if (MatchManager.getInstance().getPlayerSession(attacker.getUuid()) != session) return true;

                if (session.isCompetitive()) return true;

                // 同队时取消伤害
                return session.getPlayerTeam(victim.getUuid()) != session.getPlayerTeam(attacker.getUuid());
            } catch (Throwable t) {
                ModGuardian.engage("友伤拦截判定", t);
                return true; // 保护模式下回退原版伤害行为
            }
        });

        // 【作用】玩家断线：对局中断线交 MatchManager 处理（保留会话等待重连/结算），否则退出队列并恢复已保存的背包
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                ModGuardian.run("玩家断线事件", () -> {
                    ServerPlayerEntity player = handler.getPlayer();
                    UUID uuid = player.getUuid();

                    Cstmm.LOGGER.debug("[CSTMM - EventListener] Player disconnected: {}", player.getName());

                    MatchSession session = MatchManager.getInstance().getPlayerSession(uuid);
                    if (session != null && session.getPhase() != MatchSession.GamePhase.ENDED) {
                        MatchManager.getInstance().onPlayerDisconnect(uuid);
                    } else {
                        QueueManager.getInstance().leaveQueue(player);
                        // 死亡状态断线（对局已结束于其死亡期间）：跳过恢复，快照保留待重生补恢复
                        // （向即将丢弃的死亡实体恢复会消费快照导致原物品丢失）
                        if (InventoryManager.getInstance().hasSavedInventory(uuid) && !player.isDead()) {
                            InventoryManager.getInstance().restoreInventory(player);
                        }
                    }
                }));

        // 【作用】玩家死亡播报：击杀者为玩家、双方同处一个活跃对局且异队时，累计队伍比分、更新双方 K/D 档案并向对局全员广播击杀消息
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) ->
                ModGuardian.run("玩家死亡事件", () -> {
            if (!(entity instanceof ServerPlayerEntity killed)) return;

            // 【作用】对局内玩家死亡即记个人死亡并冻结上一条命击杀（无论死因：
            //         玩家击杀/环境死/越界处决），供 HUD 花名册 K/D 与击杀徽章使用
            MatchSession killedSession = MatchManager.getInstance().getPlayerSession(killed.getUuid());
            if (killedSession != null && killedSession.isPlayerInGame(killed.getUuid())
                    && killedSession.getPhase() != MatchSession.GamePhase.ENDED) {
                killedSession.recordDeath(killed.getUuid());
            }

            LivingEntity attacker = null;
            DamageSource source = damageSource;
            if (source.getAttacker() instanceof LivingEntity living) {
                attacker = living;
            }

            // 击杀者不是玩家（摔落/怪物/环境伤害等）则不处理
            if (!(attacker instanceof ServerPlayerEntity killer)) {
                return;
            }

            UUID killerUuid = killer.getUuid();
            UUID killedUuid = killed.getUuid();

            MatchSession session = MatchManager.getInstance().getPlayerSession(killerUuid);
            // 击杀者不在任何对局中 → 不计分不播报
            if (session == null) {
                return;
            }

            // 被击杀者不属于击杀者所在对局（如对局外玩家被击杀）→ 忽略
            if (!session.isPlayerInGame(killedUuid)) {
                return;
            }

            // 同队击杀（友伤）不计分不播报
            int killerTeam = session.getPlayerTeam(killerUuid);
            int killedTeam = session.getPlayerTeam(killedUuid);
            if (killerTeam == killedTeam) {
                return;
            }

            // 按击杀者队伍累计红/蓝比分
            if (killerTeam == 1) {
                session.addRedKills(1);
            } else if (killerTeam == 2) {
                session.addBlueKills(1);
            }

            // 【作用】记击杀者个人击杀（本局累计 + 当前生命计数，HUD 花名册/徽章数据源）
            session.recordKill(killerUuid);

            // 更新击杀者/被击杀者的档案 K/D 统计（持久化由 PlayerDataManager 负责）
            PlayerDataManager.getInstance().addKill(killerUuid);
            PlayerDataManager.getInstance().addDeath(killedUuid);

            // 向对局内全体在线玩家广播击杀消息（含最新比分）
            var server = killer.getServer();
            if (server == null) return;
            String message = String.format("§c%s §7击杀了 §9%s §7! (§c%d §7- §9%d§7)",
                    killer.getName().getString(),
                    killed.getName().getString(),
                    session.getRedKills(),
                    session.getBlueKills()
            );
            for (UUID uuid : session.getAllPlayers()) {
                ServerPlayerEntity p = server.getPlayerManager().getPlayer(uuid);
                if (p != null) {
                    p.sendMessage(Text.literal(message), false);
                }
            }

            Cstmm.LOGGER.debug("[CSTMM - EventListener] {} killed {} ({} - {})",
                    killer.getName(), killed.getName(),
                    session.getRedKills(), session.getBlueKills());
        }));

        // SERVER_STOPPING 保存逻辑已在 Cstmm 中注册，此处不再重复注册

        Cstmm.LOGGER.info("[CSTMM - EventListener] Registered all event listeners");
    }
}