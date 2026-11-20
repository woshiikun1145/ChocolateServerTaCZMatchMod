package cn.woshiikun_1145.mcmod.choco.cstmm.item;

import cn.woshiikun_1145.mcmod.choco.cstmm.entity.GrenadeType;
import cn.woshiikun_1145.mcmod.choco.cstmm.sound.ModSounds;

/**
 * 【作用】诱饵弹物品（移植自 CounterStrikeGrenades 的 DecoyGrenadeItem）：
 *         绑定 DECOY_GRENADE 类型与拔销音（decoy.draw），投掷手感由客户端
 *         ThrowActionHandler 接管（含 "DecoySound" 自定义音效读取）。
 * 【被谁使用】ModItems.DECOY（注册）、ThrowActionHandler（类型判定与投掷包）、
 *           NetworkHandler（服务端手持类型校验）。
 */
public class DecoyGrenadeItem extends CounterStrikeGrenadeItem {

    public DecoyGrenadeItem(Settings settings) {
        super(GrenadeType.DECOY_GRENADE, ModSounds.DECOY_DRAW.value(), settings);
    }
}
