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
 * 【作用】每秒检查对局内所有玩家是否在队伍生效的地图边界内（红蓝两队分别判定：
 *         优先用各自的 redBoundary/blueBoundary，未配置则回退公共 boundary）：出界累计警告秒数
 *         并附加黑暗效果、周期性发倒计时警告，达到上限（boundaryWarningTime）处决玩家、
 *         给对方队伍加罚分击杀并记惩罚死亡；回到界内则清零警告计时。
 * 【被谁使用】MatchManager（对局进行中每秒 tick 调用 checkBoundaries）。
 *           包私有工具类，仅服务端使用。
 */
final class BoundaryChecker {

    private BoundaryChecker() {
    }

    /**
     * 【作用】对单场对局执行一次边界检测（每秒一次，与倒计时 tick 同步）。
     * 【被谁使用】MatchManager（对局 tick 中调用）。包私有，仅服务端。
     */
    static void checkBoundaries(MatchSession session, MapConfig config) {
        MinecraftServer server = Cstmm.getServer();
        if (server == null) return;

        // 【作用】每次检测只解析一次两队生效边界（红/蓝队专属边界优先，未配置回退公共边界），
        //         供全体玩家按队复用，避免逐玩家重复执行回退判定与 isConfigured 检查；
        //         两队生效边界都未配置（含公共边界也未配置）时整体跳过，避免空转
        MapConfig.Boundary redBoundary = config.getBoundaryForTeam(1);
        MapConfig.Boundary blueBoundary = config.getBoundaryForTeam(2);
        boolean anyConfigured = (redBoundary != null && redBoundary.isConfigured())
                || (blueBoundary != null && blueBoundary.isConfigured());
        if (!anyConfigured) return;

        int warningTimeLimit = config.getBoundaryWarningTime();
        int penaltyKills = config.getBoundaryPenaltyKills();

        for (UUID uuid : session.getAllPlayers()) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
            if (player == null) continue;

            // 【作用】按玩家队伍取上面已解析的生效边界（队伍未知时回退公共边界）；
            //         生效边界仍未配置（如公共边界也没有）的队伍不做判定
            MapConfig.Boundary boundary = switch (session.getPlayerTeam(uuid)) {
                case 1 -> redBoundary;
                case 2 -> blueBoundary;
                default -> config.getBoundary();
            };
            if (boundary == null || !boundary.isConfigured()) continue;

            Vec3d pos = player.getPos();
            boolean inside = boundary.contains(pos.x, pos.y, pos.z);

            // 【作用】出界处理：累计警告秒数 → 施加黑暗效果 → 周期性倒计时警告 → 超限处决并罚分
            if (!inside) {
                int warningTime = session.getBoundaryWarningTime(uuid) + 1;
                session.setBoundaryWarningTime(uuid, warningTime);

                player.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
                        net.minecraft.entity.effect.StatusEffects.DARKNESS,
                        40, 0, false, true
                ));

                // 【作用】首次出界立即警告，此后每 5 秒及临处决前再警告（文案相同，合并为一个分支）
                if (warningTime == 1 || warningTime % 5 == 0 || warningTime >= warningTimeLimit) {
                    player.sendMessage(
                            Text.literal("§c⚠ 警告：你已离开地图区域！剩余 " + (warningTimeLimit - warningTime) + " 秒"),
                            false
                    );
                }

                // 【作用】超出警告时限：处决玩家、给对方队伍加罚分击杀、记惩罚死亡并清零计时
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
                // 【作用】回到界内：清零该玩家的出界警告计时
                session.resetBoundaryWarningTime(uuid);
            }
        }
    }
}
