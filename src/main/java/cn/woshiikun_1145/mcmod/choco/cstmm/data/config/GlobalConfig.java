package cn.woshiikun_1145.mcmod.choco.cstmm.data.config;

import java.util.ArrayList;
import java.util.List;

/**
 * 全局配置
 * 对应原 weapons.mcfunction 和 accessories.mcfunction 中的商品列表
 * 新增通用游戏参数
 */
public class GlobalConfig {
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