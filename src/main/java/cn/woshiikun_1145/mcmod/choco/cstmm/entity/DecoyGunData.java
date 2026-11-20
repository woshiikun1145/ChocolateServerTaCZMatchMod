package cn.woshiikun_1145.mcmod.choco.cstmm.entity;

/**
 * 【作用】诱饵弹模拟的枪械数据（移植自 CounterStrikeGrenades 的 DecoyGunData）：
 *         枪械 ID / 射击模式（TACZ FireMode 枚举名，如 "AUTO"/"SEMI"）/ 射速 RPM / 射击间隔毫秒。
 *         fireMode 以字符串存储，避免对 TACZ 类型产生编译期依赖（运行时经反射取得）。
 * 【被谁使用】DecoyGrenadeEntity（fireShot 使用 gunId、有效性判定）、
 *           DecoyFirePatternGenerator（按 fireMode 选择连发/半自动时间戳生成）、
 *           util/TaczGunDataProvider（反射构造）。
 */
public record DecoyGunData(String gunId, String fireMode, int rpm, int shootIntervalMs) {

    /** 数据有效性：枪械 ID 非空白、射速与射击间隔均为正（与原版一致） */
    public boolean isValid() {
        return gunId != null && !gunId.isBlank() && rpm > 0 && shootIntervalMs > 0;
    }
}
