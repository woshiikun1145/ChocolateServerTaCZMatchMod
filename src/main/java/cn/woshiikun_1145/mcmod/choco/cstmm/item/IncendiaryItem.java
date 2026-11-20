package cn.woshiikun_1145.mcmod.choco.cstmm.item;

import cn.woshiikun_1145.mcmod.choco.cstmm.entity.GrenadeType;
import cn.woshiikun_1145.mcmod.choco.cstmm.sound.ModSounds;
import net.minecraft.item.Item;

/**
 * 【作用】燃烧弹物品：绑定 GrenadeType.INCENDIARY 与拔销音（与莫洛托夫共用 incendiary.draw）。
 * 【被谁使用】ModItems（注册 cstmm:incendiary）、ThrowActionHandler（发起投掷）。
 */
public class IncendiaryItem extends CounterStrikeGrenadeItem {
    public IncendiaryItem(Item.Settings settings) {
        super(GrenadeType.INCENDIARY, ModSounds.INCENDIARY_DRAW.value(), settings);
    }
}
