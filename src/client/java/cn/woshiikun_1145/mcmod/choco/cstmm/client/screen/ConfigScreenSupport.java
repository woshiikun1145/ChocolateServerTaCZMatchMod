package cn.woshiikun_1145.mcmod.choco.cstmm.client.screen;

import cn.woshiikun_1145.mcmod.choco.cstmm.data.config.GlobalConfig;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.config.MapConfig;
import cn.woshiikun_1145.mcmod.choco.cstmm.util.BlockPosAdapter;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * 【作用】配置界面纯函数辅助（自 ConfigScreen 提取）：
 * 深拷贝（Gson 往返，保证全部持久化字段不丢失）与保存前校验。
 * 【被谁使用】仅 ConfigScreen 使用（loadData/addNewMap 等处深拷贝，saveConfig 保存前校验）。
 */
final class ConfigScreenSupport {

    private ConfigScreenSupport() {
    }

    // 深拷贝专用 Gson（注册 BlockPos 适配器处理出生点列表；与 ConfigScreen 保存用实例同构）
    private static final Gson COPY_GSON = new GsonBuilder()
            .registerTypeAdapter(net.minecraft.util.math.BlockPos.class, new BlockPosAdapter())
            .create();

    /**
     * 【作用】深拷贝单张地图配置：Gson 序列化→反序列化往返，自动覆盖全部持久化字段
     *         （gameType/包点/炸弹参数/商店分类列/商店物品/地图默认装备等——此前手写逐字段
     *         拷贝漏掉新字段导致保存时静默重置），编辑副本与缓存数据互不影响。
     */
    static MapConfig deepCopyMap(MapConfig src) {
        MapConfig dst = COPY_GSON.fromJson(COPY_GSON.toJsonTree(src), MapConfig.class);
        if (dst == null) {
            dst = new MapConfig();
        }
        return normalizeMap(dst);
    }

    /** Gson 往返后兜底：缺字段（null）重置为构造器默认值，避免编辑态写入临时对象丢失。 */
    private static MapConfig normalizeMap(MapConfig m) {
        if (m.getBoundary() == null) m.setBoundary(new MapConfig.Boundary());
        if (m.getRedBoundary() == null) m.setRedBoundary(new MapConfig.Boundary());
        if (m.getBlueBoundary() == null) m.setBlueBoundary(new MapConfig.Boundary());
        if (m.getRedSpawns() == null) m.setRedSpawns(new ArrayList<>());
        if (m.getBlueSpawns() == null) m.setBlueSpawns(new ArrayList<>());
        if (m.getBombSettings() == null) m.setBombSettings(new MapConfig.BombSettings());
        if (m.getShopItems() == null) m.setShopItems(new ArrayList<>());
        if (m.getShopColumns() == null) m.setShopColumns(new ArrayList<>());
        if (m.getShopColumnsT() == null) m.setShopColumnsT(new ArrayList<>());
        if (m.getShopColumnsCT() == null) m.setShopColumnsCT(new ArrayList<>());
        if (m.getBombsites() == null) m.setBombsites(new ArrayList<>());
        if (m.getDefaultGear() == null) m.setDefaultGear(new ArrayList<>());
        return m;
    }

    /**
     * 【作用】深拷贝商店分类列列表（含列内商品逐项复制）：
     *         用于爆破图 T/CT 队伍列的旧配置预填——两支队伍复制独立副本，
     *         避免共享 ShopItem 对象导致一侧编辑串扰另一侧。
     * 【被谁使用】ConfigScreen（爆破分类列编辑器的旧配置兼容预填）。
     */
    static List<MapConfig.ShopColumn> copyShopColumns(List<MapConfig.ShopColumn> src) {
        List<MapConfig.ShopColumn> out = new ArrayList<>();
        for (MapConfig.ShopColumn col : src) {
            MapConfig.ShopColumn copy = new MapConfig.ShopColumn(col.getName());
            copy.setIcon(col.getIcon());
            for (GlobalConfig.ShopItem item : col.getItems()) {
                copy.getItems().add(new GlobalConfig.ShopItem(item.getItemId(), item.getPrice(),
                        item.getMaxPurchase(), item.getTeam(), item.getDisplayName(), item.getIcon()));
            }
            out.add(copy);
        }
        return out;
    }

    // 【作用】深拷贝全局配置：Gson 往返覆盖全部字段（含 bombEconomy——此前逐字段拷贝漏掉，
    //        界面保存会把爆破经济参数重置为默认值）
    static GlobalConfig deepCopyGlobal(GlobalConfig src) {
        GlobalConfig dst = COPY_GSON.fromJson(COPY_GSON.toJsonTree(src), GlobalConfig.class);
        if (dst == null) {
            dst = new GlobalConfig();
        }
        if (dst.getDefaultGear() == null) dst.setDefaultGear(new ArrayList<>());
        if (dst.getBombEconomy() == null) dst.setBombEconomy(new GlobalConfig.BombEconomy());
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
