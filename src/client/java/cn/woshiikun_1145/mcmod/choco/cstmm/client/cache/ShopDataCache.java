package cn.woshiikun_1145.mcmod.choco.cstmm.client.cache;

import cn.woshiikun_1145.mcmod.choco.cstmm.data.config.GlobalConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.util.ArrayList;
import java.util.List;

/**
 * 商店数据缓存
 * 保存最近一次 ShopDataS2CPayload 下发的数据（是否可购买 + 商品列表），供 ShopScreen 读取
 * 【被谁使用】ClientNetworkHandler 的 ShopDataS2CPayload 接收器写入、DISCONNECT 回调清空；
 *             ShopScreen#init/render 读取渲染商城界面。
 */
@Environment(EnvType.CLIENT)
public class ShopDataCache {
    // 懒加载单例实例
    private static ShopDataCache instance;

    // 当前玩家是否满足购买资格（服务端判定）
    private boolean eligible = false;
    /** null 表示尚未收到服务端下发的商店数据 */
    private List<GlobalConfig.ShopItem> items = null;

    // 单例，禁止外部实例化
    private ShopDataCache() {}

    // 懒加载单例入口
    public static ShopDataCache getInstance() {
        if (instance == null) {
            instance = new ShopDataCache();
        }
        return instance;
    }

    // 覆盖写入服务端下发的购买资格与商品列表
    public void update(boolean eligible, List<GlobalConfig.ShopItem> items) {
        this.eligible = eligible;
        this.items = items != null ? new ArrayList<>(items) : new ArrayList<>();
    }

    // 当前玩家是否可购买
    public boolean isEligible() { return eligible; }

    /** 是否已收到服务端下发的商店数据（区分"未收到"与"收到但不可购买"） */
    public boolean hasData() { return items != null; }

    // 读取商品列表副本（未收到数据时返回空列表）
    public List<GlobalConfig.ShopItem> getItems() {
        return items != null ? new ArrayList<>(items) : new ArrayList<>();
    }

    // 断线时清空缓存
    public void clear() {
        eligible = false;
        items = null;
    }
}
