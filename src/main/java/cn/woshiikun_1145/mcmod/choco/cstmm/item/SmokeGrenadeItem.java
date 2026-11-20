package cn.woshiikun_1145.mcmod.choco.cstmm.item;

import cn.woshiikun_1145.mcmod.choco.cstmm.entity.GrenadeType;
import cn.woshiikun_1145.mcmod.choco.cstmm.sound.ModSounds;
import net.minecraft.item.Item;

/**
 * 【作用】烟雾弹物品：绑定 GrenadeType.SMOKE_GRENADE 与拔销音。
 * 【被谁使用】ModItems（注册 cstmm:smokegrenade）、ThrowActionHandler（发起投掷）、
 *           GrenadeThrownPayload（服务端类型校验）。
 */
public class SmokeGrenadeItem extends CounterStrikeGrenadeItem {
    public SmokeGrenadeItem(Item.Settings settings) {
        super(GrenadeType.SMOKE_GRENADE, ModSounds.SMOKE_GRENADE_DRAW.value(), settings);
    }
}
