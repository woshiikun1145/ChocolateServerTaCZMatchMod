package cn.woshiikun_1145.mcmod.choco.cstmm.api;

import java.util.UUID;

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

    record VoteStatus(
            VoteType type,
            UUID target,
            int yesVotes,
            int noVotes,
            int requiredVotes,
            int remainingSeconds
    ) {}

    enum VoteType {
        OVERTIME,
        KICK
    }
}