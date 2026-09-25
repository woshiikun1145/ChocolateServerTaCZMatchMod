package cn.woshiikun_1145.mcmod.choco.cstmm.client.cache;

import cn.woshiikun_1145.mcmod.choco.cstmm.data.config.GlobalConfig;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.config.MapConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.util.ArrayList;
import java.util.List;

/**
 * 客户端配置数据缓存
 * 从服务端同步配置，供 UI 使用
 * 【被谁使用】ClientNetworkHandler#applyConfigSync 写入、DISCONNECT 回调清空；
 *             MatchMenuScreen、ConfigScreen、QueueTabPanel 读取地图/全局配置。
 */
@Environment(EnvType.CLIENT)
public class ConfigDataCache {
    // 懒加载单例实例
    private static ConfigDataCache instance;

    // 服务端下发的地图配置列表
    private List<MapConfig> maps = new ArrayList<>();
    // 服务端下发的全局配置
    private GlobalConfig globalConfig = new GlobalConfig();
    /** 正在对局中使用的地图 ID 列表（服务端随配置同步下发），用于阻止删除 */
    private List<String> inUseMaps = new ArrayList<>();
    // 是否已收到过配置同步（用于界面区分"暂无地图"与"同步中"）
    private boolean loaded = false;

    // 单例，禁止外部实例化
    private ConfigDataCache() {}

    // 懒加载单例入口
    public static ConfigDataCache getInstance() {
        if (instance == null) {
            instance = new ConfigDataCache();
        }
        return instance;
    }

    // 覆盖写入地图列表并标记已加载（配置同步时调用）
    public void updateMaps(List<MapConfig> maps) {
        this.maps = new ArrayList<>(maps);
        this.loaded = true;
    }

    // 覆盖写入全局配置并标记已加载
    public void updateGlobalConfig(GlobalConfig config) {
        this.globalConfig = config;
        this.loaded = true;
    }

    // 覆盖写入"使用中地图"列表
    public void updateInUseMaps(List<String> inUseMaps) {
        this.inUseMaps = new ArrayList<>(inUseMaps);
    }

    // 判断地图是否正在对局中使用（用于配置界面阻止删除）
    public boolean isMapInUse(String mapId) {
        return inUseMaps.contains(mapId);
    }

    // 读取地图列表副本（防御性拷贝，避免外部修改缓存）
    public List<MapConfig> getMaps() {
        return new ArrayList<>(maps);
    }

    // 按 id 查找地图配置，找不到返回 null
    public MapConfig getMap(String id) {
        return maps.stream()
                .filter(m -> m.getId().equals(id))
                .findFirst()
                .orElse(null);
    }

    // 读取全局配置
    public GlobalConfig getGlobalConfig() {
        return globalConfig;
    }

    // 是否已收到过配置同步
    public boolean isLoaded() {
        return loaded;
    }

    // 断线时清空全部缓存数据
    public void clear() {
        maps.clear();
        globalConfig = new GlobalConfig();
        inUseMaps.clear();
        loaded = false;
    }

    /**
     * 仅清空配置本体（maps/global），保留 inUseMaps。
     * 【被谁使用】ClientNetworkHandler（JOIN 哈希握手发现磁盘缓存哈希与服务端不一致时：
     *           先清脏的本体再请求全量，而刚由 ConfigMetaPayload 更新的 inUseMaps 必须保留，
     *           否则配置界面会丢失"使用中地图"的删除保护）。
     */
    public void clearConfigBody() {
        maps.clear();
        globalConfig = new GlobalConfig();
        loaded = false;
    }
}