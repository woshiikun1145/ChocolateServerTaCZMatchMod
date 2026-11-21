package cn.woshiikun_1145.mcmod.choco.cstmm.client.cache;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

/**
 * 【作用】客户端磁盘缓存统一目录工具：提供缓存根目录（config/cstmm/client/cache）
 *         与服务器标识（host:port）到安全文件名/目录名的转换。
 *         Windows 文件名禁止冒号，host:port 统一替换非法字符为下划线
 *         （如 mc.example.com:25565 → mc.example.com_25565）。
 * 【被谁使用】ConfigDiskCache（配置缓存文件 config_files/<safeKey>.json）、
 *           BadgeCache（徽标缓存目录 badges/<safeKey>/）。
 */
@Environment(EnvType.CLIENT)
public final class ClientCacheDirs {

    // 单例工具类，禁止实例化
    private ClientCacheDirs() {}

    /** 缓存根目录：<客户端 config>/cstmm/client/cache */
    public static Path cacheRoot() {
        return FabricLoader.getInstance().getConfigDir().resolve("cstmm/client/cache");
    }

    /**
     * 服务器标识 → 安全文件/目录名：非 [A-Za-z0-9._-] 的字符（冒号、IPv6 分隔符等）
     * 统一替换为下划线；防御性处理，避免异常 key 导致路径穿越或写盘失败。
     */
    public static String sanitize(String serverKey) {
        return serverKey.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
