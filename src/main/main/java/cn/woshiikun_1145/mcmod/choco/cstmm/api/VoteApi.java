package cn.woshiikun_1145.mcmod.choco.cstmm.api;

import java.util.UUID;

/**
 * 【作用】对局内投票对外 API：发起加时/踢人投票、处理投票、查询投票状态（供外部模组或集成调用）。
 * 【被谁使用】api 包为对外整合入口；服务端由 VoteManager 实现（VoteManager implements VoteApi）。
 */
public interface VoteApi {

    /**
     * 发起加时赛投票（服务端自动调用）。
     * matchId 可为对局 session UUID，或对局内任意玩家的 UUID。
     */
    void startOvertimeVote(UUID matchId);

    /**
     * 发起踢人投票
     */
    void startKickVote(UUID playerUuid, UUID targetUuid);

    /**
     * 处理投票
     */
    void handleVote(UUID playerUuid, boolean agree);

    /**
     * 获取当前投票状态。
     * matchId 可为对局 session UUID，或对局内任意玩家的 UUID。
     */
    VoteStatus getVoteStatus(UUID matchId);

    /** 【作用】当前投票状态快照：类型、被踢目标、赞成/反对票数、所需票数与剩余秒数。 */
    record VoteStatus(
            VoteType type,
            UUID target,
            int yesVotes,
            int noVotes,
            int requiredVotes,
            int remainingSeconds
    ) {}

    // 投票类型：加时赛 / 踢人
    enum VoteType {
        OVERTIME,
        KICK
    }
}