package cn.woshiikun_1145.mcmod.choco.cstmm.manager;

import cn.woshiikun_1145.mcmod.choco.cstmm.Cstmm;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.config.GlobalConfig;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.config.MapConfig;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.MatchSession;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.List;

/**
 * 装备管理器 - 仅服务端
 * 负责竞技模式装备发放和商店购买（免费）
 * 【作用】按全局配置给竞技玩家发放默认装备（配置缺失时发放钻石套兜底），
 *         并处理商店购买请求（按地图商品配置免费发放物品）。
 * 【被谁使用】MatchManager（开局 setupCompetitiveGear）、NetworkHandler（客户端商店购买请求
 *           givePurchasedItem）。仅服务端。
 */
public class EquipmentManager {

    private static EquipmentManager instance;

    private EquipmentManager() {}

    // 单例入口（懒加载）
    public static EquipmentManager getInstance() {
        if (instance == null) {
            instance = new EquipmentManager();
        }
        return instance;
    }

    /**
     * 发放默认装备给玩家（竞技模式）
     * 【被谁使用】setupCompetitiveGear（开局清空背包后调用）；仅服务端内部使用。
     */
    public void giveDefaultGear(ServerPlayerEntity player) {
        GlobalConfig config = ConfigManager.getInstance().getGlobalConfig();
        if (config == null) {
            Cstmm.LOGGER.warn("[CSTMM - EquipmentManager] Global config not loaded, using fallback gear");
            giveFallbackGear(player);
            return;
        }

        var gearList = config.getDefaultGear();
        if (gearList == null || gearList.isEmpty()) {
            Cstmm.LOGGER.warn("[CSTMM - EquipmentManager] No default gear configured, using fallback");
            giveFallbackGear(player);
            return;
        }
        giveGearList(player, gearList);
    }

    /**
     * 【作用】按地图发放默认装备（爆破图回合重生用）：地图配置了 defaultGear 时优先使用，
     *         未配置（空列表）回退全局默认装备——行为兼容旧地图，后续（阶段⑤）由配置界面
     *         "手持添加"逐图写入并自动迁移落盘。
     * 【被谁使用】BombRoundManager（回合重置给阵亡玩家发装备）。仅服务端。
     */
    public void giveDefaultGear(ServerPlayerEntity player, MapConfig map) {
        if (map != null && !map.getDefaultGear().isEmpty()) {
            giveGearList(player, map.getDefaultGear());
            return;
        }
        giveDefaultGear(player);
    }

    /** 按 EquipSlot 列表逐槽发放装备（giveDefaultGear 两个重载共用）。 */
    private void giveGearList(ServerPlayerEntity player, List<GlobalConfig.EquipSlot> gearList) {
        RegistryWrapper.WrapperLookup lookup = player.getWorld().getRegistryManager();

        for (GlobalConfig.EquipSlot equipSlot : gearList) {
            String slot = equipSlot.getSlot();
            String itemNbt = equipSlot.getItemId();

            // 槽位为 null/空串（JSON 显式 null 等异常数据）直接跳过，避免 NPE 干扰后续条目
            if (slot == null || slot.isBlank()) continue;
            if (itemNbt == null || itemNbt.isEmpty()) continue;

            try {
                ItemStack stack = parseItemStack(itemNbt, lookup);
                if (stack.isEmpty()) continue;
                applyToSlot(player, slot, stack);
            } catch (Exception e) {
                Cstmm.LOGGER.warn("[CSTMM - EquipmentManager] Failed to apply gear to slot {}: {}", slot, e.getMessage());
            }
        }

        Cstmm.LOGGER.debug("[CSTMM - EquipmentManager] Gave default gear to {}", player.getName());
    }

    // 【作用】兜底装备：全局配置缺失/未配置装备时发钻石盔甲套装 + 64 熟牛肉
    private void giveFallbackGear(ServerPlayerEntity player) {
        applyToSlot(player, "head", new ItemStack(net.minecraft.item.Items.DIAMOND_HELMET));
        applyToSlot(player, "chest", new ItemStack(net.minecraft.item.Items.DIAMOND_CHESTPLATE));
        applyToSlot(player, "legs", new ItemStack(net.minecraft.item.Items.DIAMOND_LEGGINGS));
        applyToSlot(player, "feet", new ItemStack(net.minecraft.item.Items.DIAMOND_BOOTS));
        player.getInventory().offerOrDrop(new ItemStack(net.minecraft.item.Items.COOKED_BEEF, 64));
        Cstmm.LOGGER.debug("[CSTMM - EquipmentManager] Gave fallback gear to {}", player.getName());
    }

    // 【作用】按槽位名把物品放到对应装备栏。兼容两种写法：
    //         ① 简写：head/chest/legs/feet/mainhand/offhand（兜底装备与旧配置使用）；
    //         ② 配置界面 /item replace entity 的 <slot> 语法：armor.head/chest/legs/feet/body、
    //            weapon.mainhand/offhand、container.0~35（玩家主背包对应槽位）——此前仅识别
    //            简写，配置界面保存的 armor.* 槽位会落入 default 分支被塞进背包，此处一并修正；
    //         armor.body（玩家无身体盔甲槽）与其余未知槽位塞背包或掉落
    private void applyToSlot(ServerPlayerEntity player, String slot, ItemStack stack) {
        if (stack.isEmpty()) return;

        switch (slot.toLowerCase()) {
            case "head", "armor.head" -> player.getInventory().armor.set(3, stack);
            case "chest", "armor.chest" -> player.getInventory().armor.set(2, stack);
            case "legs", "armor.legs" -> player.getInventory().armor.set(1, stack);
            case "feet", "armor.feet" -> player.getInventory().armor.set(0, stack);
            case "mainhand", "weapon.mainhand" -> player.getInventory().setStack(player.getInventory().selectedSlot, stack);
            case "offhand", "weapon.offhand" -> player.getInventory().offHand.set(0, stack);
            default -> {
                // container.N：替换主背包 N 号槽位（/item replace 语义）；越界/非法退化为塞背包或掉落
                String s = slot.toLowerCase();
                if (s.startsWith("container.")) {
                    try {
                        int idx = Integer.parseInt(s.substring("container.".length()));
                        if (idx >= 0 && idx < player.getInventory().main.size()) {
                            player.getInventory().main.set(idx, stack);
                            return;
                        }
                    } catch (NumberFormatException ignored) {
                        // 非数字后缀，走下方 offerOrDrop
                    }
                }
                player.getInventory().offerOrDrop(stack);
            }
        }
    }

    /**
     * 解析物品字符串为 ItemStack（统一委托 ItemStrings，与服务端/客户端预览同一套规则）。
     * 支持格式：
     * 1. /give 格式（主格式）："minecraft:netherite_sword[minecraft:enchantments={...}] 1"
     * 2. 完整 SNBT："{id:\"minecraft:diamond_sword\",count:1,components:{...}}"（含 1.20 tag 迁移）
     * 3. "物品ID 数量"（如 "minecraft:cooked_beef 64"）与纯物品ID
     */
    private ItemStack parseItemStack(String itemString, RegistryWrapper.WrapperLookup lookup) {
        return cn.woshiikun_1145.mcmod.choco.cstmm.util.ItemStrings.toStack(itemString, lookup);
    }

    /**
     * 【作用】公开的物品字符串解析入口（parseItemStack 的对外包装）：
     *         供 EconomyManager 商店扣费购买复用同一套解析规则（"物品ID 数量" / SNBT）。
     * 【被谁使用】EconomyManager#tryPurchase。仅服务端。
     */
    public ItemStack parseAndBuild(ServerPlayerEntity player, String itemString) {
        if (itemString == null || itemString.isBlank()) return ItemStack.EMPTY;
        try {
            return parseItemStack(itemString, player.getWorld().getRegistryManager());
        } catch (Exception e) {
            Cstmm.LOGGER.warn("[CSTMM - EquipmentManager] parseAndBuild failed: {}", e.getMessage());
            return ItemStack.EMPTY;
        }
    }

    /**
     * 商店购买物品 - 免费发放，不扣费
     * 商品取自玩家所在地图的配置；购买资格按对局会话模式判定（地图配置已无模式）
     * 【被谁使用】NetworkHandler（处理客户端 SHOP_BUY 购买请求 payload）。仅服务端。
     */
    public boolean givePurchasedItem(ServerPlayerEntity player, int itemIndex) {
        MatchSession session = MatchManager.getInstance().getPlayerSession(player.getUuid());
        MapConfig map = session != null
                ? ConfigManager.getInstance().getMap(session.getMapName()) : null;
        // 资格按会话；map 仍需非空以取商品列表
        if (session == null || session.getPhase() == MatchSession.GamePhase.ENDED
                || map == null || !session.isCompetitive()) {
            player.sendMessage(Text.literal("§c仅竞技模式对局中才可购买商品！"), false);
            return false;
        }

        // 商品取平铺分类列（与购买页下发顺序一致；未配置列时回退旧版平铺 shopItems）
        var items = map.getFlatShopItems();
        if (items == null || itemIndex < 0 || itemIndex >= items.size()) {
            return false;
        }

        GlobalConfig.ShopItem shopItem = items.get(itemIndex);
        String itemNbt = shopItem.getItemId();

        if (itemNbt == null || itemNbt.isEmpty()) return false;

        try {
            RegistryWrapper.WrapperLookup lookup = player.getWorld().getRegistryManager();
            ItemStack stack = parseItemStack(itemNbt, lookup);
            if (stack.isEmpty()) return false;

            // 不扣费，直接发放
            player.getInventory().offerOrDrop(stack);
            return true;
        } catch (Exception e) {
            Cstmm.LOGGER.warn("[CSTMM - EquipmentManager] Failed to give purchased item: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 清空玩家背包并给予竞技装备
     * 【被谁使用】MatchManager（对局开始把玩家传送入地图时）。仅服务端。
     */
    public void setupCompetitiveGear(ServerPlayerEntity player) {
        player.getInventory().clear();
        player.getInventory().armor.clear();
        player.getInventory().offHand.clear();
        giveDefaultGear(player);
    }
}