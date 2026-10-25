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

    /** 爆破图经济系统参数（CS 标准默认值，全局可配；仅 gameType=BOMB 的地图对局生效） */
    private BombEconomy bombEconomy;

    // Getter/Setter


    public GlobalConfig() {
        this.defaultGear = new ArrayList<>();
        this.bombEconomy = new BombEconomy();
    }

    // ========== Getter/Setter ==========

    public int getQuickTimeout() { return quickTimeout; }
    public void setQuickTimeout(int quickTimeout) { this.quickTimeout = quickTimeout; }

    /** 爆破图经济参数（Gson 兜底：缺失/null 时以默认值对象兜底） */
    public BombEconomy getBombEconomy() {
        return bombEconomy == null ? new BombEconomy() : bombEconomy;
    }

    public void setBombEconomy(BombEconomy bombEconomy) { this.bombEconomy = bombEconomy; }

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
        /** 适用队伍：BOTH=双方（默认）/ T / CT（爆破图购买校验；普通图忽略） */
        private String team;
        /** 显示名覆盖（空 = 用物品自带名，含自定义名） */
        private String displayName;
        /** 商店列图标：base64 / http(s):// URL / 空（= 物品栏贴图） */
        private String icon;

        public ShopItem() {}

        public ShopItem(String itemId, int price, int maxPurchase) {
            this.itemId = itemId;
            this.price = price;
            this.maxPurchase = maxPurchase;
        }

        public ShopItem(String itemId, int price, int maxPurchase, String team, String displayName, String icon) {
            this.itemId = itemId;
            this.price = price;
            this.maxPurchase = maxPurchase;
            this.team = team;
            this.displayName = displayName;
            this.icon = icon;
        }

        public String getItemId() { return itemId; }
        public int getPrice() { return price; }
        public int getMaxPurchase() { return maxPurchase; }
        /** 适用队伍（兜底 BOTH） */
        public String getTeam() { return team == null || team.isBlank() ? "BOTH" : team.toUpperCase(); }
        /** 显示名覆盖（未设置返回空串，调用方回退物品自带名） */
        public String getDisplayName() { return displayName == null ? "" : displayName; }
        /** 列图标（未设置返回空串 = 物品栏贴图） */
        public String getIcon() { return icon == null ? "" : icon; }

        public void setItemId(String itemId) { this.itemId = itemId; }
        public void setPrice(int price) { this.price = price; }
        public void setMaxPurchase(int maxPurchase) { this.maxPurchase = maxPurchase; }
        public void setTeam(String team) { this.team = team; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
        public void setIcon(String icon) { this.icon = icon; }
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

    /**
     * 【作用】爆破图经济系统参数（CS 标准默认值，Gson 持久化到 config/cstmm/config.json，
     * 全局可配）：起始/上限金额、击杀与回合胜负奖励、连败递增败金、安放/拆除奖励、
     * 购买窗口时长与休闲奖励倍率。价格=匹配内货币（非服务器货币，不持久化，对局结束清零）。
     * 【被谁使用】EconomyManager 全部金额计算与购买窗口判定（服务端）；阶段⑤配置界面编辑。
     */
    public static class BombEconomy {
        /** 对局起始金额（CS 标准 $800；半场/加时重置回此值） */
        private int startMoney = 800;
        /** 金额上限（CS 标准 $16000，奖励与击杀所得封顶） */
        private int maxMoney = 16000;
        /** 击杀奖励（CS 标准 $300；休闲模式按 casualMoneyMultiplier 减半） */
        private int killReward = 300;
        /** 安放 C4 个人奖励（CS 标准 $300，即使本回合战败也保留） */
        private int plantReward = 300;
        /** 拆除成功个人奖励（CS 标准 $300，与回合胜利金叠加） */
        private int defuseReward = 300;
        /** 全歼/超时判定胜利的回合胜利金（CS 标准 $3250） */
        private int winBonusElimination = 3250;
        /** C4 爆炸或拆除成功判定胜利的回合胜利金（CS 标准 $3500） */
        private int winBonusObjective = 3500;
        /** 败方基础败金（CS 标准 $1400） */
        private int loseBonusBase = 1400;
        /** 每连续败一回合的败金递增（CS 标准 $500） */
        private int loseBonusStep = 500;
        /** 败金上限（CS 标准 $3400） */
        private int loseBonusMax = 3400;
        /** 购买窗口时长（秒）：冻结时间结束后的战斗阶段前 N 秒内可购买；炸弹安放后禁买 */
        private int buyWindowSeconds = 20;
        /** 拆弹装备价格（爆破图购买页第 1 列固定项，仅 CT；CS 标准 $400） */
        private int defuserPrice = 400;
        /** 休闲模式奖励倍率（0~1，默认 0.5 = 击杀/回合奖励减半；败金同样按此倍率缩放） */
        private double casualMoneyMultiplier = 0.5;

        public int getStartMoney() { return Math.max(0, startMoney); }
        public int getMaxMoney() { return Math.max(1, maxMoney); }
        public int getKillReward() { return Math.max(0, killReward); }
        public int getPlantReward() { return Math.max(0, plantReward); }
        public int getDefuseReward() { return Math.max(0, defuseReward); }
        public int getWinBonusElimination() { return Math.max(0, winBonusElimination); }
        public int getWinBonusObjective() { return Math.max(0, winBonusObjective); }
        public int getLoseBonusBase() { return Math.max(0, loseBonusBase); }
        public int getLoseBonusStep() { return Math.max(0, loseBonusStep); }
        public int getLoseBonusMax() { return Math.max(getLoseBonusBase(), loseBonusMax); }
        public int getBuyWindowSeconds() { return Math.max(0, buyWindowSeconds); }
        public int getDefuserPrice() { return Math.max(0, defuserPrice); }
        /** 休闲奖励倍率（钳制在 0~1；非法值回退 0.5） */
        public double getCasualMoneyMultiplier() {
            return (casualMoneyMultiplier >= 0 && casualMoneyMultiplier <= 1) ? casualMoneyMultiplier : 0.5;
        }

        public void setStartMoney(int v) { this.startMoney = v; }
        public void setMaxMoney(int v) { this.maxMoney = v; }
        public void setKillReward(int v) { this.killReward = v; }
        public void setPlantReward(int v) { this.plantReward = v; }
        public void setDefuseReward(int v) { this.defuseReward = v; }
        public void setWinBonusElimination(int v) { this.winBonusElimination = v; }
        public void setWinBonusObjective(int v) { this.winBonusObjective = v; }
        public void setLoseBonusBase(int v) { this.loseBonusBase = v; }
        public void setLoseBonusStep(int v) { this.loseBonusStep = v; }
        public void setLoseBonusMax(int v) { this.loseBonusMax = v; }
        public void setBuyWindowSeconds(int v) { this.buyWindowSeconds = v; }
        public void setDefuserPrice(int v) { this.defuserPrice = v; }
        public void setCasualMoneyMultiplier(double v) { this.casualMoneyMultiplier = v; }
    }
}