package cn.woshiikun_1145.mcmod.choco.cstmm.client.cache;

import cn.woshiikun_1145.mcmod.choco.cstmm.data.config.GlobalConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.util.ArrayList;
import java.util.List;

/**
 * 商店数据缓存
 * 保存最近一次 ShopDataS2CPayload 下发的数据（是否可购买 + 商品列表），供 ShopScreen 读取
 */
@Environment(EnvType.CLIENT)
public class ShopDataCache {
    private static ShopDataCache instance;

    private boolean eligible = false;
    /** null 表示尚未收到服务端下发的商店数据 */
    private List<GlobalConfig.ShopItem> items = null;

    private ShopDataCache() {}

    public static ShopDataCache getInstance() {
        if (instance == null) {
            instance = new ShopDataCache();
        }
        return instance;
    }

    public void update(boolean eligible, List<GlobalConfig.ShopItem> items) {
        this.eligible = eligible;
        this.items = items != null ? new ArrayList<>(items) : new ArrayList<>();
    }

    public boolean isEligible() { return eligible; }

    /** 是否已收到服务端下发的商店数据（区分"未收到"与"收到但不可购买"） */
    public boolean hasData() { return items != null; }

    public List<GlobalConfig.ShopItem> getItems() {
        return items != null ? new ArrayList<>(items) : new ArrayList<>();
    }

    public void clear() {
        eligible = false;
        items = null;
    }
}
