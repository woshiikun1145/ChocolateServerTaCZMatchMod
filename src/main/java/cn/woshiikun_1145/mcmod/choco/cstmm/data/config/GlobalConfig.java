package cn.woshiikun_1145.mcmod.choco.cstmm.data.config;

import java.util.ArrayList;
import java.util.List;

/**
 * 【作用】全局配置（Gson 持久化到 config/cstmm/config.json）：玩家默认装备槽位与
 * 快速匹配超时等通用参数（对局参数已下沉到 MapConfig 按地图配置）。
 * 【被谁使用】ConfigManager 加载/保存/生成默认值（服务端）；EquipmentManager 应用默认装备
 * 与商店购买、QuickMatchEngine 读取快速匹配超时、NetworkHandler 同步商店数据均读取。
 */
public class GlobalConfig {
    // 玩家进对局时的默认装备槽位列表（head/chest/legs/feet 等 → 物品 id）
    private List<EquipSlot> defaultGear;

    // ========== 全局游戏参数 ==========
    // 注意：准备时间、边界警告/惩罚、踢人冷却、补位规则、最低人数均已改为按地图配置（见 MapConfig）
    private int quickTimeout = 30;         // 快速匹配超时秒数（唯一保留的全局参数）

    // Getter/Setter


    public GlobalConfig() {
        this.defaultGear = new ArrayList<>();
    }

    // ========== Getter/Setter ==========

    public int getQuickTimeout() { return quickTimeout; }
    public void setQuickTimeout(int quickTimeout) { this.quickTimeout = quickTimeout; }

    public List<EquipSlot> getDefaultGear() { return defaultGear; }

    public void setDefaultGear(List<EquipSlot> defaultGear) { this.defaultGear = defaultGear; }

    // ========== 内部类 ==========

    /**
     * 【作用】商店商品条目：物品 id（可带数量后缀如 "minecraft:cooked_beef 64"）、价格与限购次数。
     * 【被谁使用】ConfigManager 生成默认商品、EquipmentManager 商店购买校验、
     * MapConfig.shopItems 与 NetworkHandler 商店同步引用（服务端）。
     */
    public static class ShopItem {
        private String itemId;
        private int price;
        private int maxPurchase;

        public ShopItem() {}

        public ShopItem(String itemId, int price, int maxPurchase) {
            this.itemId = itemId;
            this.price = price;
            this.maxPurchase = maxPurchase;
        }

        public String getItemId() { return itemId; }
        public int getPrice() { return price; }
        public int getMaxPurchase() { return maxPurchase; }

        public void setItemId(String itemId) { this.itemId = itemId; }
        public void setPrice(int price) { this.price = price; }
        public void setMaxPurchase(int maxPurchase) { this.maxPurchase = maxPurchase; }
    }

    /**
     * 【作用】默认装备槽位条目：槽位名（head/chest/legs/feet）→ 物品 id，
     * 玩家进入对局时按此发放初始装备。
     * 【被谁使用】ConfigManager 生成默认装备、EquipmentManager 应用装备（服务端）。
     */
    public static class EquipSlot {
        private String slot;
        private String itemId;

        public EquipSlot() {}

        public EquipSlot(String slot, String itemId) {
            this.slot = slot;
            this.itemId = itemId;
        }

        public String getSlot() { return slot; }
        public String getItemId() { return itemId; }

        public void setSlot(String slot) { this.slot = slot; }
        public void setItemId(String itemId) { this.itemId = itemId; }
    }
}