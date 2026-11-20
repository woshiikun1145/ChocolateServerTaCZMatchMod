package cn.woshiikun_1145.mcmod.choco.cstmm.item;

import cn.woshiikun_1145.mcmod.choco.cstmm.entity.GrenadeType;
import cn.woshiikun_1145.mcmod.choco.cstmm.sound.ModSounds;
import net.minecraft.item.Item;

/**
 * 【作用】闪光弹物品：绑定 GrenadeType.FLASH_BANG 与拔销音。
 * 【被谁使用】ModItems（注册 cstmm:flashbang）、ThrowActionHandler（发起投掷）、
 *           GrenadeThrownPayload（服务端类型校验）。
 */
public class FlashBangItem extends CounterStrikeGrenadeItem {
    public FlashBangItem(Item.Settings settings) {
        super(GrenadeType.FLASH_BANG, ModSounds.FLASHBANG_DRAW.value(), settings);
    }
}
