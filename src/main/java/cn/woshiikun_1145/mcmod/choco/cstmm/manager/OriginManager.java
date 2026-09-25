package cn.woshiikun_1145.mcmod.choco.cstmm.manager;

import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家对局前原点管理（自 MatchManager 嵌套类提取，逻辑逐字保留）。
 * 【作用】在对局开始传送前记录玩家所在坐标与维度，对局结束后把玩家传送回原点原维度，
 *         防止玩家被永久滞留在竞技地图。纯内存存储（不落盘）。
 * 【被谁使用】MatchManager（开局传送前 saveOrigin；对局结束/离线/被踢时 restoreOrigin）、
 *           EventListener（玩家在对局中断线时 restoreOrigin）。仅服务端。
 */
public final class OriginManager {
    /** 玩家进对局前的坐标，key: 玩家 UUID */
    private static final Map<UUID, Vec3d> origins = new ConcurrentHashMap<>();
    /** 玩家进对局前的维度，key: 玩家 UUID */
    private static final Map<UUID, RegistryKey<World>> originDimensions = new ConcurrentHashMap<>();

    private OriginManager() {
    }

    /**
     * 【作用】记录玩家当前坐标与维度（传送进竞技地图前调用）。
     * 【被谁使用】MatchManager（对局开始把玩家传送入地图之前）。
     */
    public static void saveOrigin(ServerPlayerEntity player) {
        origins.put(player.getUuid(), player.getPos());
        originDimensions.put(player.getUuid(), player.getServerWorld().getRegistryKey());
    }

    /**
     * 【作用】把玩家传送回对局前保存的坐标（取走并清除记录）；无记录时不做任何事。
     * 【被谁使用】MatchManager（对局结束、玩家离线、被投票踢出时）、
     *           EventListener（玩家在对局中断线时兜底恢复）。
     */
    public static void restoreOrigin(ServerPlayerEntity player) {
        Vec3d origin = origins.remove(player.getUuid());
        RegistryKey<World> dim = originDimensions.remove(player.getUuid());
        if (origin != null) {
            // 原点必须恢复到保存时的维度，跨维度玩家不能被传送回当前世界的相同坐标
            MinecraftServer server = player.getServer();
            ServerWorld world = (server != null && dim != null) ? server.getWorld(dim) : null;
            if (world == null) {
                world = player.getServerWorld();
            }
            player.teleport(world, origin.x, origin.y, origin.z,
                    player.getYaw(), player.getPitch());
        }
    }
}
