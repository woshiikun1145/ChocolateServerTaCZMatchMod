package cn.woshiikun_1145.mcmod.choco.cstmm.item;

import cn.woshiikun_1145.mcmod.choco.cstmm.entity.GrenadeType;
import cn.woshiikun_1145.mcmod.choco.cstmm.sound.ModSounds;
import net.minecraft.item.Item;

/**
 * 【作用】高爆手雷（HE）物品：绑定 GrenadeType.HEGRENADE 与拔销音。
 * 【被谁使用】ModItems（注册 cstmm:hegrenade）、ThrowActionHandler（发起投掷）、
 *           GrenadeThrownPayload（服务端类型校验）。
 */
public class HEGrenadeItem extends CounterStrikeGrenadeItem {
    public HEGrenadeItem(Item.Settings settings) {
        super(GrenadeType.HEGRENADE, ModSounds.HEGRENADE_DRAW.value(), settings);
    }
}
