package cn.woshiikun_1145.mcmod.choco.cstmm.item;

import cn.woshiikun_1145.mcmod.choco.cstmm.Cstmm;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * 【作用】模组自定义物品注册表（命名空间固定为 cstmm，与设计文档的物品 ID 约定一致）：
 *         cstmm:c4 = C4 炸药（爆破模式 T 方安放用）；
 *         cstmm:hegrenade / cstmm:flashbang / cstmm:smokegrenade = CS 手雷
 *         （移植自 CounterStrikeGrenades，阶段 1）。
 *         本模组的全部物品集中在独立创造模式物品栏（cstmm:main）中展示，
 *         不再挂入原版战斗栏（2026-11-14 起，用户指定：CSTMM 物品单独分类）。
 *         拆弹装备不再是物品——持有状态用玩家 tag（cstmm_defuse_kit）表示，
 *         见 BombInteractionManager.DEFUSE_KIT_TAG。
 * 【被谁使用】Cstmm.onInitialize（启动时注册）；EconomyManager（C4 禁购校验）、
 *           BombRoundManager（回合开始发放/掉落物清理）、阶段③的安放交互（手持判定）、
 *           手雷商店/默认装备配置（物品串直接引用上述 ID）。
 */
public class ModItems {

    /** CSTMM 独立创造模式物品栏注册键（物品栏中单独的一页） */
    public static final RegistryKey<ItemGroup> CSTMM_GROUP = RegistryKey.of(
            Registries.ITEM_GROUP.getKey(), Identifier.of("cstmm", "main"));

    /** C4 炸药（不可堆叠，每个回合内仅一枚在循环流转） */
    public static final Item C4 = register("c4", new Item(new Item.Settings().maxCount(1)));

    /** 高爆手雷（HE）：爆炸伤害随距离线性衰减，引信 2 秒 */
    public static final Item HEGRENADE = register("hegrenade",
            new HEGrenadeItem(new Item.Settings().maxCount(2).attributeModifiers(
                    CounterStrikeGrenadeItem.preventSwingModifiers())));

    /** 闪光弹：按角度/距离/遮挡计算致盲时长，无实体伤害 */
    public static final Item FLASHBANG = register("flashbang",
            new FlashBangItem(new Item.Settings().maxCount(2).attributeModifiers(
                    CounterStrikeGrenadeItem.preventSwingModifiers())));

    /** 烟雾弹：落地发烟，烟雾可被箭矢/子弹（TACZ）穿透打散 */
    public static final Item SMOKEGRENADE = register("smokegrenade",
            new SmokeGrenadeItem(new Item.Settings().maxCount(2).attributeModifiers(
                    CounterStrikeGrenadeItem.preventSwingModifiers())));

    /** 燃烧弹：落地生成蓝白魂火（粒子），伤害随接触时间爬升 */
    public static final Item INCENDIARY = register("incendiary",
            new IncendiaryItem(new Item.Settings().maxCount(2).attributeModifiers(
                    CounterStrikeGrenadeItem.preventSwingModifiers())));

    /** 莫洛托夫：落地生成橙色火焰（粒子），伤害随接触时间爬升 */
    public static final Item MOLOTOV = register("molotov",
            new MolotovItem(new Item.Settings().maxCount(2).attributeModifiers(
                    CounterStrikeGrenadeItem.preventSwingModifiers())));

    /** 诱饵弹：落地后按持枪者射速模式模拟枪声误导敌人（TACZ 时用真实枪械音效/射速） */
    public static final Item DECOY = register("decoy",
            new DecoyGrenadeItem(new Item.Settings().maxCount(2).attributeModifiers(
                    CounterStrikeGrenadeItem.preventSwingModifiers())));

    /** 按命名空间 cstmm 注册物品并返回注册后的实例。 */
    private static Item register(String name, Item item) {
        return Registry.register(Registries.ITEM, Identifier.of("cstmm", name), item);
    }

    /**
     * 【作用】物品注册入口（必须在注册表冻结前调用）：静态字段加载即完成注册；
     *         创建 CSTMM 独立创造模式物品栏并把 C4 + 全部手雷挂入其中。
     * 【被谁使用】Cstmm.onInitialize（ModGuardian 包装，异常时进入保护模式）。
     */
    public static void initialize() {
        // 【作用】注册独立物品栏：显示名用翻译键（zh_cn/en_us lang 提供），图标用 C4
        Registry.register(Registries.ITEM_GROUP, CSTMM_GROUP, FabricItemGroup.builder()
                .displayName(Text.translatable("itemGroup.cstmm"))
                .icon(() -> new ItemStack(C4))
                .build());
        // 【作用】把 CSTMM 物品挂入独立物品栏（不再加入原版战斗栏）
        ItemGroupEvents.modifyEntriesEvent(CSTMM_GROUP).register(entries -> {
            entries.add(C4);
            entries.add(HEGRENADE);
            entries.add(FLASHBANG);
            entries.add(SMOKEGRENADE);
            entries.add(INCENDIARY);
            entries.add(MOLOTOV);
            entries.add(DECOY);
        });
        Cstmm.LOGGER.info("[CSTMM - ModItems] Items registered: cstmm:c4, cstmm:hegrenade, cstmm:flashbang, cstmm:smokegrenade, cstmm:incendiary, cstmm:molotov, cstmm:decoy (group cstmm:main)");
    }
}
