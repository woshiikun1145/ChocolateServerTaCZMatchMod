package cn.woshiikun_1145.mcmod.choco.cstmm.client.screen;

import cn.woshiikun_1145.mcmod.choco.cstmm.data.config.GlobalConfig;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.config.MapConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * 配置界面纯函数辅助（自 ConfigScreen 提取，逻辑逐字保留）：
 * 深拷贝与保存前校验。
 */
final class ConfigScreenSupport {

    private ConfigScreenSupport() {
    }

    static MapConfig deepCopyMap(MapConfig src) {
        MapConfig dst = new MapConfig();
        dst.setId(src.getId());
        dst.setDisplayName(src.getDisplayName());
        dst.setEnabled(src.isEnabled());
        dst.setWinCondition(src.getWinCondition());
        dst.setTargetKills(src.getTargetKills());
        dst.setMaxDuration(src.getMaxDuration());
        dst.setTieRule(src.getTieRule());
        dst.setMinRedPlayers(src.getMinRedPlayers());
        dst.setMinBluePlayers(src.getMinBluePlayers());
        dst.setPrepareTime(src.getPrepareTime());
        dst.setBoundaryWarningTime(src.getBoundaryWarningTime());
        dst.setBoundaryPenaltyKills(src.getBoundaryPenaltyKills());
        dst.setKickCooldownSeconds(src.getKickCooldownSeconds());
        dst.setCooldownSeconds(src.getCooldownSeconds());
        dst.setReinforceable(src.isReinforceable());
        dst.setDimension(src.getDimension());
        dst.setReinforcementMode(src.getReinforcementMode());
        dst.setMaxRedPlayers(src.getMaxRedPlayers());
        dst.setMaxBluePlayers(src.getMaxBluePlayers());
        dst.setBackgroundBase64(src.getBackgroundBase64());
        // 商店物品按地图配置，逐项深拷贝（源列表可能为 Gson 反序列化产生的 null）
        List<GlobalConfig.ShopItem> srcShopItems = src.getShopItems();
        List<GlobalConfig.ShopItem> dstShopItems = new ArrayList<>();
        if (srcShopItems != null) {
            for (GlobalConfig.ShopItem item : srcShopItems) {
                dstShopItems.add(new GlobalConfig.ShopItem(item.getItemId(), item.getPrice(), item.getMaxPurchase()));
            }
        }
        dst.setShopItems(dstShopItems);
        dst.getBoundary().setMinX(src.getBoundary().getMinX());
        dst.getBoundary().setMinY(src.getBoundary().getMinY());
        dst.getBoundary().setMinZ(src.getBoundary().getMinZ());
        dst.getBoundary().setMaxX(src.getBoundary().getMaxX());
        dst.getBoundary().setMaxY(src.getBoundary().getMaxY());
        dst.getBoundary().setMaxZ(src.getBoundary().getMaxZ());
        dst.getRedSpawns().clear();
        dst.getRedSpawns().addAll(src.getRedSpawns());
        dst.getBlueSpawns().clear();
        dst.getBlueSpawns().addAll(src.getBlueSpawns());
        return dst;
    }

    static GlobalConfig deepCopyGlobal(GlobalConfig src) {
        GlobalConfig dst = new GlobalConfig();
        dst.setQuickTimeout(src.getQuickTimeout());
        dst.getDefaultGear().clear();
        for (GlobalConfig.EquipSlot slot : src.getDefaultGear()) {
            dst.getDefaultGear().add(new GlobalConfig.EquipSlot(slot.getSlot(), slot.getItemId()));
        }
        return dst;
    }

    /**
     * #27 保存前校验全部地图 ID 无重复（服务端按 ID 静默覆盖，重复会导致配置丢失）
     * @return 错误信息，无重复返回 null
     */
    static String validateMapIds(List<MapConfig> maps) {
        for (int i = 0; i < maps.size(); i++) {
            String id = maps.get(i).getId();
            if (id == null) continue;
            for (int j = i + 1; j < maps.size(); j++) {
                if (id.equals(maps.get(j).getId())) {
                    return "第 " + (i + 1) + " 张与第 " + (j + 1) + " 张地图 ID 重复（\"" + id
                            + "\"），服务端会按 ID 覆盖，请修改后再保存！";
                }
            }
        }
        return null;
    }

    /**
     * 校验装备槽位ID是否合法。
     * 允许: armor.body / armor.chest / armor.feet / armor.head / armor.legs / container.0 ~ container.35
     * @param gear 待校验的默认装备列表
     * @return 错误信息，合法返回 null
     */
    static String validateGearSlots(List<GlobalConfig.EquipSlot> gear) {
        List<String> valid = new ArrayList<>(List.of(
                "armor.body", "armor.chest", "armor.feet", "armor.head", "armor.legs"));
        for (int i = 0; i <= 35; i++) valid.add("container." + i);

        for (int i = 0; i < gear.size(); i++) {
            String slot = gear.get(i).getSlot();
            if (!valid.contains(slot)) {
                return "第 " + (i + 1) + " 行装备槽位 \"" + slot + "\" 无效！"
                        + "可用: armor.head/chest/legs/feet/body 或 container.0~container.35";
            }
        }
        return null;
    }
}
