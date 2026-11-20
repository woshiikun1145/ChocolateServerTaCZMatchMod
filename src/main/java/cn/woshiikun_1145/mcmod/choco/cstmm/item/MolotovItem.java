package cn.woshiikun_1145.mcmod.choco.cstmm.item;

import cn.woshiikun_1145.mcmod.choco.cstmm.entity.GrenadeType;
import cn.woshiikun_1145.mcmod.choco.cstmm.sound.ModSounds;
import net.minecraft.item.Item;

/**
 * 【作用】莫洛托夫物品：绑定 GrenadeType.MOLOTOV 与拔销音（与燃烧弹共用 incendiary.draw）。
 * 【被谁使用】ModItems（注册 cstmm:molotov）、ThrowActionHandler（发起投掷）。
 */
public class MolotovItem extends CounterStrikeGrenadeItem {
    public MolotovItem(Item.Settings settings) {
        super(GrenadeType.MOLOTOV, ModSounds.INCENDIARY_DRAW.value(), settings);
    }
}
