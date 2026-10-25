package cn.woshiikun_1145.mcmod.choco.cstmm.item;

import cn.woshiikun_1145.mcmod.choco.cstmm.Cstmm;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

/**
 * 【作用】模组自定义物品注册表（命名空间固定为 cstmm，与设计文档的物品 ID 约定一致）：
 *         cstmm:c4 = C4 炸药（爆破模式 T 方安放用）。
 *         拆弹装备不再是物品——持有状态用玩家 tag（cstmm_defuse_kit）表示，
 *         见 BombInteractionManager.DEFUSE_KIT_TAG。
 * 【被谁使用】Cstmm.onInitialize（启动时注册）；EconomyManager（C4 禁购校验）、
 *           BombRoundManager（回合开始发放/掉落物清理）、阶段③的安放交互（手持判定）。
 */
public class ModItems {

    /** C4 炸药（不可堆叠，每个回合内仅一枚在循环流转） */
    public static final Item C4 = register("c4", new Item(new Item.Settings().maxCount(1)));

    /** 按命名空间 cstmm 注册物品并返回注册后的实例。 */
    private static Item register(String name, Item item) {
        return Registry.register(Registries.ITEM, Identifier.of("cstmm", name), item);
    }

    /**
     * 【作用】物品注册入口（必须在注册表冻结前调用）：静态字段加载即完成注册，
     *         并把 C4 挂进创造模式物品栏（战斗栏，否则创造栏搜不到、无法手持拾取）。
     * 【被谁使用】Cstmm.onInitialize（ModGuardian 包装，异常时进入保护模式）。
     */
    public static void initialize() {
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.COMBAT)
                .register(entries -> entries.add(C4));
        Cstmm.LOGGER.info("[CSTMM - ModItems] Item registered: cstmm:c4");
    }
}
