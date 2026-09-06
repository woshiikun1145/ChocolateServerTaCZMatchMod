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
 */
@Environment(EnvType.CLIENT)
public class ConfigDataCache {
    private static ConfigDataCache instance;

    private List<MapConfig> maps = new ArrayList<>();
    private GlobalConfig globalConfig = new GlobalConfig();
    /** 正在对局中使用的地图 ID 列表（服务端随配置同步下发），用于阻止删除 */
    private List<String> inUseMaps = new ArrayList<>();
    private boolean loaded = false;

    private ConfigDataCache() {}

    public static ConfigDataCache getInstance() {
        if (instance == null) {
            instance = new ConfigDataCache();
        }
        return instance;
    }

    public void updateMaps(List<MapConfig> maps) {
        this.maps = new ArrayList<>(maps);
        this.loaded = true;
    }

    public void updateGlobalConfig(GlobalConfig config) {
        this.globalConfig = config;
        this.loaded = true;
    }

    public void updateInUseMaps(List<String> inUseMaps) {
        this.inUseMaps = new ArrayList<>(inUseMaps);
    }

    public boolean isMapInUse(String mapId) {
        return inUseMaps.contains(mapId);
    }

    public List<MapConfig> getMaps() {
        return new ArrayList<>(maps);
    }

    public MapConfig getMap(String id) {
        return maps.stream()
                .filter(m -> m.getId().equals(id))
                .findFirst()
                .orElse(null);
    }

    public GlobalConfig getGlobalConfig() {
        return globalConfig;
    }

    public boolean isLoaded() {
        return loaded;
    }

    public void clear() {
        maps.clear();
        globalConfig = new GlobalConfig();
        inUseMaps.clear();
        loaded = false;
    }
}