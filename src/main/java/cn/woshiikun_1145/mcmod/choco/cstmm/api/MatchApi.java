package cn.woshiikun_1145.mcmod.choco.cstmm.api;

import java.util.UUID;

/**
 * 【作用】对局状态对外 API：查询玩家当前对局/击杀数据等只读信息（供外部模组或集成调用，如自制 HUD）。
 * 【被谁使用】api 包为对外整合入口；服务端由 MatchManager 实现（MatchManager implements MatchApi），
 * ModCommands/EventListener 等也经由 MatchManager 使用对应能力。
 */
public interface MatchApi {

    /**
     * 获取当前对局状态（供 HUD 使用）
     */
    MatchStatus getCurrentMatchStatus(UUID playerUuid);

    /**
     * 获取玩家是否在对局中
     */
    boolean isInGame(UUID playerUuid);

    /**
     * 获取玩家所在对局的击杀数
     */
    KillsData getKillsData(UUID playerUuid);

    /** 【作用】玩家当前对局状态快照：地图、剩余秒数、双方击杀数、是否准备/战斗阶段。 */
    record MatchStatus(
            String mapName,
            int remainingSeconds,
            int redKills,
            int blueKills,
            boolean isPreparing,
            boolean isFighting
    ) {}

    /** 【作用】击杀数据快照：双方队伍总击杀与该玩家个人击杀/死亡数。 */
    record KillsData(
            int redKills,
            int blueKills,
            int playerKills,
            int playerDeaths
    ) {}
}