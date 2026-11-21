package cn.woshiikun_1145.mcmod.choco.cstmm.entity;

/**
 * 【作用】手雷类型枚举（移植自 CSG（CounterStrikeGrenades）1.5.2 的 GrenadeType）。
 *         决定投掷时服务端生成哪种手雷实体；六类（HE/闪光/烟雾/燃烧/莫洛托夫/诱饵）均已实现。
 * 【被谁使用】GrenadeThrownPayload（C2S 投掷包的分发依据）、CounterStrikeGrenadeItem 子类
 *           （每种手雷物品绑定自己的类型）。
 */
public enum GrenadeType {
    /** 闪光弹 */
    FLASH_BANG,
    /** 烟雾弹 */
    SMOKE_GRENADE,
    /** 高爆手雷（HE） */
    HEGRENADE,
    /** 燃烧弹 */
    INCENDIARY,
    /** 莫洛托夫 */
    MOLOTOV,
    /** 诱饵弹 */
    DECOY_GRENADE,
}
