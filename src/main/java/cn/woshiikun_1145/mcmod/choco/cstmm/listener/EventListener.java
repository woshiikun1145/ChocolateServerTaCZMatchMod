package cn.woshiikun_1145.mcmod.choco.cstmm.listener;

import cn.woshiikun_1145.mcmod.choco.cstmm.Cstmm;
import cn.woshiikun_1145.mcmod.choco.cstmm.manager.InventoryManager;
import cn.woshiikun_1145.mcmod.choco.cstmm.manager.MatchManager;
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

public class EventListener {

    private static boolean registered = false;

    public static void register() {
        if (registered) return;
        registered = true;

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            UUID uuid = player.getUuid();

            Cstmm.LOGGER.debug("[CSTMM - EventListener] Player joined: {}", player.getName());

            PlayerDataManager.getInstance().ensureProfile(player);

            // 各步骤独立隔离：某一步抛异常不影响后续步骤（尤其背包恢复）
            try {
                NetworkHandler.sendConfigSync(player);
            } catch (Exception e) {
                Cstmm.LOGGER.error("[CSTMM - EventListener] Failed to send config sync to {}", player.getName(), e);
            }

            try {
                NetworkHandler.sendPlayerProfile(player);
            } catch (Exception e) {
                Cstmm.LOGGER.error("[CSTMM - EventListener] Failed to send player profile to {}", player.getName(), e);
            }

            try {
                if (InventoryManager.getInstance().hasSavedInventory(uuid)) {
                    MatchSession session = MatchManager.getInstance().getPlayerSession(uuid);
                    if (session != null && session.getPhase() != MatchSession.GamePhase.ENDED) {
                        player.sendMessage(Text.literal("§e你已重新加入对局！"), false);
                    } else {
                        OriginManager.restoreOrigin(player);
                        InventoryManager.getInstance().restoreInventory(player);
                        player.sendMessage(Text.literal("§a已恢复你的背包"), false);
                    }
                }
            } catch (Exception e) {
                Cstmm.LOGGER.error("[CSTMM - EventListener] Failed to restore inventory for {}", player.getName(), e);
            }
        });

        // 玩家在对局中死亡重生后，只要对局未结束就传送回其队伍的配置出生点
        ServerPlayerEvents.AFTER_RESPAWN.register((oldEntity, newEntity, alive) -> {
            MatchManager.getInstance().handleRespawn(newEntity);
        });

        // 休闲模式禁用友伤：同一活跃对局中同队玩家之间的伤害取消；
        // 竞技模式不拦截（含友伤），对局外玩家的正常 PVP 不受影响
        // 模式按对局会话判定（地图配置已无模式）
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (!(entity instanceof ServerPlayerEntity victim)) return true;
            if (!(source.getAttacker() instanceof ServerPlayerEntity attacker)) return true;

            MatchSession session = MatchManager.getInstance().getPlayerSession(victim.getUuid());
            if (session == null || session.getPhase() == MatchSession.GamePhase.ENDED) return true;
            // 攻击者必须与受害者处于同一对局
            if (MatchManager.getInstance().getPlayerSession(attacker.getUuid()) != session) return true;

            if (session.isCompetitive()) return true;

            // 同队（含队伍值同为 0）时取消伤害
            return session.getPlayerTeam(victim.getUuid()) != session.getPlayerTeam(attacker.getUuid());
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            UUID uuid = player.getUuid();

            Cstmm.LOGGER.debug("[CSTMM - EventListener] Player disconnected: {}", player.getName());

            MatchSession session = MatchManager.getInstance().getPlayerSession(uuid);
            if (session != null && session.getPhase() != MatchSession.GamePhase.ENDED) {
                MatchManager.getInstance().onPlayerDisconnect(uuid);
            } else {
                QueueManager.getInstance().leaveQueue(player);
                if (InventoryManager.getInstance().hasSavedInventory(uuid)) {
                    InventoryManager.getInstance().restoreInventory(player);
                }
            }
        });

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            if (!(entity instanceof ServerPlayerEntity killed)) return;

            LivingEntity attacker = null;
            DamageSource source = damageSource;
            if (source.getAttacker() instanceof LivingEntity living) {
                attacker = living;
            }

            if (!(attacker instanceof ServerPlayerEntity killer)) {
                return;
            }

            UUID killerUuid = killer.getUuid();
            UUID killedUuid = killed.getUuid();

            MatchSession session = MatchManager.getInstance().getPlayerSession(killerUuid);
            if (session == null) {
                return;
            }

            if (!session.isPlayerInGame(killedUuid)) {
                return;
            }

            int killerTeam = session.getPlayerTeam(killerUuid);
            int killedTeam = session.getPlayerTeam(killedUuid);
            if (killerTeam == killedTeam) {
                return;
            }

            // 观战者击杀不计数、不播报
            if (killerTeam == 0) {
                return;
            }

            if (killerTeam == 1) {
                session.addRedKills(1);
            } else if (killerTeam == 2) {
                session.addBlueKills(1);
            }

            PlayerDataManager.getInstance().addKill(killerUuid);
            PlayerDataManager.getInstance().addDeath(killedUuid);

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
        });

        // SERVER_STOPPING 保存逻辑已在 Cstmm 中注册，此处不再重复注册

        Cstmm.LOGGER.info("[CSTMM - EventListener] Registered all event listeners");
    }
}