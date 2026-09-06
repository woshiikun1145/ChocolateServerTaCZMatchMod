package cn.woshiikun_1145.mcmod.choco.cstmm.manager;

import cn.woshiikun_1145.mcmod.choco.cstmm.Cstmm;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.config.MapConfig;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.MatchSession;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

import java.util.UUID;

/**
 * 对局边界检测（自 MatchManager 提取，逻辑逐字保留）：
 * 出界施加黑暗效果并倒计时警告，超限处决并给对方队伍罚分。
 */
final class BoundaryChecker {

    private BoundaryChecker() {
    }

    static void checkBoundaries(MatchSession session, MapConfig config) {
        MapConfig.Boundary boundary = config.getBoundary();
        if (boundary == null || !boundary.isConfigured()) return;

        MinecraftServer server = Cstmm.getServer();
        if (server == null) return;

        int warningTimeLimit = config.getBoundaryWarningTime();
        int penaltyKills = config.getBoundaryPenaltyKills();

        for (UUID uuid : session.getAllPlayers()) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
            if (player == null) continue;

            Vec3d pos = player.getPos();
            boolean inside = boundary.contains(pos.x, pos.y, pos.z);

            if (!inside) {
                int warningTime = session.getBoundaryWarningTime(uuid) + 1;
                session.setBoundaryWarningTime(uuid, warningTime);

                player.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
                        net.minecraft.entity.effect.StatusEffects.DARKNESS,
                        40, 0, false, true
                ));

                if (warningTime == 1) {
                    player.sendMessage(
                            Text.literal("§c⚠ 警告：你已离开地图区域！剩余 " + (warningTimeLimit - warningTime) + " 秒"),
                            false
                    );
                } else if (warningTime % 5 == 0 || warningTime >= warningTimeLimit) {
                    player.sendMessage(
                            Text.literal("§c⚠ 警告：你已离开地图区域！剩余 " + (warningTimeLimit - warningTime) + " 秒"),
                            false
                    );
                }

                if (warningTime >= warningTimeLimit) {
                    player.kill();
                    int team = session.getPlayerTeam(uuid);
                    if (team == 1) {
                        session.addBlueKills(penaltyKills);
                    } else if (team == 2) {
                        session.addRedKills(penaltyKills);
                    }
                    PlayerDataManager.getInstance().addPenaltyDeath(uuid);
                    session.resetBoundaryWarningTime(uuid);
                }
            } else {
                session.resetBoundaryWarningTime(uuid);
            }
        }
    }
}
