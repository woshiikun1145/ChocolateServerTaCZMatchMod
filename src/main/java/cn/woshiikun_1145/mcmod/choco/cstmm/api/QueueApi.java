package cn.woshiikun_1145.mcmod.choco.cstmm.api;

import java.util.List;
import java.util.UUID;

/**
 * 【作用】匹配队列对外 API：查询/加入/离开队列等服务端匹配能力（供外部模组或集成调用）。
 * 【被谁使用】api 包为对外整合入口；服务端由 QueueManager 实现（QueueManager implements QueueApi）。
 */
public interface QueueApi {

    /**
     * 获取某地图的队列状态（排队人数；队伍在对局人数满足、准备开局时才由系统自动分配）
     */
    QueueStatus getQueueStatus(String mapName);

    /**
     * 获取所有地图的队列状态
     */
    List<QueueStatus> getAllQueueStatus();

    /**
     * 玩家加入队列（team 参数已废弃：队伍在开局时自动分配，保留参数仅为兼容旧签名）
     */
    void joinQueue(UUID playerUuid, String mapName, int team);

    /**
     * 玩家离开队列
     */
    void leaveQueue(UUID playerUuid);

    /**
     * 玩家是否在队列中
     */
    boolean isInQueue(UUID playerUuid);

    /** 【作用】单张地图的队列状态快照：地图 id、当前排队人数、地图是否可用（启用且非冷却）。 */
    record QueueStatus(
            String mapName,
            int playerCount,
            boolean isMapAvailable
    ) {}
}