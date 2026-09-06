package cn.woshiikun_1145.mcmod.choco.cstmm.api;

import java.util.List;
import java.util.UUID;

public interface QueueApi {

    /**
     * 获取某地图的队列人数
     */
    QueueStatus getQueueStatus(String mapName);

    /**
     * 获取所有地图的队列状态
     */
    List<QueueStatus> getAllQueueStatus();

    /**
     * 玩家加入队列
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

    record QueueStatus(
            String mapName,
            int redCount,
            int blueCount,
            boolean isMapAvailable
    ) {}
}