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
 */
public final class OriginManager {
    private static final Map<UUID, Vec3d> origins = new ConcurrentHashMap<>();
    private static final Map<UUID, RegistryKey<World>> originDimensions = new ConcurrentHashMap<>();

    private OriginManager() {
    }

    public static void saveOrigin(ServerPlayerEntity player) {
        origins.put(player.getUuid(), player.getPos());
        originDimensions.put(player.getUuid(), player.getServerWorld().getRegistryKey());
    }

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
