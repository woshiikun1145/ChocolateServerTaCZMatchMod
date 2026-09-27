package cn.woshiikun_1145.mcmod.choco.cstmm.client.screen;

import cn.woshiikun_1145.mcmod.choco.cstmm.data.config.GlobalConfig;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.config.MapConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * 【作用】配置界面纯函数辅助（自 ConfigScreen 提取，逻辑逐字保留）：
 * 深拷贝与保存前校验。
 * 【被谁使用】仅 ConfigScreen 使用（loadData/addNewMap 等处深拷贝，saveConfig 保存前校验）。
 */
final class ConfigScreenSupport {

    private ConfigScreenSupport() {
    }

    // 【作用】深拷贝单张地图配置（含商店物品/边界/出生点集合），编辑副本与缓存数据互不影响
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
        copyBoundary(src.getBoundary(), dst.getBoundary());
        copyBoundary(src.getRedBoundary(), dst.getRedBoundary());
        copyBoundary(src.getBlueBoundary(), dst.getBlueBoundary());
        dst.getRedSpawns().clear();
        dst.getRedSpawns().addAll(src.getRedSpawns());
        dst.getBlueSpawns().clear();
        dst.getBlueSpawns().addAll(src.getBlueSpawns());
        return dst;
    }

    // 【作用】深拷贝单个边界对象（公共/红队/蓝队边界共用，六个坐标逐字段复制）
    private static void copyBoundary(MapConfig.Boundary src, MapConfig.Boundary dst) {
        dst.setMinX(src.getMinX());
        dst.setMinY(src.getMinY());
        dst.setMinZ(src.getMinZ());
        dst.setMaxX(src.getMaxX());
        dst.setMaxY(src.getMaxY());
        dst.setMaxZ(src.getMaxZ());
    }

    // 【作用】深拷贝全局配置（含默认装备槽位列表）
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

    // 装备槽位合法归一键（与服务端 ModCommands.GEAR_SLOTS 同口径）
    private static final List<String> VALID_GEAR_KEYS = List.of(
            "head", "chest", "legs", "feet", "mainhand", "offhand");

    /**
     * 校验装备槽位ID是否合法（与服务端 ModCommands.isValidGearSlot 同口径，命令侧无超集差异）：
     * 简写 head/chest/legs/feet/mainhand/offhand 及 /item 语法别名 armor.head/chest/legs/feet、
     * weapon.mainhand/offhand，无别名的 armor.body，container.0 ~ container.35。
     * 此前仅认 armor.* 与 container.* 两类：命令侧配置的合法简写槽位会令配置界面任何保存被整体拒绝。
     * @param gear 待校验的默认装备列表
     * @return 错误信息，合法返回 null
     */
    static String validateGearSlots(List<GlobalConfig.EquipSlot> gear) {
        for (int i = 0; i < gear.size(); i++) {
            String slot = gear.get(i).getSlot();
            if (!isValidGearSlot(slot)) {
                return "第 " + (i + 1) + " 行装备槽位 \"" + slot + "\" 无效！"
                        + "可用: head/chest/legs/feet/mainhand/offhand（或 armor.head/chest/legs/feet、"
                        + "weapon.mainhand/offhand）、armor.body、container.0~35";
            }
        }
        return null;
    }

    /** 槽位等价归一键（与服务端 gearSlotKey 同映射）：null/异常返回空串 */
    private static String gearSlotKey(String slot) {
        if (slot == null) return "";
        return switch (slot.toLowerCase()) {
            case "head", "armor.head" -> "head";
            case "chest", "armor.chest" -> "chest";
            case "legs", "armor.legs" -> "legs";
            case "feet", "armor.feet" -> "feet";
            case "mainhand", "weapon.mainhand" -> "mainhand";
            case "offhand", "weapon.offhand" -> "offhand";
            default -> slot.toLowerCase();
        };
    }

    /** 槽位合法性（与服务端 isValidGearSlot 同口径）：归一键 ∈ 简写集，或 armor.body，或 container.0~35 */
    private static boolean isValidGearSlot(String slot) {
        String key = gearSlotKey(slot);
        if (VALID_GEAR_KEYS.contains(key)) return true;
        if (key.equals("armor.body")) return true;
        if (key.startsWith("container.")) {
            try {
                int idx = Integer.parseInt(key.substring("container.".length()));
                return idx >= 0 && idx <= 35;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
    }
}
