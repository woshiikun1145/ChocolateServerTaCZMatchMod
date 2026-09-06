package cn.woshiikun_1145.mcmod.choco.cstmm.api;

import java.util.UUID;

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

    record MatchStatus(
            String mapName,
            int remainingSeconds,
            int redKills,
            int blueKills,
            boolean isPreparing,
            boolean isFighting
    ) {}

    record KillsData(
            int redKills,
            int blueKills,
            int playerKills,
            int playerDeaths
    ) {}
}