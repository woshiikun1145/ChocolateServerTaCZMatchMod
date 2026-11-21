package cn.woshiikun_1145.mcmod.choco.cstmm.client.cache;

import cn.woshiikun_1145.mcmod.choco.cstmm.Cstmm;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 【作用】客户端配置磁盘持久缓存：把服务端下发的核心配置 JSON（{"hash":..,"maps":..,"global":..}）
 *         按服务器标识落盘（config/cstmm/client/cache/config_files/<host_port>.json，
 *         服务器标识经 ClientCacheDirs.sanitize 清洗——Windows 文件名禁止冒号），跨重连/跨启动复用。
 *         哈希握手省带宽的关键一环——重连进服时本地哈希与服务端一致，即可免收全量配置
 *         （含地图背景图 base64 的大 JSON）。缓存文件损坏/缺失静默降级为无缓存（多收一次全量，无功能影响）。
 * 【被谁使用】ClientNetworkHandler：JOIN 时 onJoin 记录服务器标识；收到 ConfigMetaPayload 后
 *           loadHash/loadCoreJson 与服务端哈希比对、命中则用磁盘 JSON 恢复 ConfigDataCache；
 *           收到全量 ConfigSyncPayload 后 store 落盘。
 */
@Environment(EnvType.CLIENT)
public class ConfigDiskCache {

    private static final Gson GSON = new GsonBuilder().create();
    // 当前服务器标识（JOIN 时记录；不同服务器各存一份，避免 A 服缓存污染 B 服）
    private static String serverKey = null;
    // 当前服务器对应的磁盘缓存内容（core JSON 原文）与解析出的哈希；无缓存时为 null/空串
    private static String coreJson = null;
    private static String hash = "";

    // 单例工具类，禁止实例化
    private ConfigDiskCache() {}

    /**
     * 【作用】JOIN 时记录当前服务器标识并重置内存状态；同服重连保留已加载内容避免重复读盘。
     * 【被谁使用】ClientNetworkHandler 的 ClientPlayConnectionEvents.JOIN 回调。
     */
    public static void onJoin(String key) {
        if (key != null && key.equals(serverKey)) return; // 同服重连，保留已加载的缓存
        serverKey = key;
        coreJson = null;
        hash = "";
    }

    /**
     * 【作用】读取磁盘缓存的核心配置哈希（懒加载，首次调用读盘；之后走内存）。
     * 【被谁使用】ClientNetworkHandler（收到 ConfigMetaPayload 后与服务端哈希比对）。
     */
    public static String loadHash() {
        loadIfNeed();
        return hash;
    }

    /**
     * 【作用】读取磁盘缓存的核心配置 JSON 原文（哈希匹配时交由 applyConfigSync 恢复内存缓存）。
     * 【被谁使用】ClientNetworkHandler（哈希匹配分支）。
     */
    public static String loadCoreJson() {
        loadIfNeed();
        return coreJson;
    }

    /**
     * 【作用】收到全量配置后落盘持久化（原子写：先写 .tmp 再替换）；失败仅告警，不影响使用。
     * 【被谁使用】ClientNetworkHandler#applyConfigSync（全量配置应用成功后）。
     */
    public static void store(String newHash, String newCoreJson) {
        if (serverKey == null || newHash == null || newHash.isEmpty() || newCoreJson == null) return;
        coreJson = newCoreJson;
        hash = newHash;
        try {
            Path dir = ensureCacheDir();
            String name = ClientCacheDirs.sanitize(serverKey);
            Path file = dir.resolve(name + ".json");
            Path tmp = dir.resolve(name + ".json.tmp");
            Files.writeString(tmp, newCoreJson, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            Cstmm.LOGGER.warn("[CSTMM - ConfigDiskCache] Failed to store config cache: {}", e.toString());
        }
    }

    // 懒加载：首次访问时读盘并解析哈希；文件缺失/损坏静默降级为无缓存
    private static void loadIfNeed() {
        if (serverKey == null || coreJson != null) return;
        try {
            Path file = ensureCacheDir().resolve(ClientCacheDirs.sanitize(serverKey) + ".json");
            if (!Files.exists(file)) return;
            String json = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject obj = GSON.fromJson(json, JsonObject.class);
            if (obj == null || obj.get("hash") == null || obj.get("hash").isJsonNull()) return;
            coreJson = json;
            hash = obj.get("hash").getAsString();
        } catch (Exception e) {
            // 损坏缓存直接当无缓存处理：多收一次全量配置，功能不受影响
            Cstmm.LOGGER.warn("[CSTMM - ConfigDiskCache] Failed to load config cache, treat as empty: {}", e.toString());
            coreJson = null;
            hash = "";
        }
    }

    // 获取/创建缓存目录：<缓存根>/config_files
    private static Path ensureCacheDir() throws IOException {
        Path dir = ClientCacheDirs.cacheRoot().resolve("config_files");
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        }
        return dir;
    }
}
