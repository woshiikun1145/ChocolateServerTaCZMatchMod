package cn.woshiikun_1145.mcmod.choco.cstmm.manager;

import cn.woshiikun_1145.mcmod.choco.cstmm.Cstmm;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.config.GlobalConfig;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.config.MapConfig;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.MatchSession;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

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

        RegistryWrapper.WrapperLookup lookup = player.getWorld().getRegistryManager();

        for (GlobalConfig.EquipSlot equipSlot : gearList) {
            String slot = equipSlot.getSlot();
            String itemNbt = equipSlot.getItemId();

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

    // 【作用】按槽位名（head/chest/legs/feet/mainhand/offhand）把物品放到对应装备栏，未知槽位塞背包或掉落
    private void applyToSlot(ServerPlayerEntity player, String slot, ItemStack stack) {
        if (stack.isEmpty()) return;

        switch (slot.toLowerCase()) {
            case "head" -> player.getInventory().armor.set(3, stack);
            case "chest" -> player.getInventory().armor.set(2, stack);
            case "legs" -> player.getInventory().armor.set(1, stack);
            case "feet" -> player.getInventory().armor.set(0, stack);
            case "mainhand" -> player.getInventory().setStack(player.getInventory().selectedSlot, stack);
            case "offhand" -> player.getInventory().offHand.set(0, stack);
            default -> player.getInventory().offerOrDrop(stack);
        }
    }

    /**
     * 解析物品字符串为 ItemStack
     * 支持两种格式：
     * 1. "物品ID 数量"（如 "minecraft:cooked_beef 64"）
     * 2. SNBT 字符串（如 "{id:\"minecraft:diamond_sword\",count:1}"）
     * 1.21.1 需要使用双参数 fromNbt，并处理 Optional
     */
    private ItemStack parseItemStack(String itemString, RegistryWrapper.WrapperLookup lookup) {
        String nbtString = itemString.trim();
        int count = 1;

        // 支持 "物品ID 数量" 格式：末尾为纯数字时按数量处理（不影响以 } 结尾的 SNBT）
        int spaceIdx = nbtString.lastIndexOf(' ');
        if (spaceIdx > 0) {
            try {
                int parsedCount = Integer.parseInt(nbtString.substring(spaceIdx + 1));
                if (parsedCount >= 1) {
                    count = parsedCount;
                    nbtString = nbtString.substring(0, spaceIdx).trim();
                }
            } catch (NumberFormatException ignored) {
                // 末尾不是数字，按整体处理（可能是含空格的 SNBT）
            }
        }

        try {
            NbtElement parsed = NbtHelper.fromNbtProviderString(nbtString);
            if (!(parsed instanceof NbtCompound compound)) {
                return ItemStack.EMPTY;
            }
            // 1.21.1 正确签名：fromNbt(RegistryWrapper.WrapperLookup, NbtCompound) 返回 Optional<ItemStack>
            return withCount(ItemStack.fromNbt(lookup, compound).orElse(ItemStack.EMPTY), count);
        } catch (Exception e) {
            // 尝试作为简单物品ID处理（不带NBT）
            try {
                var item = net.minecraft.registry.Registries.ITEM.get(
                        net.minecraft.util.Identifier.tryParse(nbtString)
                );
                if (item != null) {
                    return withCount(new ItemStack(item), count);
                }
            } catch (Exception ignored) {}
            return ItemStack.EMPTY;
        }
    }

    /** 按解析出的数量设置物品堆数量（不超过最大堆叠上限） */
    private ItemStack withCount(ItemStack stack, int count) {
        if (!stack.isEmpty() && count > 1) {
            stack.setCount(Math.min(count, stack.getMaxCount()));
        }
        return stack;
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
            player.sendMessage(Text.literal("§c仅竞技模式对局中可购买商品"), false);
            return false;
        }

        var items = map.getShopItems();
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